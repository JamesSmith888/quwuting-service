package org.quwuting.quwutingservice.venuestatusreport.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AdminStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AnnouncementSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.MyStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.StatusReportListItem;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 场所突发事件（紧急公告）实时信号服务。
 * <p>
 * 用户在详情页上报"突发事件"（8 类枚举，2026-08-11 由"仅暂停营业"泛化），系统作为
 * 独立信号层展示（不直接修改 Venue.status），通过按类型 TTL 过期（expires_at 列）+
 * {@link VenueHeatService} 的 StatusConfidence override 形成闭环。
 * <p>
 * 分层模型（先发后审）：上报立即进入活跃信号（低置信，TTL 窗口内公开可见）；管理员
 * <b>采纳</b>（状态类联动门店状态 + 奖励 + 站内信）或<b>移除</b>（清理虚假信号）。
 * 采纳后信号保留展示至 TTL 过期并带"已核实"标记（AdminAction.ADOPTED），移除后
 * 即时消失（AdminAction.REMOVED）。
 * <p>
 * 写操作即时失效热度缓存（{@link VenueHeatService#invalidate}），确保其他用户很快
 * 看到最新活跃报告数——与 TagInteractionService / FavoriteService 的显式失效模式一致。
 * <p>
 * Upsert 恢复模式：撤销（soft delete）后再次上报时，恢复已软删的记录（设 deleted=false、
 * 重置 adminAction=null、刷新 createdAt + expiresAt 续期 TTL），而非 INSERT 新行。
 * UNIQUE(userId, venueId) 约束使软删记录仍占用唯一槽位，INSERT 会冲突。此模式与
 * {@link org.quwuting.quwutingservice.favorite.service.FavoriteService#addFavorite} 一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusReportService {

    /** 全局频率限制：每用户每小时最多报告的不同场所数（滑动窗口） */
    private static final int MAX_REPORTS_PER_HOUR = 5;

    /** 每日上报上限：每用户每日报告总次数（2026-08-11 新增，与滑动窗口互补兜底批量刷） */
    private static final int MAX_REPORTS_PER_DAY = 10;

    /** 门店突发事件历史明细列表最大返回条数（全量历史倒序取最近 N 条，防无限增长；公告页消费） */
    private static final int RECENT_REPORT_LIST_LIMIT = 20;

    private final StatusReportRepository statusReportRepository;
    private final VenueRepository venueRepository;
    private final VenueHeatService venueHeatService;
    /** 采纳联动：门店状态变更 + 状态变迁日志 + 场所/热门缓存逐出（markSuspendedByReport / reopenByReport） */
    private final VenueService venueService;
    private final PointsService pointsService;
    private final MessageService messageService;

    /**
     * 提交或更新突发事件报告（upsert 语义）。
     * 同一用户对同一场所只保留一条物理记录，再次报告覆盖更新（含换类型）。
     * 撤销后再次上报 = 恢复软删记录（不 INSERT，避免 UNIQUE 约束冲突）。
     * <p>
     * 2026-08-11 泛化守卫（状态类 vs 事件类，与前端报告操作状态机同源语义）：
     * <ul>
     *   <li>SUSPENDED（暂停营业）：仅对声称营业（OPEN）门店有决策意义——存储态声称
     *       非营业（RENOVATING/CLOSED/SUSPENDED/CEASED）时拒绝（业务错误 1010，
     *       前端 chip 已翻转为「报告恢复营业」，本守卫为 API 层系统性闭合防绕过）；</li>
     *   <li>RESUMED（恢复营业）：与 SUSPENDED 对称——仅对声称非营业门店有意义，
     *       OPEN 门店报告恢复营业自相矛盾（业务错误 1012）；</li>
     *   <li>事件类（突然检查/舞池不开/突然清场/突然关门/禁龙/情况不明）：不受存储态
     *       约束（非营业门店同样可能突发检查/清场）。</li>
     * </ul>
     * SITUATION_UNCLEAR（情况不明）信息量最低、噪音高危：提交必须携带补充说明
     * （业务错误 1011）。限频检查置于守卫之后——无效请求快速失败，不消耗限频额度。
     */
    @Transactional
    public ActiveReportSummary submitReport(Long venueId, SubmitReportRequest req) {
        Long userId = UserContext.requireAuth();

        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));

        // 类型缺省兜底 SUSPENDED（兼容极速上报空 body）
        ReportType type = req.type() != null ? req.type() : ReportType.SUSPENDED;
        // 情况不明：必填补充说明（防刷价值导向：低信息量类型以说明约束噪音）
        if (type == ReportType.SITUATION_UNCLEAR
                && (req.note() == null || req.note().isBlank())) {
            throw new BusinessException(1011, "「情况不明」需要补充说明（不超过 500 字）");
        }
        // 状态类守卫（置于限频检查之前——无效请求快速失败，不消耗用户限频额度）
        if (type == ReportType.SUSPENDED && venue.getStatus() != VenueStatus.OPEN) {
            throw new BusinessException(1010,
                    "该门店当前为" + venue.getStatus().getDisplayName() + "，无需报告暂停营业");
        }
        if (type == ReportType.RESUMED && venue.getStatus() == VenueStatus.OPEN) {
            throw new BusinessException(1012, "该门店当前营业中，无需报告恢复营业");
        }

        // 查找任何状态的已有记录（含软删），用于判断是更新、恢复还是新建
        var existing = statusReportRepository.findByUserIdAndVenueId(userId, venueId);

        if (existing.isPresent()) {
            VenueStatusReport report = existing.get();
            boolean wasDeleted = report.isDeleted();
            // 恢复软删记录 = 新报告行为，需检查频率限制（更新活跃记录不限频）
            if (wasDeleted) {
                checkRateLimit(userId);
            }
            report.setType(type);
            report.setOccurredAt(req.occurredAt());
            report.setNote(TextSanitizer.sanitize(req.note()));
            report.setDeleted(false);
            // 用户重新上报（含此前被采纳/移除的软删记录）：重置处置标记为活跃
            report.setAdminAction(null);
            statusReportRepository.save(report);
            // 续期 TTL：@CreationTimestamp 不可变属性，实体 setter 被静默忽略（HHH000502）——
            // 必须经 JPQL 批量更新直写 created_at + expires_at（见 renewReport 根因注记）
            LocalDateTime now = LocalDateTime.now();
            statusReportRepository.renewReport(report.getId(), now,
                    now.plusHours(type.getTtlHours()));
        } else {
            // 首次上报，检查频率限制
            checkRateLimit(userId);
            LocalDateTime now = LocalDateTime.now();
            // 并发首报竞态收口：UNIQUE(user_id, venue_id) 索引 + 原子 upsert
            // （2026-08-20 根因修复：替代「save + catch 23505 + 同事务继续查询」——
            // PG 语句失败后事务中止（25P02），catch 后 getActiveReportSummary 必然
            // HTTP 500；ON CONFLICT DO NOTHING 恒 1 次往返零异常，冲突 = 另一请求
            // 已插入，幂等忽略本请求数据，与旧 catch 语义一致）
            statusReportRepository.upsertReport(venueId, userId, type.name(), req.occurredAt(),
                    TextSanitizer.sanitize(req.note()), now.plusHours(type.getTtlHours()), now);
        }

        // 活跃报告数是热度接口的输出之一：显式失效热度缓存（写路径逐出，refresh 周期仅兜底）
        venueHeatService.invalidate(venueId);
        return getActiveReportSummary(venueId);
    }

    /**
     * 撤销当前用户对某场所的报告（soft delete）。
     * 撤销不写 adminAction（撤销是用户主动收回，与管理端处置语义独立）。
     */
    @Transactional
    public ActiveReportSummary cancelReport(Long venueId) {
        Long userId = UserContext.requireAuth();

        statusReportRepository.findByUserIdAndVenueIdAndDeletedFalse(userId, venueId)
                .ifPresent(report -> {
                    report.setDeleted(true);
                    statusReportRepository.save(report);
                });

        venueHeatService.invalidate(venueId);
        return getActiveReportSummary(venueId);
    }

    /**
     * 获取某场所的活跃报告摘要（状态上报接口的响应数据）。
     * <p>
     * 活跃 = 未删除且 expiresAt > now（TTL 唯一事实源 = expires_at 列）。
     * 热度接口不再经由此方法——{@link org.quwuting.quwutingservice.venue.service.VenueHeatService#getHeat}
     * 的 mega-query 已内联活跃上报计数；本方法仅供 submitReport / cancelReport 组装响应使用。
     */
    @Transactional(readOnly = true)
    public ActiveReportSummary getActiveReportSummary(Long venueId) {
        StatusReportRepository.ActiveReportStats stats =
                statusReportRepository.countActiveAndLatestTime(venueId, LocalDateTime.now());
        int count = stats.getActiveCount() != null ? stats.getActiveCount().intValue() : 0;
        return new ActiveReportSummary(count, stats.getLatestTime());
    }

    /**
     * 某门店突发事件历史明细列表（公开读，无需登录）。
     * <p>
     * 公告页「最近的突发事件」数据源：该门店<b>全部未撤销（deleted=false）报告</b>
     * 按时间倒序，最多 {@value #RECENT_REPORT_LIST_LIMIT} 条——<b>无时间窗口</b>，
     * 含已过期（TTL 外）与超出任何展示窗口的历史记录，由 {@code expired} 标注
     * （2026-08-12 起：TTL 过期只代表信号失效，不代表报告事实消失；2026-08-20 起
     * 移除展示窗口：历史视图只裁剪「非事实」（撤销/处置），时间维度逐行标注——
     * 旧实现先硬套活跃判定 {@code expires_at > now}、后设 created_at 展示窗口，均
     * 让用户回看社区历史时只见空列表，无法区分「从未有人报」与「报过但已过期」）。
     * 活跃/过期判定由本方法按 {@code expires_at} 列与 now 比较（TTL 唯一事实源 =
     * 列，SQL 不自行定义时间窗）。报告者昵称脱敏（首字 + "**"，无昵称回退「舞友」）
     * ——保护用户身份隐私的同时保留"社区已有多人报告"的信任信号。
     * <p>
     * {@code mine} 标记当前登录用户的报告（未登录时 UserContext.getCurrentUserId() 为
     * null，恒 false），供前端高亮"我"的上报行。
     */
    @Transactional(readOnly = true)
    public List<StatusReportListItem> listRecentReports(Long venueId) {
        Long currentUserId = UserContext.getCurrentUserId();
        LocalDateTime now = LocalDateTime.now();
        return statusReportRepository.findRecentByVenue(venueId).stream()
                .limit(RECENT_REPORT_LIST_LIMIT)
                .map(row -> {
                    ReportType type = ReportType.valueOf(row.getType());
                    // 活跃判定全局口径：expires_at > now 为活跃，否则视为已过期（含边界相等）
                    boolean expired = !row.getExpiresat().isAfter(now);
                    return new StatusReportListItem(
                            row.getId(),
                            maskNickname(row.getNickname()),
                            type,
                            type.getDisplayName(),
                            type.getSeverity().getCode(),
                            row.getCreatedat(),
                            expired,
                            currentUserId != null && currentUserId.equals(row.getUserid()));
                })
                .toList();
    }

    /**
     * 门店紧急公告区聚合（公开读，无需登录，2026-08-11 新增，2026-08-20 分窗参数化）。
     * <p>
     * 公告区展示 = 活跃信号 + 已采纳信号（带"已核实"标记）按类型聚簇；移除信号不展示。
     * 返回按严重级降序（HIGH→MEDIUM→LOW→RECOVERY，恢复营业语义上最后呈现），
     * 每类型一条摘要。不返回 note（审核安全约定"note 仅管理端可见"）。
     * <p>
     * 时间窗口由 {@code includeExpired} 参数化（双消费方分窗，根因见
     * AGENTS.md「紧急公告区」）：
     * <ul>
     *   <li>false（默认）= 活跃视图：仅 TTL 窗口内信号——详情页单行公告条消费
     *       （"当前紧急信号"语义，过时信号不得误导为当前紧急）；</li>
     *   <li>true = 历史视图：全部未撤销 + 已采纳记录（含已过期）——公告专属页
     *       「紧急公告」列表消费（"历史事实摘要"语义，时效由 latestAt 相对时间传达）。</li>
     * </ul>
     */
    @Transactional(readOnly = true)
    public List<AnnouncementSummary> listAnnouncements(Long venueId, boolean includeExpired) {
        return statusReportRepository.findAnnouncementsByVenue(venueId, LocalDateTime.now(), includeExpired).stream()
                .map(row -> {
                    ReportType type = ReportType.valueOf(row.getType());
                    int count = row.getCnt() != null ? row.getCnt().intValue() : 0;
                    boolean adopted = row.getAdoptedcnt() != null && row.getAdoptedcnt() > 0;
                    return new AnnouncementSummary(type, type.getDisplayName(),
                            type.getSeverity().getCode(), count, adopted, row.getLatestat());
                })
                .sorted((a, b) -> {
                    // 严重级降序：HIGH(0) → MEDIUM(1) → LOW(2) → RECOVERY(3)
                    return Integer.compare(severityOrder(a.severity()), severityOrder(b.severity()));
                })
                .toList();
    }

    private int severityOrder(String severity) {
        return switch (severity) {
            case "high" -> 0;
            case "medium" -> 1;
            case "low" -> 2;
            default -> 3;
        };
    }

    /** 昵称脱敏：首字 + "**"；空昵称回退「舞友」（列表公开展示用，保护用户身份隐私） */
    private String maskNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "舞友";
        }
        return nickname.charAt(0) + "**";
    }

    /** 频率限制：滑动窗口内不同场所数 + 每日总次数（批量刷同批门店由每日上限兜底） */
    private void checkRateLimit(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long hourly = statusReportRepository.countDistinctVenuesByUserIdSince(userId, since);
        if (hourly >= MAX_REPORTS_PER_HOUR) {
            throw new BusinessException(1006, "操作过于频繁，请稍后再试");
        }
        LocalDateTime dayStart = LocalDate.now().atStartOfDay();
        long daily = statusReportRepository.countReportsByUserSince(userId, dayStart);
        if (daily >= MAX_REPORTS_PER_DAY) {
            throw new BusinessException(1006, "今日上报次数已达上限，请明天再来");
        }
    }

    /**
     * 当前用户的全部上报记录（「我的上报记录」数据源）。
     * <p>
     * 范围：仅未撤销（deleted=false）的记录，含已过期（TTL 外）——「已过期」记录
     * 前端标注后提醒用户可重新上报。已撤销记录不返回：撤销是用户主动收回动作，
     * soft delete 属内部实现细节，语义上不再属于"上报记录"。
     * <p>
     * venueId 可选（2026-08-06）：null = 跨场所全部（个人中心）；非 null = 单门店。
     * <p>
     * {@code active} / {@code expiresAt} 直接取 {@code expires_at} 列（TTL 唯一事实源 =
     * 列，2026-08-11 由常量计算迁移）——SQL 层不自行定义时间窗。
     */
    @Transactional(readOnly = true)
    public List<MyStatusReportResponse> listMyReports(Long venueId) {
        Long userId = UserContext.requireAuth();
        LocalDateTime now = LocalDateTime.now();
        return statusReportRepository.findMyReportsByUserId(userId, venueId).stream()
                .map(row -> {
                    ReportType type = ReportType.valueOf(row.getType());
                    return new MyStatusReportResponse(
                            row.getId(),
                            row.getVenueid(),
                            row.getVenuename(),
                            row.getVenuecity(),
                            row.getVenuedistrict(),
                            row.getVenueaddress(),
                            type,
                            type.getDisplayName(),
                            row.getCreatedat(),
                            !row.getExpiresat().isBefore(now),
                            row.getExpiresat());
                })
                .toList();
    }

    // ─── 管理端（需 ADMIN，2026-08-10 新增，2026-08-11 泛化） ───

    /**
     * 管理端活跃突发事件列表（需 ADMIN，跨场所分页倒序，可按类型筛选）。
     * <p>
     * 管理端「上报管理 → 突发事件」tab 数据源：TTL 窗口内全部活跃报告，按时间倒序。
     * 管理端上下文返回上报者真实昵称 + userId + note（note 仅管理端可见的审核安全约定），
     * 不做公开列表的昵称脱敏。{@code peerCount} = 同店同类型活跃信号数（众报聚簇，
     * 管理员处置一条时看到"已有多人报同一事件"）。仅活跃报告需要管理处置（移除虚假
     * 信号 / 采纳属实信号）——TTL 过期后信号已自动从公开视图消失，无需管理动作。
     */
    @Transactional(readOnly = true)
    public Page<AdminStatusReportResponse> listAdminReports(int page, int size, String typeFilter) {
        UserContext.requireAdmin();
        LocalDateTime now = LocalDateTime.now();
        // 同店同类型聚簇计数（单次往返），主列表逐条回填
        Map<String, Long> clusterMap = new HashMap<>();
        for (StatusReportRepository.TypeClusterRow row :
                statusReportRepository.countClustersByVenueAndType(now)) {
            clusterMap.put(row.getVenueid() + ":" + row.getType(), row.getCnt());
        }
        return statusReportRepository.findActiveReports(now, typeFilter,
                        PageRequest.of(page, Math.min(Math.max(size, 1), 100)))
                .map(row -> {
                    ReportType type = ReportType.valueOf(row.getType());
                    long peerCount = clusterMap.getOrDefault(
                            row.getVenueid() + ":" + row.getType(), 0L);
                    return new AdminStatusReportResponse(
                            row.getId(),
                            row.getVenueid(),
                            row.getVenuename(),
                            row.getUserid(),
                            row.getNickname() != null && !row.getNickname().isBlank()
                                    ? row.getNickname() : "舞友",
                            type,
                            type.getDisplayName(),
                            type.getSeverity().getCode(),
                            row.getNote(),
                            row.getOccurredat(),
                            row.getCreatedat(),
                            peerCount);
                });
    }

    /**
     * 管理端活跃突发事件计数（需 ADMIN，跨场所全量）。
     * <p>
     * FAB「上报管理」红点聚合数据源之一（2026-08-10）：与 venuefeedback PENDING 计数
     * 合并为「管理端上报待办总数」——管理员对"有新活跃突发信号"有巡查可见性，
     * 处置（采纳/移除）或 TTL 过期后计数自然归零，无独立已读语义。
     */
    @Transactional(readOnly = true)
    public long countActiveReports() {
        UserContext.requireAdmin();
        return statusReportRepository.countActiveReports(LocalDateTime.now());
    }

    /**
     * 管理端移除突发事件报告（需 ADMIN，幂等）。
     * <p>
     * 移除 = 平台清理虚假/失效信号：soft delete + adminAction=REMOVED 后所有"活跃"
     * 查询（热度计数/公开列表/管理端列表/公告区聚合）立即过滤掉该报告——公开视图
     * 即时消失，无需等 TTL 过期。移除后失效 venueHeat 缓存（活跃报告数是热度输出之一）。
     * <p>
     * 与用户自撤（{@link #cancelReport}）的差异：操作者是管理员而非上报者本人；
     * 与采纳（{@link #adoptReport}）的差异：移除 = 虚假/失效信号清理（无副作用），
     * 采纳 = 信号属实 → 联动状态 + 奖励 + 通知（同事务）。
     */
    @Transactional
    public void removeReport(Long id) {
        UserContext.requireAdmin();
        VenueStatusReport report = statusReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "上报不存在"));
        if (report.isDeleted()) {
            return; // 幂等：已处置直接返回（不重复失效缓存）
        }
        report.setDeleted(true);
        report.setAdminAction(AdminAction.REMOVED);
        statusReportRepository.save(report);
        venueHeatService.invalidate(report.getVenueId());
    }

    /**
     * 管理端采纳突发事件报告（需 ADMIN，幂等，2026-08-10 新增，2026-08-11 泛化）。
     * <p>
     * 采纳 = 管理员核实事件属实（区别于移除：移除 = 清理虚假/失效信号，无副作用）。
     * 采纳在<b>同一事务</b>内完成四件事（任一失败整体回滚，杜绝"状态已改但积分未发"等
     * 半完成状态）：
     * <ol>
     *   <li><b>状态类联动门店营业状态</b>——SUSPENDED 经
     *       {@link VenueService#markSuspendedByReport}（写状态变迁日志 + 场所/热门缓存
     *       逐出）；RESUMED 经对称的 {@link VenueService#reopenByReport}（营业中）；
     *       事件类不改状态；</li>
     *   <li><b>报告处置</b>（deleted=true + adminAction=ADOPTED）——不再作为活跃信号，
     *       但公告区保留展示至 TTL 过期并带"已核实"标记（与移除 REMOVED 的
     *       即时消失语义区分，见 {@link #removeReport}）；</li>
     *   <li><b>积分奖励</b>——上报者（userId 非空）经
     *       {@link PointsService#rewardStatusReport} 发放，流水幂等键
     *       (user, STATUS_REPORT_REWARD, reportId) 兜底并发；<b>SITUATION_UNCLEAR
     *       不设奖励</b>（信息量最低、噪音高危，防价值错配，2026-08-11 约定）；</li>
     *   <li><b>处理结果站内信</b>——经 {@link #notifyAdopted} 通知上报者（匿名不通知，
     *       与积分同一匿名边界，见 {@link VenueFeedbackService}「处理结果站内信」约定）。</li>
     * </ol>
     * 已处置（软删）或不存在幂等返回：不重复改状态/发分/发信。
     */
    @Transactional
    public void adoptReport(Long id) {
        Long adminId = UserContext.requireAdmin();
        VenueStatusReport report = statusReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "上报不存在"));
        if (report.isDeleted()) {
            return; // 幂等：已处置（采纳/移除）直接返回
        }
        ReportType type = report.getType();
        // 1. 状态类联动门店营业状态（状态变迁日志 + 场所/热门缓存逐出，同事务）
        if (type == ReportType.SUSPENDED) {
            venueService.markSuspendedByReport(report.getVenueId(), adminId);
        } else if (type == ReportType.RESUMED) {
            venueService.reopenByReport(report.getVenueId(), adminId);
        }
        // 2. 报告处置：软删 + 采纳标记（公告区保留展示至 TTL 过期，带"已核实"标记）
        report.setDeleted(true);
        report.setAdminAction(AdminAction.ADOPTED);
        statusReportRepository.save(report);
        // 3. 积分奖励（同事务；匿名不发、流水幂等键兜底并发；情况不明不设奖励）
        if (report.getUserId() != null && type != ReportType.SITUATION_UNCLEAR) {
            pointsService.rewardStatusReport(report.getUserId(), report.getId());
        }
        // 4. 处理结果站内信（同事务；匿名不通知）
        notifyAdopted(report);
        // 5. 热度缓存失效（活跃报告数是热度输出之一；状态类联动早退不失效时此处兜底，
        //    与提交/撤销/移除同模式）
        venueHeatService.invalidate(report.getVenueId());
    }

    /**
     * 采纳结果站内信：采纳流转实际发生时向上报者发送 STATUS_REPORT_RESULT，与采纳
     * 同事务（通知不丢失）。仅陈述事实：场所名 + 类型 + 采纳结论（状态类附门店状态
     * 变更结果）——奖励数额不在消息内硬编码（以积分流水为唯一事实源，同
     * {@link VenueFeedbackService#notifyHandled} 约定）；软关联 VENUE 深链。
     * 匿名上报（userId null）无法归属，不通知（与积分奖励同一匿名边界）。
     */
    private void notifyAdopted(VenueStatusReport report) {
        if (report.getUserId() == null) {
            return;
        }
        String venueName = venueRepository.findByIdAndDeletedFalse(report.getVenueId())
                .map(Venue::getName)
                .orElse("已下架场所");
        ReportType type = report.getType();
        String statusClause = switch (type) {
            case SUSPENDED -> "，该门店已标记为暂停营业";
            case RESUMED -> "，该门店已标记为营业中";
            default -> "";
        };
        messageService.create(report.getUserId(), MessageType.STATUS_REPORT_RESULT,
                "突发事件已采纳",
                "「" + venueName + "」的" + type.getDisplayName() + "报告已被采纳"
                        + statusClause + "，奖励积分已发放。",
                "VENUE", report.getVenueId());
    }
}
