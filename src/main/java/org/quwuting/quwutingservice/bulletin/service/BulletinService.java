package org.quwuting.quwutingservice.bulletin.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementRepository;
import org.quwuting.quwutingservice.bulletin.BulletinExcerpt;
import org.quwuting.quwutingservice.bulletin.dto.request.AgentPublishBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.request.CreateBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.request.PublishBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.request.UpdateBulletinRequest;
import org.quwuting.quwutingservice.bulletin.dto.response.AdminBulletinResponse;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinDetailResponse;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinFeedItemResponse;
import org.quwuting.quwutingservice.bulletin.dto.response.BulletinReactionBadge;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 行业快讯服务（2026-09-10，docs/agents/47-bulletins.md 设计定稿）。
 * <p>
 * <b>领域定位</b>：快讯 = 平台单向发布的行业情报（停业 / 开闭店 / 时段调整），
 * 与公告（平台权威、强触达、平台背书）严格分离——弱触达（独立入口 + 列表，
 * 无红点无已读）、标注来源、平台不背书。
 * <p>
 * <b>信息架构（2026-09-10 二次定稿）</b>：快讯是<b>信息流</b>而非"列表 + 详情"——
 * 内容在列表内<b>完整内联呈现</b>（每条的气泡即全部内容，含 markdown/图片/视频），
 * 详情页退化为"长文深读 + 分享落地"通道。根因见 47 号文档「一、领域定位与信息架构」
 * （把公告的列表-详情结构套到内容模态完全不同的快讯上，读一句话要走两次请求）。
 * 因此 {@link #listVisible} 下发 content 全文，并附带该条的表态徽标。
 * <p>
 * <b>实现策略</b>：复用 {@code qwt_announcements} 实体与
 * {@link AnnouncementRepository}（同表 + category='FLASH' 区分），复用状态机
 * 与定时发布/下线调度；但接口契约、DTO、入口全部独立，公告域契约零改动。
 * 与 {@code AnnouncementService} 存在少量重复代码（校验/状态流转），这是有意
 * 为之的 trade-off：两个域可以各自独立演进，不因改一个而牵动另一个。
 * <p>
 * 关键契约：
 * <ul>
 *   <li><b>category 恒 FLASH</b>：接口不接受调用方指定；查询侧靠 Repository 的
 *       category / excludeCategory 参数与公告互斥（读公告详情不返回快讯，反之亦然）；</li>
 *   <li><b>source 恒定</b>：管理端创建 = MANUAL，Agent 通道 = AGENT（均服务端固定）；</li>
 *   <li><b>无已读回执</b>：不进公告未读数（公告侧 excludeCategory=FLASH 已隔离）；</li>
 *   <li><b>无置顶语义</b>：纯时间流，排序恒为 publishAt ASC（旧 → 新，最新在底部，
 *       2026-09-11 用户拍板，见 {@code findBulletinFeedPage}）；</li>
 *   <li><b>可见性单一事实源</b>：FLASH + PUBLISHED + 已生效，读/写两侧统一走
 *       {@link BulletinLookupService}（表态域复用同一判据）；</li>
 *   <li><b>Agent 幂等</b>：dedupKey 命中返回已存在条目（不改写字段）+
 *       V18 生成列唯一索引兜底并发撞键。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BulletinService {

    /** 快讯固定分类：唯一事实源在 {@link BulletinLookupService#CATEGORY}（读写两侧共用） */
    private static final AnnouncementCategory CATEGORY = BulletinLookupService.CATEGORY;

    /** 用户端列表分页上限（同公告域 findVisiblePage 的 Math.min(size, 50) 口径） */
    private static final int MAX_PAGE_SIZE = 50;

    /** 管理端列表分页上限（同公告域 adminList 的 Math.min(size, 100) 口径） */
    private static final int MAX_ADMIN_PAGE_SIZE = 100;

    private final AnnouncementRepository announcementRepository;
    private final VenueRepository venueRepository;
    private final BulletinLookupService bulletinLookupService;
    private final BulletinReactionService bulletinReactionService;
    private final BulletinViewService bulletinViewService;

    // ── 用户端 ────────────────────────────────────────────────

    /**
     * 可见快讯流（PUBLISHED + 已生效，**时间正序：旧 → 新，最新一条在底部**——
     * 2026-09-11 用户拍板，聊天式信息流；见 {@code findBulletinFeedPage} 判据）。
     * <p>
     * pinned 恒传 null：快讯是纯时间流，无置顶语义（公告域「置顶进首页」的机制
     * 不适用于快讯——首页公告条只服务平台权威内容）。一期不做城市筛选，
     * city 仅随条目返回给列表气泡作展示标签。
     * <p>
     * <b>每项携带 content 全文与 reactions 徽标</b>（2026-09-10 信息流改造）：
     * 列表页内联渲染全文，故正文必须随列表下发；表态按整页 id 一次批量聚合
     * （两条 IN 查询，无 N+1、无缓存）。
     */
    @Transactional(readOnly = true)
    public Page<BulletinFeedItemResponse> listVisible(int page, int size, Long currentUserId) {
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_PAGE_SIZE));
        Page<Announcement> found = announcementRepository.findBulletinFeedPage(
                CATEGORY, AnnouncementStatus.PUBLISHED, LocalDateTime.now(), pageable);
        List<Long> ids = found.getContent().stream().map(Announcement::getId).collect(Collectors.toList());
        Map<Long, List<BulletinReactionBadge>> reactions =
                bulletinReactionService.batchBadges(ids, currentUserId);
        Map<Long, Long> viewCounts = bulletinViewService.countByBulletinIds(ids);
        return found.map(a -> toFeedItem(a,
                reactions.getOrDefault(a.getId(), Collections.emptyList()),
                viewCounts.getOrDefault(a.getId(), 0L)));
    }

    /** 快讯详情（未发布/已下线/已软删/非 FLASH → 404）；表态徽标与列表同口径 */
    @Transactional(readOnly = true)
    public BulletinDetailResponse detail(Long id, Long currentUserId) {
        Announcement a = bulletinLookupService.requirePublished(id);
        return new BulletinDetailResponse(
                a.getId(), BulletinExcerpt.of(a.getContent()), a.getContent(), a.getCity(), a.getVenueId(),
                a.getPublishAt(), a.getPublishedAt(), a.getCreatedAt(),
                bulletinReactionService.badges(a.getId(), currentUserId),
                bulletinViewService.countByBulletinId(a.getId()));
    }

    // ── 管理端 ────────────────────────────────────────────────

    /** 管理端列表（状态/来源筛选，id 倒序；恒只取 FLASH） */
    @Transactional(readOnly = true)
    public Page<AdminBulletinResponse> adminList(AnnouncementStatus status,
                                                 AnnouncementSource source,
                                                 int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, MAX_ADMIN_PAGE_SIZE));
        return announcementRepository.findPageByFilters(status, CATEGORY, null, source, pageable)
                .map(this::toAdminResponse);
    }

    /** 管理端详情/编辑回显（非 FLASH 或已删 → 404） */
    @Transactional(readOnly = true)
    public AdminBulletinResponse adminDetail(Long id) {
        return toAdminResponse(findAny(id));
    }

    /** 创建快讯（存草稿；source 固定 MANUAL；publishAt 未来时刻 = 计划发布时间） */
    @Transactional
    public AdminBulletinResponse create(CreateBulletinRequest request, Long adminId) {
        validateContent(request.content());
        validateSchedule(request.publishAt(), request.offlineAt());
        Announcement a = new Announcement();
        a.setTitle(BulletinExcerpt.of(request.content()));
        a.setContent(request.content());
        a.setCategory(CATEGORY);
        a.setSource(AnnouncementSource.MANUAL);
        a.setCity(normalizeCity(request.city()));
        a.setVenueId(validateVenueId(request.venueId()));
        a.setStatus(AnnouncementStatus.DRAFT);
        a.setPublishAt(request.publishAt());
        a.setOfflineAt(request.offlineAt());
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /**
     * 更新快讯（状态机同公告域）：
     * <ul>
     *   <li>DRAFT：全字段可改（含定时发布 publishAt）；</li>
     *   <li>PUBLISHED：除 publishAt 外全字段可改并即时生效（运营纠错刚需，
     *       operator_id 审计兜底）；</li>
     *   <li>OFFLINE：禁改（需重新 publish 走新发布周期）。</li>
     * </ul>
     */
    @Transactional
    public AdminBulletinResponse update(Long id, UpdateBulletinRequest request, Long adminId) {
        Announcement a = findAny(id);
        if (a.getStatus() == AnnouncementStatus.OFFLINE) {
            throw new BusinessException(1001, "已下线快讯不可编辑，如需变更请重新发布");
        }
        validateContent(request.content());
        Long venueId = validateVenueId(request.venueId());
        if (a.getStatus() == AnnouncementStatus.PUBLISHED) {
            // 发布中：publishAt 已生效不可改（既不校验也不落库）
            validateSchedule(null, request.offlineAt());
            a.setTitle(BulletinExcerpt.of(request.content()));
            a.setContent(request.content());
            a.setOfflineAt(request.offlineAt());
        } else {
            validateSchedule(request.publishAt(), request.offlineAt());
            a.setTitle(BulletinExcerpt.of(request.content()));
            a.setContent(request.content());
            a.setPublishAt(request.publishAt());
            a.setOfflineAt(request.offlineAt());
        }
        a.setCity(normalizeCity(request.city()));
        a.setVenueId(venueId);
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /**
     * 发布：publishAt 缺省 = 立即（publish_at=now + 状态 PUBLISHED）；
     * 指定未来时刻 = 定时（写 publish_at，状态保持 DRAFT，@Scheduled 到点强转）。
     * 同公告域：过期遗留 offlineAt 在重新发布时清空，否则 30s 调度会立刻再度下线。
     */
    @Transactional
    public AdminBulletinResponse publish(Long id, PublishBulletinRequest request, Long adminId) {
        Announcement a = findAny(id);
        LocalDateTime now = LocalDateTime.now();
        if (request != null && request.publishAt() != null) {
            if (!request.publishAt().isAfter(now)) {
                throw new BusinessException(1001, "定时发布时间必须晚于当前时间（立即发布请留空）");
            }
            if (a.getOfflineAt() != null && !a.getOfflineAt().isAfter(now)) {
                a.setOfflineAt(null);
            } else if (a.getOfflineAt() != null && !a.getOfflineAt().isAfter(request.publishAt())) {
                throw new BusinessException(1001, "自动下线时间必须晚于定时发布时间，请先在草稿中调整");
            }
            a.setPublishAt(request.publishAt());
            a.setStatus(AnnouncementStatus.DRAFT);
        } else {
            if (a.getOfflineAt() != null && !a.getOfflineAt().isAfter(now)) {
                a.setOfflineAt(null);
            }
            a.setPublishAt(now);
            a.setPublishedAt(now);
            a.setStatus(AnnouncementStatus.PUBLISHED);
        }
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /** 下线：PUBLISHED → OFFLINE（恢复需重新 publish） */
    @Transactional
    public AdminBulletinResponse offline(Long id, Long adminId) {
        Announcement a = findAny(id);
        if (a.getStatus() != AnnouncementStatus.PUBLISHED) {
            throw new BusinessException(1001, "仅已发布快讯可下线");
        }
        a.setStatus(AnnouncementStatus.OFFLINE);
        a.setOfflinedAt(LocalDateTime.now());
        a.setOperatorId(adminId);
        return toAdminResponse(announcementRepository.save(a));
    }

    /** 软删除（任意状态；软删后 dedupKey 自动退出唯一约束，见 V18 生成列条件） */
    @Transactional
    public void delete(Long id, Long adminId) {
        Announcement a = findAny(id);
        a.setDeleted(true);
        a.setOperatorId(adminId);
        announcementRepository.save(a);
    }

    // ── Agent 一步发布（机器友好 + 幂等，docs/agents/47 接口契约） ──────

    /**
     * Agent 一步发布（create + publish 合并，agent 场景不需要草稿态）。
     * <p>
     * 幂等语义：{@code dedupKey} 命中已有未软删条目 → 直接返回该条目且<b>不改写任何
     * 字段</b>（重跑 = 「确保这条存在」，不是「覆盖」——采集源事后纠错要靠人工
     * 走管理端 update，不能被重跑悄悄盖掉）。未命中则新建，由 V18 生成列唯一索引
     * 兜底并发撞键（与公告域 data-update 同日防重同款两层防护）。
     * <p>
     * source 由服务端固定 AGENT（请求体不可指定）；operator_id 记调用管理员审计。
     */
    @Transactional
    public AdminBulletinResponse agentPublish(AgentPublishBulletinRequest request, Long adminId) {
        String dedupKey = normalizeDedupKey(request.dedupKey());
        if (dedupKey != null) {
            Optional<Announcement> existing = announcementRepository
                    .findFirstByDedupKeyAndDeletedFalseOrderByIdDesc(dedupKey);
            if (existing.isPresent()) {
                log.info("[bulletin] agent publish dedup hit: dedupKey={} id={}",
                        dedupKey, existing.get().getId());
                return toAdminResponse(existing.get());
            }
        }
        validateContent(request.content());
        validateSchedule(request.publishAt(), request.offlineAt());
        LocalDateTime now = LocalDateTime.now();
        Announcement a = new Announcement();
        a.setTitle(BulletinExcerpt.of(request.content()));
        a.setContent(request.content());
        a.setCategory(CATEGORY);
        a.setSource(AnnouncementSource.AGENT);
        a.setCity(normalizeCity(request.city()));
        a.setVenueId(validateVenueId(request.venueId()));
        a.setDedupKey(dedupKey);
        a.setOperatorId(adminId);
        if (request.publishAt() != null && request.publishAt().isAfter(now)) {
            // 定时发布：写计划时间，状态保持 DRAFT，@Scheduled 到点强转
            a.setPublishAt(request.publishAt());
            a.setOfflineAt(request.offlineAt());
            a.setStatus(AnnouncementStatus.DRAFT);
        } else {
            a.setPublishAt(now);
            a.setPublishedAt(now);
            a.setOfflineAt(request.offlineAt());
            a.setStatus(AnnouncementStatus.PUBLISHED);
        }
        try {
            Announcement saved = announcementRepository.save(a);
            log.info("[bulletin] agent published: id={} dedupKey={} city={}",
                    saved.getId(), dedupKey, saved.getCity());
            return toAdminResponse(saved);
        } catch (DataIntegrityViolationException e) {
            // 并发同 dedupKey 撞唯一索引 → 取回已存在条目（幂等返回）
            if (dedupKey != null) {
                Optional<Announcement> winner = announcementRepository
                        .findFirstByDedupKeyAndDeletedFalseOrderByIdDesc(dedupKey);
                if (winner.isPresent()) {
                    log.info("[bulletin] agent publish dedup race resolved: dedupKey={} id={}",
                            dedupKey, winner.get().getId());
                    return toAdminResponse(winner.get());
                }
            }
            throw new BusinessException(1001, "快讯发布冲突，请重试");
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    /**
     * 用户端可见性校验：委托 {@link BulletinLookupService#requirePublished}（规则唯一事实源，
     * 表态域共用同一判据，避免"详情可见但不可表态"这类口径分叉）。
     */
    private Announcement findPublished(Long id) {
        return bulletinLookupService.requirePublished(id);
    }

    /** 管理端存在性校验：委托 {@link BulletinLookupService#requireAny}（防跨域读写公告条目） */
    private Announcement findAny(Long id) {
        return bulletinLookupService.requireAny(id);
    }

    /**
     * 内容安全基础校验（同公告域口径）：快讯正文是 markdown 原文，不做
     * TextSanitizer 清洗（避免误伤 markdown 语法），仅拦截最危险的脚本/iframe
     * 原始标签；渲染侧由小程序 towxml 白名单兜底。
     */
    private void validateContent(String content) {
        if (content == null) return;
        String lower = content.toLowerCase();
        if (lower.contains("<script") || lower.contains("<iframe")) {
            throw new BusinessException(400, "快讯内容包含不允许的标签");
        }
    }

    /**
     * 调度窗口校验（创建/更新/Agent 发布共用）：offlineAt 设置时必须晚于当前时间；
     * 若 publishAt 参与校验（草稿态/Agent 定时）则 offlineAt 还必须晚于发布时间。
     *
     * @param publishAt null = 不校验该项（发布中编辑时传入——publishAt 已生效不可改）
     */
    private void validateSchedule(LocalDateTime publishAt, LocalDateTime offlineAt) {
        if (offlineAt == null) {
            return;
        }
        if (!offlineAt.isAfter(LocalDateTime.now())) {
            throw new BusinessException(1001, "自动下线时间必须晚于当前时间");
        }
        if (publishAt != null && !offlineAt.isAfter(publishAt)) {
            throw new BusinessException(1001, "自动下线时间必须晚于发布时间");
        }
    }

    /** 城市标签规范化：trim；空串 → null（列表卡片仅在非空时渲染标签） */
    private String normalizeCity(String city) {
        if (city == null) return null;
        String v = city.trim();
        return v.isEmpty() ? null : v;
    }

    /** 幂等键规范化：trim；空串 → null（null = 不参与唯一约束，每次新建） */
    private String normalizeDedupKey(String dedupKey) {
        if (dedupKey == null) return null;
        String v = dedupKey.trim();
        return v.isEmpty() ? null : v;
    }

    /**
     * 关联门店真实性校验（可空）：脏 venueId 会让列表卡片的门店锚点跳向 404，
     * 与公告域「发布前核 venueId 真实性」的教训同源（docs/agents/34 qw-demo cta 事故）。
     */
    private Long validateVenueId(Long venueId) {
        if (venueId == null) {
            return null;
        }
        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(400, "关联门店不存在");
        }
        return venueId;
    }

    /** 列表项映射：内容全文 + 该条的表态徽标 + 累计查看人数（列表页内联渲染所需的最小完整集合） */
    private BulletinFeedItemResponse toFeedItem(Announcement a, List<BulletinReactionBadge> reactions,
                                                Long viewCount) {
        return new BulletinFeedItemResponse(
                a.getId(), BulletinExcerpt.of(a.getContent()), a.getContent(), a.getCity(), a.getVenueId(),
                a.getPublishAt(), a.getCreatedAt(), reactions, viewCount);
    }

    private AdminBulletinResponse toAdminResponse(Announcement a) {
        return new AdminBulletinResponse(
                a.getId(), BulletinExcerpt.of(a.getContent()), a.getContent(), a.getCity(), a.getVenueId(),
                a.getDedupKey(), a.getSource(), a.getStatus(),
                a.getPublishAt(), a.getOfflineAt(), a.getPublishedAt(), a.getOfflinedAt(),
                a.getOperatorId(), a.getCreatedAt(), a.getUpdatedAt());
    }
}
