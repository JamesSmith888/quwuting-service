package org.quwuting.quwutingservice.announcement.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.announcement.dto.request.CreateAnnouncementRequest;
import org.quwuting.quwutingservice.announcement.dto.request.PublishAnnouncementRequest;
import org.quwuting.quwutingservice.announcement.dto.request.UpdateAnnouncementRequest;
import org.quwuting.quwutingservice.announcement.dto.response.AdminAnnouncementResponse;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementDetailResponse;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementStatsResponse;
import org.quwuting.quwutingservice.announcement.dto.response.AnnouncementSummaryResponse;
import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.entity.AnnouncementRead;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementReadRepository;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 全局公告服务（2026-09-01，docs/agents/34-announcements.md 设计定稿）。
 * <p>
 * 双场景一套系统：运营公告（MANUAL）+ 数据更新公告（SYSTEM），差异仅 source。
 * 用户端只读 + 已读回执；管理端全生命周期（草稿 → 发布[立即/定时] → 下线 → 软删）。
 * <p>
 * 关键契约：
 * <ul>
 *   <li>已读幂等：existsBy 前置检查 + (user_id, announcement_id) 唯一索引兜底 23505；</li>
 *   <li>PUBLISHED 仅允许追加正文（新内容必须以旧内容为前缀），禁静默篡改已发公告；</li>
 *   <li>定时发布/下线由 @Scheduled 强转（状态权威在后端，publish 只写计划时间）；</li>
 *   <li>SYSTEM 公告 operator_id 恒 null（系统/Agent 来源审计先例）；</li>
 *   <li>数据更新公告同日防重：查询防重 + V7 生成列唯一索引兜底并发。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final String DEFAULT_UPDATE_TEMPLATE = "今日舞讯更新：新增 {new} 家门店、{reversed} 家门店恢复营业";
    /** 数据更新公告固定标题（正文由 ops-config 模板渲染） */
    private static final String DATA_UPDATE_TITLE = "今日舞讯更新";

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementReadRepository readRepository;
    private final UserRepository userRepository;
    private final OpsConfigService opsConfigService;

    // ── 用户端 ────────────────────────────────────────────────

    /** 可见公告列表（PUBLISHED + 已生效，pinned 优先倒序；read 批量派生） */
    @Transactional(readOnly = true)
    public Page<AnnouncementSummaryResponse> listVisible(Long userId, int page, int size) {
        // 排序由 findVisiblePage JPQL 内 ORDER BY 承担（pinned DESC, publishAt DESC, id DESC），
        // Pageable 不带 Sort——避免与 JPQL 排序重复拼接
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Announcement> result = announcementRepository.findVisiblePage(
                AnnouncementStatus.PUBLISHED, LocalDateTime.now(), pageable);
        Set<Long> readIds = result.isEmpty()
                ? Set.of()
                : announcementRepository.findReadAnnouncementIds(
                        userId, result.getContent().stream().map(Announcement::getId).collect(Collectors.toList()));
        return result.map(a -> new AnnouncementSummaryResponse(
                a.getId(), a.getTitle(), a.getCategory(), a.getSource(),
                a.isPinned(), a.getPublishAt(), readIds.contains(a.getId()), a.getCreatedAt()));
    }

    /** 未读公告数（首页公告条 / 我的页入口红点数据源） */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return announcementRepository.countUnread(AnnouncementStatus.PUBLISHED, LocalDateTime.now(), userId);
    }

    /** 公告详情（已下线/已软删 → 404；不自动标已读，由前端调 markRead） */
    @Transactional(readOnly = true)
    public AnnouncementDetailResponse detail(Long userId, Long id) {
        Announcement a = findPublished(id);
        boolean read = readRepository.existsByUserIdAndAnnouncementId(userId, id);
        return new AnnouncementDetailResponse(
                a.getId(), a.getTitle(), a.getContent(), a.getCategory(), a.getSource(),
                a.isPinned(), a.getPublishAt(), a.getPublishedAt(), read, a.getCreatedAt());
    }

    /** 标记已读（幂等：已读跳过；并发重复插入由唯一索引兜底 23505 静默） */
    @Transactional
    public void markRead(Long userId, Long id) {
        // 幂等前置：已读直接返回（不校验公告可见性——深链/过期公告的历史已读事实保留）
        if (readRepository.existsByUserIdAndAnnouncementId(userId, id)) {
            return;
        }
        try {
            AnnouncementRead read = new AnnouncementRead();
            read.setUserId(userId);
            read.setAnnouncementId(id);
            read.setReadAt(LocalDateTime.now());
            readRepository.save(read);
        } catch (DataIntegrityViolationException e) {
            // 并发重复标记：唯一索引 (user_id, announcement_id) 冲突 = 已读，幂等静默
        }
    }

    // ── 管理端 ────────────────────────────────────────────────

    /** 管理端列表（状态/分类/来源筛选，id 倒序） */
    @Transactional(readOnly = true)
    public Page<AdminAnnouncementResponse> adminList(AnnouncementStatus status,
                                                     AnnouncementCategory category,
                                                     AnnouncementSource source,
                                                     int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return announcementRepository.findPageByFilters(status, category, source, pageable)
                .map(this::toAdminResponse);
    }

    /** 管理端详情/编辑回显（不存在或已删 → 404） */
    @Transactional(readOnly = true)
    public AdminAnnouncementResponse adminDetail(Long id) {
        return toAdminResponse(findAny(id));
    }

    /** 创建（默认草稿；publishAt 未来时刻 = 计划发布时间，不影响状态） */
    @Transactional
    public AdminAnnouncementResponse create(CreateAnnouncementRequest request, Long adminId) {
        validateContent(request.content());
        Announcement a = new Announcement();
        applyFields(a, request.title(), request.content(), request.category(),
                request.pinned() != null && request.pinned(), request.publishAt(), request.offlineAt());
        a.setSource(AnnouncementSource.MANUAL); // 管理端创建恒 MANUAL（SYSTEM 走 createDataUpdateAnnouncement）
        a.setStatus(AnnouncementStatus.DRAFT);
        a.setOperatorId(adminId);
        a.setPublishAt(request.publishAt());
        return toAdminResponse(announcementRepository.save(a));
    }

    /**
     * 更新（状态机约束）：
     * DRAFT 全字段可改；PUBLISHED 仅允许追加正文（新内容以旧内容为前缀，其余字段锁定）；
     * OFFLINE 禁改（需重新 publish）。
     */
    @Transactional
    public AdminAnnouncementResponse update(Long id, UpdateAnnouncementRequest request, Long adminId) {
        Announcement a = findAny(id);
        if (a.getStatus() == AnnouncementStatus.OFFLINE) {
            throw new BusinessException(1001, "已下线公告不可编辑，如需变更请重新发布");
        }
        if (a.getStatus() == AnnouncementStatus.PUBLISHED) {
            if (!a.getTitle().equals(request.title())
                    || a.getCategory() != request.category()
                    || a.isPinned() != (request.pinned() != null && request.pinned())) {
                throw new BusinessException(1001, "已发布公告仅允许追加正文，标题/分类/置顶不可变更");
            }
            if (!request.content().startsWith(a.getContent())) {
                throw new BusinessException(1001, "已发布公告仅允许在原文末尾追加内容，禁止修改已有正文");
            }
            validateContent(request.content());
            a.setContent(request.content());
        } else {
            validateContent(request.content());
            applyFields(a, request.title(), request.content(), request.category(),
                    request.pinned() != null && request.pinned(), request.publishAt(), request.offlineAt());
        }
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /**
     * 发布：publishAt 缺省 = 立即（publish_at=now + 状态 PUBLISHED）；
     * 指定未来时刻 = 定时（写入 publish_at，状态保持 DRAFT，@Scheduled 到点强转）。
     * 仅 DRAFT 可发布；重新发布（OFFLINE → PUBLISHED）同语义。
     */
    @Transactional
    public AdminAnnouncementResponse publish(Long id, PublishAnnouncementRequest request, Long adminId) {
        Announcement a = findAny(id);
        LocalDateTime now = LocalDateTime.now();
        if (request != null && request.publishAt() != null) {
            if (!request.publishAt().isAfter(now)) {
                throw new BusinessException(1001, "定时发布时间必须晚于当前时间（立即发布请留空）");
            }
            a.setPublishAt(request.publishAt());
            a.setStatus(AnnouncementStatus.DRAFT);
        } else {
            a.setPublishAt(now);
            a.setPublishedAt(now);
            a.setStatus(AnnouncementStatus.PUBLISHED);
        }
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /** 下线：PUBLISHED → OFFLINE（不可直接回已发布，需重新 publish） */
    @Transactional
    public AdminAnnouncementResponse offline(Long id, Long adminId) {
        Announcement a = findAny(id);
        if (a.getStatus() != AnnouncementStatus.PUBLISHED) {
            throw new BusinessException(1001, "仅已发布公告可下线");
        }
        a.setStatus(AnnouncementStatus.OFFLINE);
        a.setOfflinedAt(LocalDateTime.now());
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /** 软删除（任意状态；已读回执保留——事实不删，膨胀归档预案） */
    @Transactional
    public void delete(Long id, Long adminId) {
        Announcement a = findAny(id);
        a.setDeleted(true);
        a.setOperatorId(adminId);
        announcementRepository.save(a);
    }

    /** 阅读统计：阅读人数 + 阅读率（分母 = 有效用户数） */
    @Transactional(readOnly = true)
    public AnnouncementStatsResponse stats(Long id) {
        findAny(id); // 存在性校验
        long readCount = readRepository.countByAnnouncementId(id);
        long totalUsers = userRepository.countByDeletedFalse();
        double readRate = totalUsers == 0 ? 0 : (double) readCount / totalUsers;
        return new AnnouncementStatsResponse(readCount, totalUsers, readRate);
    }

    // ── 数据更新公告（SYSTEM 来源，M4 钩子调用入口） ─────────────

    /**
     * 生成数据更新公告（内部通道，不暴露管理端创建接口）：
     * <ul>
     *   <li>开关关闭（announcement.data_update.enabled=false，默认）→ 直接返回（不产生公告）；</li>
     *   <li>同日防重：当天已存在 SYSTEM+DATA_UPDATE 公告 → 返回已存在（幂等，重复同步不重复发）；</li>
     *   <li>正文 = ops-config 模板渲染（占位符 {new}/{reversed}），标题固定「今日舞讯更新」。</li>
     * </ul>
     *
     * @return 创建/已存在的公告；开关关闭时返回 {@link Optional#empty()}
     */
    @Transactional
    public Optional<Announcement> createDataUpdateAnnouncement(int newVenues, int reversed) {
        if (!opsConfigService.isEnabled(OpsConfigService.KEY_ANNOUNCEMENT_DATA_UPDATE_ENABLED, false)) {
            return Optional.empty();
        }
        AnnouncementCategory category = AnnouncementCategory.DATA_UPDATE;
        List<Announcement> existing = announcementRepository.findForDay(
                AnnouncementSource.SYSTEM, category, LocalDate.now());
        if (!existing.isEmpty()) {
            log.info("[announcement] data-update announcement exists for today, skip: id={}", existing.get(0).getId());
            return Optional.of(existing.get(0));
        }
        String template = opsConfigService.getValue(OpsConfigService.KEY_ANNOUNCEMENT_DATA_UPDATE_TEMPLATE)
                .orElse(DEFAULT_UPDATE_TEMPLATE);
        String content = template
                .replace("{new}", String.valueOf(newVenues))
                .replace("{reversed}", String.valueOf(reversed));
        Announcement a = new Announcement();
        a.setTitle(DATA_UPDATE_TITLE);
        a.setContent(content);
        a.setCategory(category);
        a.setSource(AnnouncementSource.SYSTEM);
        a.setStatus(AnnouncementStatus.PUBLISHED);
        a.setPublishAt(LocalDateTime.now());
        a.setPublishedAt(a.getPublishAt());
        a.setOperatorId(null); // 系统来源恒 null（Agent 来源审计先例）
        try {
            Announcement saved = announcementRepository.save(a);
            log.info("[announcement] data-update announcement created: id={} new={} reversed={}", saved.getId(), newVenues, reversed);
            return Optional.of(saved);
        } catch (DataIntegrityViolationException e) {
            // 并发同日防重：生成列唯一索引兜底，静默返回已存在
            List<Announcement> winner = announcementRepository.findForDay(
                    AnnouncementSource.SYSTEM, category, LocalDate.now());
            return winner.isEmpty() ? Optional.empty() : Optional.of(winner.get(0));
        }
    }

    // ── 定时任务：计划发布/下线强转（状态权威） ─────────────────

    /**
     * 每 30s 扫一次：DRAFT + publish_at 已到 → PUBLISHED；PUBLISHED + offline_at 已到 → OFFLINE。
     * 批量 UPDATE 零业务副作用（状态权威 + publishedAt/offlinedAt 落库），转换数 >0 才记日志。
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void processScheduledTransitions() {
        LocalDateTime now = LocalDateTime.now();
        int published = announcementRepository.publishDue(
                AnnouncementStatus.DRAFT, AnnouncementStatus.PUBLISHED, now);
        int offlined = announcementRepository.offlineDue(
                AnnouncementStatus.PUBLISHED, AnnouncementStatus.OFFLINE, now);
        if (published > 0 || offlined > 0) {
            log.info("[announcement] scheduled transitions: published={} offlined={}", published, offlined);
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private void applyFields(Announcement a, String title, String content, AnnouncementCategory category,
                             boolean pinned, LocalDateTime publishAt, LocalDateTime offlineAt) {
        a.setTitle(title);
        a.setContent(content);
        a.setCategory(category);
        a.setPinned(pinned);
        a.setPublishAt(publishAt);
        a.setOfflineAt(offlineAt);
    }

    /** 用户端可见性校验：PUBLISHED + 已生效 + 未软删，否则 404（深链失效不渲染过期内容） */
    private Announcement findPublished(Long id) {
        Announcement a = announcementRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(404, "公告不存在或已下线"));
        if (a.getStatus() != AnnouncementStatus.PUBLISHED) {
            throw new BusinessException(404, "公告不存在或已下线");
        }
        if (a.getPublishAt() != null && a.getPublishAt().isAfter(LocalDateTime.now())) {
            throw new BusinessException(404, "公告不存在或已下线");
        }
        return a;
    }

    /** 管理端存在性校验（任意状态，软删 → 404） */
    private Announcement findAny(Long id) {
        return announcementRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(404, "公告不存在"));
    }

    /**
     * 内容安全基础校验（docs/agents/34 安全章节）：公告为 markdown 原文（不做
     * TextSanitizer 清洗——避免误伤 markdown 语法），仅拦截最危险的脚本/iframe
     * 原始标签；渲染侧由小程序 towxml 白名单兜底（htmlToNodes 白名单过滤）。
     */
    private void validateContent(String content) {
        if (content == null) return;
        String lower = content.toLowerCase();
        if (lower.contains("<script") || lower.contains("<iframe")) {
            throw new BusinessException(400, "公告内容包含不允许的标签");
        }
    }

    private AdminAnnouncementResponse toAdminResponse(Announcement a) {
        return new AdminAnnouncementResponse(
                a.getId(), a.getTitle(), a.getContent(), a.getCategory(), a.getSource(), a.getScope(),
                a.getStatus(), a.isPinned(), a.getPublishAt(), a.getOfflineAt(),
                a.getPublishedAt(), a.getOfflinedAt(), a.getOperatorId(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
