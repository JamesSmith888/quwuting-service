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
import org.quwuting.quwutingservice.announcement.dto.response.HomeSlotResponse;
import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.entity.AnnouncementRead;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementTouchLevel;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementReadRepository;
import org.quwuting.quwutingservice.announcement.repository.AnnouncementRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.media.MediaAttachment;
import org.quwuting.quwutingservice.media.MediaAttachments;
import org.quwuting.quwutingservice.media.MediaAttachmentValidator;
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
 *   <li>可见公告可在线编辑（2026-09-05 修订）：PUBLISHED 除 publishAt 外全字段可改并
 *       即时生效（旧「仅允许追加正文」契约已废弃——运营纠错刚需，代价是不再防静默
 *       篡改，由 operator_id 审计兜底）；publishAt 已生效不可改，OFFLINE 禁改；</li>
 *   <li><b>首页公告位（2026-10-08 根因修复）</b>：首页公告位是<b>容量为 1 的稀缺资源</b>，
 *       而 {@code pinned} 是布尔列——布尔表达不了"唯一占用"，这是原缺陷的根因。
 *       修复不放在读端（{@code size=1} 那种"读端容量限制"）而是搬到写入端：
 *       ① {@link HomeSlotService} = 占位记账唯一写入点（校验 + 显式拒绝冲突）；
 *       ② V44 生成列 + UNIQUE INDEX = 数据层兜底；
 *       ③ {@code processScheduledTransitions} 调 {@code reconcile()} 自愈脏数据。
 *       消费端 {@code listAnnouncements(0, 1, true)} 契约不变（占位至多一条，取它即真值）；
 *       下方「首页位可见面」说明"多条同日公告如何被触达"——靠未读债务，不靠多条占位。</li>
 *   <li>定时发布/下线由 @Scheduled 强转（状态权威在后端，publish 只写计划时间）；</li>
 *   <li>生命周期闭环（2026-09-02）：可见性 = PUBLISHED 且 publishAt ≤ now 且 offlineAt
 *       未到（offlineAt 到点强转 OFFLINE）；草稿保存校验调度窗口；重发布清空过期遗留
 *       offlineAt（OFFLINE 复活唯一通道，不清空会被 offlineDue 立即再度下线）；SYSTEM
 *       数据更新公告按 ops-config auto_offline_hours（默认 24h）自动过期；</li>
 *   <li><b>下线/ 软删必须释放首页位</b>（2026-10-08）：否则过期公告永久霸占首页位
 *       ——历史 36 条幽灵置顶正是"永不下线 + 置顶"共同造成的；</li>
 *   <li>SYSTEM 公告 operator_id 恒 null（系统/Agent 来源审计先例）；</li>
 *   <li>数据更新公告同日防重：查询防重 + V7 生成列唯一索引兜底并发；</li>
 *   <li><b>未读口径（2026-09-15 收敛）</b>：只有 {@code touchLevel = ALERT} 的公告
 *       才构成用户未读债务（见 {@link AnnouncementTouchLevel} 的根因说明）。SILENT
 *       （数据更新 / 每日舞讯等流水）照常可见可查、恒不计入未读徽标与未读红点。
 *       列表项的 {@code unread} 字段即本判据的派生结果（{@link #isUnread} 为唯一
 *       实现），前端零分支消费。</li>
 * </ul>
 *
 * <h3>首页位可见面：为什么不需要"多条同时置顶"</h3>
 * 首页公告位是<b>一个</b>胶囊（几何上限 {@code max-width: 362rpx}，避让右侧系统胶囊按钮，
 * 单行文字）——物理上只能承载一条，且多条并显会互相削弱（每条曝光时间被摊薄）。
 * 因此"一天内多条公告都要触达用户"的正确模型不是"多条占位"，而是：
 * <ul>
 *   <li><b>位置（1 条）</b> = {@code pinned} 首页位，由运营显式决策占谁；</li>
 *   <li><b>打扰（N 条）</b> = {@code touchLevel=ALERT} 的未读债务，走「我的 → 公告中心」
 *       徽标 + 公告中心列表，未读数已按此口径统计（{@link #unreadCount}）。</li>
 * </ul>
 * 两条通道正交且各自唯一判据：置顶管"位置"、touchLevel 管"债务"。
 * 同日多条公告各自勾置顶会被<b>显式拒绝</b>（报错带上当前占位者标题），
 * 运营要么换一条占位，要么靠 ALERT 未读让其余条目在公告中心被看到——动作结果始终可见。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    private static final String DEFAULT_UPDATE_TEMPLATE = "今日舞讯更新：新增 {new} 家门店、{reversed} 家门店恢复营业";
    /** 数据更新公告固定标题（正文由 ops-config 模板渲染） */
    private static final String DATA_UPDATE_TITLE = "今日舞讯更新";
    /** 数据更新公告自动下线缺省时长（小时）：舞讯公告时效性 = 当日，过期自动下线 */
    private static final long DEFAULT_DATA_UPDATE_AUTO_OFFLINE_HOURS = 24;

    private final AnnouncementRepository announcementRepository;
    private final AnnouncementReadRepository readRepository;
    private final UserRepository userRepository;
    private final OpsConfigService opsConfigService;
    private final MediaAttachmentValidator mediaAttachmentValidator;
    /** 首页公告位记账（占位语义唯一写入点，2026-10-08；不变量与根因见该类） */
    private final HomeSlotService homeSlotService;

    // ── 用户端 ────────────────────────────────────────────────

    /**
     * 可见公告列表（PUBLISHED + 已生效，pinned 优先倒序；read/unread 批量派生）。
     * <p>
     * <b>匿名可读</b>（2026-09-30，与快讯域同源）：{@code userId} 允许为 null（未登录），
     * 此时逐条 read/unread 恒 false——首页公告条与公告中心都无需登录即可渲染。
     *
     * @param userId 当前用户 id；<b>可为 null</b>（匿名）
     * @param pinned null = 全量（公告中心）；true = 仅置顶（首页公告栏数据源，
     *               2026-09-05 契约：非置顶公告不进首页，只在公告中心出现）
     */
    @Transactional(readOnly = true)
    public Page<AnnouncementSummaryResponse> listVisible(Long userId, int page, int size, Boolean pinned) {
        // 排序由 findVisiblePage JPQL 内 ORDER BY 承担（pinned DESC, publishAt DESC, id DESC），
        // Pageable 不带 Sort——避免与 JPQL 排序重复拼接
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        // category=null + excludeCategory=FLASH：公告域只取 NOTICE/DATA_UPDATE，
        // 快讯（FLASH）走独立接口，绝不混入公告中心/首页公告条（docs/agents/47 互斥契约）
        Page<Announcement> result = announcementRepository.findVisiblePage(
                null, AnnouncementCategory.FLASH, AnnouncementStatus.PUBLISHED,
                LocalDateTime.now(), pinned, pageable);
        // 匿名（userId == null）没有回执可查 ⇒ 直接空集，逐条 read/unread 恒 false。
        // 守卫写在这里而不是仓储层：「匿名不存在已读回执」是**领域事实**，由服务层表达；
        // 仓储不必为一种不存在的身份构造 SQL 分支（也避免 IN () 空集语义泄漏到查询层）。
        Set<Long> readIds = result.isEmpty() || userId == null
                ? Set.of()
                : announcementRepository.findReadAnnouncementIds(
                        userId, result.getContent().stream().map(Announcement::getId).collect(Collectors.toList()));
        return result.map(a -> new AnnouncementSummaryResponse(
                a.getId(), a.getTitle(), a.getCategory(), a.getSource(),
                a.isPinned(), a.getPublishAt(),
                readIds.contains(a.getId()),
                isUnread(a, readIds),
                a.getCreatedAt()));
    }

    /**
     * 未读公告数（我的页「公告中心」入口徽标数据源）。
     * <p>
     * 口径 = <b>需触达（ALERT）</b>的可见未读公告；SILENT（每日舞讯等流水）恒不计入
     * —— 2026-09-15 收敛，见 {@link AnnouncementTouchLevel}。excludeCategory=FLASH：
     * 快讯无已读回执，不得计入。
     */
    @Transactional(readOnly = true)
    public long unreadCount(Long userId) {
        return announcementRepository.countUnread(
                AnnouncementCategory.FLASH, AnnouncementStatus.PUBLISHED,
                AnnouncementTouchLevel.ALERT, LocalDateTime.now(), userId);
    }

    /**
     * 未读债务判据（<b>唯一实现单点</b>，2026-09-15）：仅 ALERT 等级的公告构成未读；
     * SILENT（数据更新 / 每日舞讯等流水）恒不计。
     * <p>
     * 列表未读点、我的页徽标、首页公告条未读点全部消费本判据派生的 {@code unread}
     * 字段（前端零分支）——三处渲染不再各自解释"什么算未读"。
     * <p>
     * 判据写成 {@code != SILENT} 而非 {@code == ALERT}：存量 / 未知值一律按"需触达"
     * 处理（保守方向 = 宁可多提醒一次，也不静默丢掉触达）。
     */
    private static boolean isUnread(Announcement a, Set<Long> readIds) {
        return a.getTouchLevel() != AnnouncementTouchLevel.SILENT && !readIds.contains(a.getId());
    }

    /**
     * 公告详情（已下线/已软删 → 404；不自动标已读，由前端调 markRead）。
     * <p>
     * <b>匿名可读</b>（2026-09-30）：{@code userId} 允许为 null，此时 {@code read} 恒 false
     * ——匿名用户没有回执，也就不存在"已读"这个事实。
     */
    @Transactional(readOnly = true)
    public AnnouncementDetailResponse detail(Long userId, Long id) {
        Announcement a = findPublished(id);
        boolean read = userId != null && readRepository.existsByUserIdAndAnnouncementId(userId, id);
        return new AnnouncementDetailResponse(
                a.getId(), a.getTitle(), a.getContent(), a.getCategory(), a.getSource(),
                a.isPinned(), MediaAttachments.parseOrEmpty(a.getMediaJson()),
                a.getPublishAt(), a.getPublishedAt(), read, a.getCreatedAt());
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

    /**
     * 全部已读（2026-09-15，docs/agents/34「未读收敛通道」）。
     * <p>
     * 用户主动声明「这些我都知道了」——一次性为全部未读的<b>需触达</b>公告补写已读回执，
     * 把"逐条点进详情"的 O(N) 收敛成本降为一次点击。补这个通道的理由：未读分级
     * （{@link AnnouncementTouchLevel}）让日常不再积压，但只要还存在"积压"的可能
     * （长期未登录、连续多条重要公告），用户就仍需要一条一次点击清零的逃生出口——
     * 只靠"少发"来避免积压，是把系统的债转嫁给用户操作。
     * <p>
     * <b>语义边界</b>：这是<b>用户主动动作</b>，回执如实记录"用户声明已读"这一事实，
     * 因此不影响阅读统计口径（与逐条点开详情得到的回执同构）。本项目刻意<b>不</b>采用
     * 站内信的"进入列表即全读"——公告是运营内容，不替用户做已读决定
     * （2026-09-01 决策记录，见 docs/agents/34）。
     * <p>
     * 只覆盖 ALERT 公告：SILENT 本就不计入未读，写回执既无意义又会污染阅读率。
     *
     * @return 收敛后的未读数（重新统计的权威值，前端直接采用，省掉一次往返）
     */
    @Transactional
    public long markAllRead(Long userId) {
        LocalDateTime now = LocalDateTime.now();
        int inserted = readRepository.markVisibleReads(
                userId, AnnouncementStatus.PUBLISHED.name(),
                AnnouncementTouchLevel.ALERT.name(), AnnouncementCategory.FLASH.name(), now);
        if (inserted > 0) {
            log.info("[announcement] read-all: userId={} marked={}", userId, inserted);
        }
        return announcementRepository.countUnread(
                AnnouncementCategory.FLASH, AnnouncementStatus.PUBLISHED,
                AnnouncementTouchLevel.ALERT, now, userId);
    }

    // ── 管理端 ────────────────────────────────────────────────

    /** 管理端列表（状态/分类/来源筛选，id 倒序）；excludeCategory=FLASH——快讯走独立管理菜单 */
    @Transactional(readOnly = true)
    public Page<AdminAnnouncementResponse> adminList(AnnouncementStatus status,
                                                     AnnouncementCategory category,
                                                     AnnouncementSource source,
                                                     int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return announcementRepository.findPageByFilters(status, category, AnnouncementCategory.FLASH, source, pageable)
                .map(this::toAdminResponse);
    }

    /** 管理端详情/编辑回显（不存在或已删 → 404） */
    @Transactional(readOnly = true)
    public AdminAnnouncementResponse adminDetail(Long id) {
        return toAdminResponse(findAny(id));
    }

    /**
     * 首页公告位当前占用者（2026-10-08）：管理端编辑页的<b>占位可见性</b>数据源。
     *
     * <p><b>为什么必须有它</b>：占位是<b>独占</b>语义（容量 = 1），所以运营在勾"首页置顶"
     * 之前必须知道位上是谁。否则只能提交后吃一个 400——"动作结果对运营不可见"正是本轮
     * 根因的第四层（09-15 修 touchLevel 时已立同一条纪律：后果必须对运营可见）。
     * <p>
     * 判据单点 = {@link HomeSlotService#findHolder()}；禁在管理端各处重写"谁占着位"的查询。
     */
    @Transactional(readOnly = true)
    public HomeSlotResponse adminHomeSlot() {
        return homeSlotService.findHolder()
                .map(a -> new HomeSlotResponse(a.getId(), a.getTitle(), a.getPublishAt()))
                .orElseGet(() -> new HomeSlotResponse(null, null, null));
    }

    /** 创建（默认草稿；publishAt 未来时刻 = 计划发布时间，不影响状态） */
    @Transactional
    public AdminAnnouncementResponse create(CreateAnnouncementRequest request, Long adminId) {
        validateContent(request.content());
        rejectFlashCategory(request.category());
        validateSchedule(request.publishAt(), request.offlineAt());
        Announcement a = new Announcement();
        // 触达等级先解析（占位判据必须消费它，禁两处各判一次——见 resolvePinned）
        AnnouncementTouchLevel touchLevel = resolveTouchLevel(request.touchLevel(), request.category());
        boolean pinned = resolvePinned(request.pinned(), touchLevel);
        applyFields(a, request.title(), request.content(), request.category(),
                touchLevel, pinned, request.publishAt(), request.offlineAt());
        applyMedia(a, request.media());
        a.setSource(AnnouncementSource.MANUAL); // 管理端创建恒 MANUAL（SYSTEM 走 createDataUpdateAnnouncement）
        a.setStatus(AnnouncementStatus.DRAFT);
        a.setOperatorId(adminId);
        a.setPublishAt(request.publishAt());
        applyPinned(a, pinned);
        return toAdminResponse(announcementRepository.save(a));
    }

    /**
     * 更新（状态机约束，**2026-09-05 修订：发布中可编辑**）：
     * <ul>
     *   <li>DRAFT：全字段可改（含定时发布 publishAt）；</li>
     *   <li>PUBLISHED：除「定时发布」外全字段可改并即时生效——标题/正文/分类/置顶/
     *       自动下线均可在线修订（运营纠错刚需；旧契约「仅允许追加正文」已废弃，
     *       它让错别字/过期标题只能靠下线重发，代价远大于静默篡改风险——公告本就是
     *       平台官方内容，admin 修订留 operator_id 审计）；
     *       <b>publishAt 锁定</b>：已生效的发布时间改成未来时刻会让公告对用户瞬间
     *       消失，且定时发布本质是发布动作而非公告属性（要改请先下线再重新发布）；</li>
     *   <li>OFFLINE：禁改（需重新 publish 走新发布周期）。</li>
     * </ul>
     */
    @Transactional
    public AdminAnnouncementResponse update(Long id, UpdateAnnouncementRequest request, Long adminId) {
        Announcement a = findAny(id);
        if (a.getStatus() == AnnouncementStatus.OFFLINE) {
            throw new BusinessException(1001, "已下线公告不可编辑，如需变更请重新发布");
        }
        validateContent(request.content());
        rejectFlashCategory(request.category());
        // 触达等级与占位判据先解析（两者有顺序依赖：占位判据消费已解析的等级）
        AnnouncementTouchLevel touchLevel = resolveTouchLevel(request.touchLevel(), request.category());
        boolean pinned = resolvePinned(request.pinned(), touchLevel);
        if (a.getStatus() == AnnouncementStatus.PUBLISHED) {
            // 发布中：publishAt 既不校验也不落库（已生效时间，逻辑上不可改）
            validateSchedule(null, request.offlineAt());
            a.setTitle(request.title());
            a.setContent(request.content());
            a.setCategory(request.category());
            // 触达等级随分类一起可改：显式传入即采用，缺省按（可能已改的）分类重新派生
            a.setTouchLevel(touchLevel);
            a.setOfflineAt(request.offlineAt());
        } else {
            validateSchedule(request.publishAt(), request.offlineAt());
            applyFields(a, request.title(), request.content(), request.category(),
                    touchLevel, pinned, request.publishAt(), request.offlineAt());
        }
        // 媒体附件幂等替换（两个状态分支共用：DRAFT 与 PUBLISHED 均可改附件）
        applyMedia(a, request.media());
        a.setOperatorId(adminId);
        // 占位收敛走 HomeSlotService（PUBLISHED 分支此时a.pinned 仍是旧值，故先清一次
        // 再由 claim 置位；DRAFT 分支applyFields 已写入 pinned，claim 幂等）
        applyPinned(a, pinned);
        return toAdminResponse(announcementRepository.save(a));
    }

    /**
     * 占位收敛（<b>唯一写 pinned 的地方</b>，2026-10-08）：创建 / 更新两条路径都必须经过它，
     * 禁在别处直接 {@code setPinned(true)}。
     * <ul>
     *   <li>要置顶 → {@link HomeSlotService#claim}（校验 + 清场 + 置位）；</li>
     *   <li>不置顶且当前占位 → release（运营主动取消勾选必须真的释放位）。</li>
     * </ul>
     * <b>为什么取消勾选也要走这里</b>：取消置顶若只置本条为false 而不清场，
     * 位就"空"了但没有任何公告占位——首页位无人展示，而运营以为位还留着。
     */
    private void applyPinned(Announcement a, boolean pinned) {
        if (pinned) {
            homeSlotService.claim(a);
        } else if (a.isPinned()) {
            a.setPinned(false);
        }
    }

    /**
     * 发布：publishAt 缺省 = 立即（publish_at=now + 状态 PUBLISHED）；
     * 指定未来时刻 = 定时（写入 publish_at，状态保持 DRAFT，@Scheduled 到点强转）。
     * 仅 DRAFT 可发布；重新发布（OFFLINE → PUBLISHED）同语义。
     * <p>
     * 过期 offlineAt 处置（2026-09-02 失效机制闭环）：立即/定时发布时，上一发布周期
     * 遗留的 offlineAt 已过当前时间 → 清空（OFFLINE 态禁改编辑，重发布是唯一复活通道，
     * 不清空则 30s 内 offlineDue 会把刚复活的公告再度强转下线）。
     */
    @Transactional
    public AdminAnnouncementResponse publish(Long id, PublishAnnouncementRequest request, Long adminId) {
        Announcement a = findAny(id);
        LocalDateTime now = LocalDateTime.now();
        if (request != null && request.publishAt() != null) {
            if (!request.publishAt().isAfter(now)) {
                throw new BusinessException(1001, "定时发布时间必须晚于当前时间（立即发布请留空）");
            }
            if (a.getOfflineAt() != null && !a.getOfflineAt().isAfter(now)) {
                // 过期遗留 → 清空（与立即发布同语义）
                log.info("[announcement] publish clears stale offlineAt: id={} offlineAt={}", a.getId(), a.getOfflineAt());
                a.setOfflineAt(null);
            } else if (a.getOfflineAt() != null && !a.getOfflineAt().isAfter(request.publishAt())) {
                throw new BusinessException(1001, "自动下线时间必须晚于定时发布时间，请先在草稿中调整");
            }
            a.setPublishAt(request.publishAt());
            a.setStatus(AnnouncementStatus.DRAFT);
        } else {
            if (a.getOfflineAt() != null && !a.getOfflineAt().isAfter(now)) {
                log.info("[announcement] publish clears stale offlineAt: id={} offlineAt={}", a.getId(), a.getOfflineAt());
                a.setOfflineAt(null);
            }
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
        // 下线必须释放首页位（2026-10-08）：否则过期公告永久霸占首页位——这正是历史
        // 36 条幽灵置顶的形成机制之一（"永不下线 + 置顶"）。
        homeSlotService.release(a.getId());
        return toAdminResponse(announcementRepository.save(a));
    }

    /** 软删除（任意状态；已读回执保留——事实不删，膨胀归档预案） */
    @Transactional
    public void delete(Long id, Long adminId) {
        Announcement a = findAny(id);
        a.setDeleted(true);
        a.setOperatorId(adminId);
        // 软删同样释放位：V44 生成列条件含 deleted=0，但实体侧同步落false 才与 PC 一致
        homeSlotService.release(a.getId());
        announcementRepository.save(a);
    }

    /**
     * 阅读统计：阅读人数 + 阅读率。
     * <p>
     * <b>分母口径（2026-09-15 收敛）</b>= {@code UserStatsSql.USER_SCOPE}（平台真实
     * 用户——未软删、{@code role='USER'}、非 {@code test_} 开发号、非微信审核账号），
     * 即「公告的实际受众」。此前为「全部未软删非审核账号」（含 ADMIN 运营号与开发
     * 联调号），与本仓其它分母不是同一个盘子，已统一为单一事实源（docs/agents/35）。
     */
    @Transactional(readOnly = true)
    public AnnouncementStatsResponse stats(Long id) {
        findAny(id); // 存在性校验
        long readCount = readRepository.countByAnnouncementId(id);
        long totalUsers = userRepository.countRealUsers();
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
        // 触达等级走分类缺省映射（数据更新 = 流水，恒 SILENT）——不在此硬写枚举，
        // 保持"分类 → 缺省档位"只有一个声明处（AnnouncementCategory#defaultTouchLevel）
        a.setTouchLevel(category.defaultTouchLevel());
        a.setSource(AnnouncementSource.SYSTEM);
        a.setStatus(AnnouncementStatus.PUBLISHED);
        a.setPublishAt(LocalDateTime.now());
        a.setPublishedAt(a.getPublishAt());
        a.setOperatorId(null); // 系统来源恒null（Agent 来源审计先例）
        // 🚫 恒不占首页位（2026-10-08，位置口径与打扰口径同源）：每日舞讯是 SILENT 流水
        //   （用户不知道也不吃亏，见 resolvePinned 的判据），不配占据首页这个唯一强触达位。
        //   历史上这里曾长期被 Skill 传 pinned=true 而霸位—— 流水公告每天换一个，
        //   但"霸位"这件事本身让真正的运营公告永远挤不进首页。
        //   显式写 false 而非留默认：让"不占位"成为这条通道的声明式契约，
        //   未来有人想改必须先推翻这句话，而不是靠"这里没写所以是false"的隐式默认。
        a.setPinned(false);
        // 自动下线（2026-09-02 失效机制闭环）：舞讯公告时效 = 当日，按 ops-config
        // auto_offline_hours（默认 24h）到期后由 30s 调度强转 OFFLINE，不长期占据小程序顶部
        long autoOfflineHours = resolveDataUpdateAutoOfflineHours();
        if (autoOfflineHours > 0) {
            a.setOfflineAt(a.getPublishAt().plusHours(autoOfflineHours));
        }
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
     * <p>
     * <b>为什么可以放心批量转态</b>：首页公告位的占位与可见性<b>刻意解耦</b>
     * （草稿也能占位 = "预定"语义），因此本批量 UPDATE 不触碰 pinned ⇒
     * <b>永不与 V44 唯一索引冲突</b>。这是刻意的取舍：换来的是定时路径零风险。
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
        // 自愈兜底：至多一条占位。写入侧有校验 + 数据侧有唯一索引，但历史脏数据已存在
        // （本轮实测 36 条），且任何手工改库都可能绕过约束 ⇒ 30s 内自动收敛，
        // 胜出者 = id 最大者（与首页读端排序同源，保住的就是用户实际看到的那条）。
        homeSlotService.reconcile();
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private void applyFields(Announcement a, String title, String content, AnnouncementCategory category,
                             AnnouncementTouchLevel touchLevel, boolean pinned,
                             LocalDateTime publishAt, LocalDateTime offlineAt) {
        a.setTitle(title);
        a.setContent(content);
        a.setCategory(category);
        a.setTouchLevel(touchLevel);
        a.setPinned(pinned);
        a.setPublishAt(publishAt);
        a.setOfflineAt(offlineAt);
    }

    /**
     * 触达等级解析（<b>唯一缺省判据</b>，2026-09-15）：请求显式给出即采用，缺省按分类
     * 派生（{@link AnnouncementCategory#defaultTouchLevel()}）。设计成"可空 + 服务端派生"
     * 而非必填，是为了让不传该字段的自动化发布链路（Agent / Skill 直接调管理端接口）
     * 也能落在正确档位——每加一条发布通道就要记得补参数，漏一处就复发老问题。
     */
    private static AnnouncementTouchLevel resolveTouchLevel(AnnouncementTouchLevel requested,
                                                            AnnouncementCategory category) {
        return requested != null ? requested : category.defaultTouchLevel();
    }

    /**
     * 首页占位解析（<b>位置口径的唯一判据</b>，2026-10-08 根因修复）。
     *
     * <p><b>为什么要有这个方法（这是根因，不是新规则）</b><br>
     * 2026-09-15 立了判据「用户不知道会不会吃亏」（{@link AnnouncementTouchLevel}），
     * 但它<b>只被套用到未读口径</b>（{@code touchLevel}），没被套用到<b>位置口径</b>
     * （{@code pinned}）—— 同一个"要不要打扰用户"的决策被切成两个独立字段各自决策，
     * 位置口径还写死在每日舞讯 Skill 的固定模板里。结果：一条"用户不知道也不吃亏"的
     * 流水公告，每天都占着唯一强触达位。
     * <p>
     * 修法不是给某个分类打特例，而是让<b>两个口径由同一个判据派生</b>：
     * {@link AnnouncementTouchLevel#eligibleForHomeSlot()}——值得打扰用户的（ALERT）
     * 才配占据首页位。SILENT（每日舞讯 / 数据更新等流水，可查即可）不占位：它在公告中心
     * 照常出现、照常可搜索阅读，只是不抢强触达。
     *
     * <p><b>为什么显式拒绝而不是静默降级</b><br>
     * 静默把 {@code pinned=true} 悄悄改成 false，就是<b>原缺陷的另一种形态</b>
     * ——运营勾了置顶却什么都没发生，且无任何提示（本轮痛点即如此）。
     * 因此这里抛错而不是悄悄改值，由管理端把原因显示给运营。
     *
     * @param requested  请求的置顶意图（null / false = 不占位）
     * @param touchLevel 已解析的触达等级（<b>必须先经 {@link #resolveTouchLevel}</b>，
     *                   禁直接按分类判断——否则又回到两套口径）
     * @return 是否应当占据首页位
     * @throws BusinessException 请求置顶但内容为 SILENT 档（不值得打扰）
     */
    private static boolean resolvePinned(Boolean requested, AnnouncementTouchLevel touchLevel) {
        boolean wantPinned = requested != null && requested;
        if (!wantPinned) {
            return false;
        }
        if (!touchLevel.eligibleForHomeSlot()) {
            throw new BusinessException(1001,
                    "「不打扰」档的内容不占用首页公告位——它是流水记录，可查即可。"
                            + "若确实需要用户立刻知晓，请把「用户提醒」改为「提醒用户」后再置顶");
        }
        return true;
    }

    /**
     * 调度窗口校验（创建/更新共用）：offlineAt 设置时必须晚于当前时间（过去时点 =
     * 无意义的即时下线，且会被 30s 调度立即强转）；若 publishAt 参与校验（草稿态）
     * 则 offlineAt 还必须晚于发布时间（先发布后下线的时序约束，防"发布即下线"窗口）。
     *
     * @param publishAt 参与校验的计划发布时间；<b>null = 不校验该项</b>（发布中编辑时
     *                  传入——publishAt 已生效、不可改，不参与窗口校验）
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

    /**
     * 数据更新公告自动下线时长（小时）：ops-config {@code announcement.data_update.auto_offline_hours}，
     * 缺省 {@link #DEFAULT_DATA_UPDATE_AUTO_OFFLINE_HOURS}；≤0 = 不自动下线；非法值告警回落缺省
     * （配置错误不阻断公告生成）。
     */
    private long resolveDataUpdateAutoOfflineHours() {
        String raw = opsConfigService.getValue(OpsConfigService.KEY_ANNOUNCEMENT_DATA_UPDATE_AUTO_OFFLINE_HOURS)
                .orElse(null);
        if (raw == null || raw.isBlank()) {
            return DEFAULT_DATA_UPDATE_AUTO_OFFLINE_HOURS;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            log.warn("[announcement] invalid auto_offline_hours config '{}', fallback to {}h", raw,
                    DEFAULT_DATA_UPDATE_AUTO_OFFLINE_HOURS);
            return DEFAULT_DATA_UPDATE_AUTO_OFFLINE_HOURS;
        }
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

    /**
     * 公告域拒绝 FLASH（docs/agents/47 互斥契约）：快讯只能走 bulletin 域接口创建，
     * 公告管理端若放行 FLASH，会造出「无已读回执、却因公告列表放行而计入未读数」
     * 的脏条目（未读红点永不收敛）。分类选择在前端已过滤，此处为服务端兜底防御。
     */
    private void rejectFlashCategory(AnnouncementCategory category) {
        if (category == AnnouncementCategory.FLASH) {
            throw new BusinessException(400, "快讯请走「快讯管理」，公告分类仅支持运营公告与数据更新");
        }
    }

    /**
     * 媒体附件落库（2026-09-28，唯一入口）：结构 + 内容双重校验后序列化；
     * null / 空列表 → 置 {@code null}（幂等清空语义——编辑页始终提交当前完整
     * 附件列表，漏传字段不会静默保留旧值，杜绝"以为删了实际还在"）。
     * <p>
     * 校验单点 = {@link MediaAttachmentValidator}（结构层 + 内容层 URL 白名单/
     * 下载验图），跨仓约定"图片/视频 URL 落库字段必校验"由此落实。
     */
    private void applyMedia(Announcement a, List<MediaAttachment> media) {
        mediaAttachmentValidator.validate(media);
        a.setMediaJson(MediaAttachments.serialize(media));
    }

    private AdminAnnouncementResponse toAdminResponse(Announcement a) {
        return new AdminAnnouncementResponse(
                a.getId(), a.getTitle(), a.getContent(), a.getCategory(), a.getTouchLevel(),
                a.getSource(), a.getScope(),
                a.getStatus(), a.isPinned(), MediaAttachments.parseOrEmpty(a.getMediaJson()),
                a.getPublishAt(), a.getOfflineAt(),
                a.getPublishedAt(), a.getOfflinedAt(), a.getOperatorId(),
                a.getCreatedAt(), a.getUpdatedAt());
    }
}
