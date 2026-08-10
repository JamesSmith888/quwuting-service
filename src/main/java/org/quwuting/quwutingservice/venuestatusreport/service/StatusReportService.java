package org.quwuting.quwutingservice.venuestatusreport.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuefeedback.service.VenueFeedbackService;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.AdminStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.MyStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.StatusReportListItem;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 场所实时状态报告服务。
 * <p>
 * 用户在详情页极速上报"暂停营业"，系统作为独立信号层展示（不修改 Venue.status），
 * 通过 TTL 过期 + {@link VenueHeatService} 的 StatusConfidence override 形成闭环。
 * <p>
 * 写操作即时失效热度缓存（{@link VenueHeatService#invalidate}），
 * 确保其他用户很快看到最新活跃报告数——与 TagInteractionService / FavoriteService 的
 * 显式失效模式一致（热度缓存为服务内嵌 LoadingCache，不走 Spring @CacheEvict）。
 * <p>
 * Upsert 恢复模式：撤销（soft delete）后再次上报时，恢复已软删的记录（设 deleted=false、
 * 刷新 createdAt 续期 TTL），而非 INSERT 新行。UNIQUE(userId, venueId) 约束使软删记录仍
 * 占用唯一槽位，INSERT 会冲突。此模式与 {@link org.quwuting.quwutingservice.favorite.service.FavoriteService#addFavorite} 一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatusReportService {

    /** 活跃报告 TTL：超过此时间的报告不再计入活跃数（物理保留，仅聚合过滤） */
    public static final int ACTIVE_REPORT_TTL_HOURS = 4;

    /** 全局频率限制：每用户每小时最多报告的不同场所数（滑动窗口） */
    private static final int MAX_REPORTS_PER_HOUR = 5;

    /** 门店暂停报列表最大返回条数（TTL 窗口内倒序取最近 N 条，详情页弹层消费） */
    private static final int RECENT_REPORT_LIST_LIMIT = 20;

    private final StatusReportRepository statusReportRepository;
    private final VenueRepository venueRepository;
    private final VenueHeatService venueHeatService;
    /** 采纳联动：门店状态变更 + 状态变迁日志 + 场所/热门缓存逐出（见 {@link VenueService#markSuspendedByReport}） */
    private final VenueService venueService;
    private final PointsService pointsService;
    private final MessageService messageService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 提交或更新状态报告（upsert 语义）。
     * 同一用户对同一场所只保留一条物理记录，再次报告覆盖更新。
     * 撤销后再次上报 = 恢复软删记录（不 INSERT，避免 UNIQUE 约束冲突）。
     */
    @Transactional
    public ActiveReportSummary submitReport(Long venueId, SubmitReportRequest req) {
        Long userId = UserContext.requireAuth();

        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
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
            if (req.reason() != null) report.setReason(req.reason());
            else if (wasDeleted) report.setReason(ReportReason.UNKNOWN);
            report.setOccurredAt(req.occurredAt());
            report.setNote(TextSanitizer.sanitize(req.note()));
            report.setDeleted(false);
            statusReportRepository.save(report);
            // 续期 TTL：@CreationTimestamp 属性不可变，实体 setter 被静默忽略（HHH000502，
            // UPDATE 不含 created_at 列）——必须经 JPQL 批量更新直写 created_at
            // （见 StatusReportRepository.renewCreatedAt 根因注记）。
            statusReportRepository.renewCreatedAt(report.getId(), LocalDateTime.now());
        } else {
            // 首次上报，检查频率限制
            checkRateLimit(userId);
            VenueStatusReport report = new VenueStatusReport();
            report.setVenueId(venueId);
            report.setUserId(userId);
            report.setReason(req.reason() != null ? req.reason() : ReportReason.UNKNOWN);
            report.setOccurredAt(req.occurredAt());
            report.setNote(TextSanitizer.sanitize(req.note()));
            try {
                statusReportRepository.save(report);
            } catch (DataIntegrityViolationException e) {
                // 并发竞态：另一请求已创建同一 (userId, venueId) 记录
                // 必须清除 session 中的脏实体（null id），否则后续查询的 auto-flush 会抛 AssertionFailure
                log.debug("submitReport 并发冲突，幂等忽略: userId={}, venueId={}", userId, venueId);
                entityManager.clear();
            }
        }

        // 活跃报告数是热度接口的输出之一：显式失效热度缓存（写路径逐出，refresh 周期仅兜底）
        venueHeatService.invalidate(venueId);
        return getActiveReportSummary(venueId);
    }

    /**
     * 撤销当前用户对某场所的状态报告（soft delete）。
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
     * 活跃 = 未删除且 createdAt >= now - TTL。
     * 热度接口不再经由此方法——{@link org.quwuting.quwutingservice.venue.service.VenueHeatService#getHeat}
     * 的 mega-query 已内联活跃上报计数；本方法仅供 submitReport / cancelReport 组装响应使用。
     */
    @Transactional(readOnly = true)
    public ActiveReportSummary getActiveReportSummary(Long venueId) {
        LocalDateTime since = LocalDateTime.now().minusHours(ACTIVE_REPORT_TTL_HOURS);
        StatusReportRepository.ActiveReportStats stats =
                statusReportRepository.countActiveAndLatestTime(venueId, since);
        int count = stats.getActiveCount() != null ? stats.getActiveCount().intValue() : 0;
        return new ActiveReportSummary(count, stats.getLatestTime());
    }

    /**
     * 某门店最近暂停报列表（公开读，无需登录）。
     * <p>
     * 详情页「报告暂停营业」弹层的默认内容：TTL 窗口内全部用户的暂停报，按时间倒序，
     * 最多 {@value #RECENT_REPORT_LIST_LIMIT} 条。报告者昵称脱敏（首字 + "**"，无昵称
     * 回退「舞友」）——保护用户身份隐私的同时保留"社区已有多人报告"的信任信号。
     * <p>
     * {@code mine} 标记当前登录用户的报告（未登录时 UserContext.getCurrentUserId() 为
     * null，恒 false），供前端高亮"我"的上报行。
     */
    @Transactional(readOnly = true)
    public List<StatusReportListItem> listRecentReports(Long venueId) {
        Long currentUserId = UserContext.getCurrentUserId();
        LocalDateTime since = LocalDateTime.now().minusHours(ACTIVE_REPORT_TTL_HOURS);
        return statusReportRepository.findRecentByVenue(venueId, since).stream()
                .limit(RECENT_REPORT_LIST_LIMIT)
                .map(row -> {
                    ReportReason reason = ReportReason.valueOf(row.getReason());
                    return new StatusReportListItem(
                            row.getId(),
                            maskNickname(row.getNickname()),
                            reason,
                            reason.getDisplayName(),
                            row.getCreatedat(),
                            currentUserId != null && currentUserId.equals(row.getUserid()));
                })
                .toList();
    }

    /** 昵称脱敏：首字 + "**"；空昵称回退「舞友」（列表公开展示用，保护用户身份隐私） */
    private String maskNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "舞友";
        }
        return nickname.charAt(0) + "**";
    }

    /** 全局频率限制：滑动窗口内不同场所数 */
    private void checkRateLimit(Long userId) {
        LocalDateTime since = LocalDateTime.now().minusHours(1);
        long count = statusReportRepository.countDistinctVenuesByUserIdSince(userId, since);
        if (count >= MAX_REPORTS_PER_HOUR) {
            throw new BusinessException(1006, "操作过于频繁，请稍后再试");
        }
    }

    /**
     * 当前用户的全部状态上报记录（「我的上报记录」数据源）。
     * <p>
     * 范围：仅未撤销（deleted=false）的记录，含已过期（TTL 外）——「已过期」记录
     * 前端标注后提醒用户可重新上报。已撤销记录不返回：撤销是用户主动收回动作，
     * soft delete 属内部实现细节，语义上不再属于"上报记录"。
     * <p>
     * venueId 可选（2026-08-06）：null = 跨场所全部（个人中心）；非 null = 单门店
     * （详情页「我的上报记录」弹窗——只展示当前门店记录，全部记录入口在个人中心）。
     * <p>
     * {@code active} / {@code expiresAt} 在本方法按 {@link #ACTIVE_REPORT_TTL_HOURS} 统一计算
     * （TTL 唯一事实源）——SQL 层不自行定义时间窗，与 findDetailStats / countHeatCounters
     * 的「活跃判定必须经参数传入同一 TTL 窗口」契约一致（见 VenuePostRepository 修复注记）。
     */
    @Transactional(readOnly = true)
    public List<MyStatusReportResponse> listMyReports(Long venueId) {
        Long userId = UserContext.requireAuth();
        LocalDateTime since = LocalDateTime.now().minusHours(ACTIVE_REPORT_TTL_HOURS);
        return statusReportRepository.findMyReportsByUserId(userId, venueId).stream()
                .map(row -> new MyStatusReportResponse(
                        row.getId(),
                        row.getVenueid(),
                        row.getVenuename(),
                        row.getVenuecity(),
                        row.getVenuedistrict(),
                        row.getVenueaddress(),
                        row.getCreatedat(),
                        !row.getCreatedat().isBefore(since),
                        row.getCreatedat().plusHours(ACTIVE_REPORT_TTL_HOURS)))
                .toList();
    }

    // ─── 管理端（需 ADMIN，2026-08-10 新增，落实 AGENTS.md「场所状态上报」管理端可见性约定） ───

    /**
     * 管理端活跃暂停报列表（需 ADMIN，跨场所分页倒序）。
     * <p>
     * 管理端「上报管理 → 暂停营业」tab 数据源：TTL 窗口内全部活跃报告，按时间倒序。
     * 管理端上下文返回上报者真实昵称 + userId + note（note 仅管理端可见的审核安全约定），
     * 不做公开列表的昵称脱敏。仅活跃报告需要管理处置（移除虚假信号）——TTL 过期后
     * 信号已自动从公开视图消失，无需管理动作。
     */
    @Transactional(readOnly = true)
    public Page<AdminStatusReportResponse> listAdminReports(int page, int size) {
        UserContext.requireAdmin();
        LocalDateTime since = LocalDateTime.now().minusHours(ACTIVE_REPORT_TTL_HOURS);
        return statusReportRepository.findActiveReports(since,
                        PageRequest.of(page, Math.min(Math.max(size, 1), 100)))
                .map(row -> new AdminStatusReportResponse(
                        row.getId(),
                        row.getVenueid(),
                        row.getVenuename(),
                        row.getUserid(),
                        row.getNickname() != null && !row.getNickname().isBlank()
                                ? row.getNickname() : "舞友",
                        ReportReason.valueOf(row.getReason()),
                        ReportReason.valueOf(row.getReason()).getDisplayName(),
                        row.getNote(),
                        row.getOccurredat(),
                        row.getCreatedat()));
    }

    /**
     * 管理端活跃暂停报计数（需 ADMIN，跨场所全量）。
     * <p>
     * FAB「上报管理」红点聚合数据源之一（2026-08-10）：与 venuefeedback PENDING 计数
     * 合并为「管理端上报待办总数」——管理员对"有新活跃暂停信号"有巡查可见性，
     * 处置（移除）或 TTL 过期后计数自然归零，无独立已读语义。
     */
    @Transactional(readOnly = true)
    public long countActiveReports() {
        UserContext.requireAdmin();
        LocalDateTime since = LocalDateTime.now().minusHours(ACTIVE_REPORT_TTL_HOURS);
        return statusReportRepository.countActiveReports(since);
    }

    /**
     * 管理端移除暂停报（需 ADMIN，幂等）。
     * <p>
     * 移除 = 平台清理虚假/失效信号：soft delete 后所有"活跃"查询（热度计数/公开列表/
     * 管理端列表）立即过滤掉该报告——公开视图即时消失，无需等 TTL 过期。移除后
     * 失效 venueHeat 缓存（活跃报告数是热度输出之一，与提交/撤销同模式）。
     * <p>
     * 与用户自撤（{@link #cancelReport}）的差异：操作者是管理员而非上报者本人；
     * 两者同属 soft delete 语义（不删除物理行），已移除记录对上报者同样不再展示。
     */
    @Transactional
    public void removeReport(Long id) {
        UserContext.requireAdmin();
        VenueStatusReport report = statusReportRepository.findById(id)
                .orElseThrow(() -> new BusinessException(1008, "上报不存在"));
        if (report.isDeleted()) {
            return; // 幂等：已移除直接返回（不重复失效缓存）
        }
        report.setDeleted(true);
        statusReportRepository.save(report);
        venueHeatService.invalidate(report.getVenueId());
    }

    /**
     * 管理端采纳暂停报（2026-08-10 新增，需 ADMIN，幂等）。
     * <p>
     * 采纳 = 管理员核实暂停属实（区别于移除：移除 = 清理虚假/失效信号，无副作用）。
     * 采纳在<b>同一事务</b>内完成四件事（任一失败整体回滚，杜绝"状态已改但积分未发"等
     * 半完成状态）：
     * <ol>
     *   <li><b>门店营业状态随之改为「暂停营业」</b>——经
     *       {@link VenueService#markSuspendedByReport}（写状态变迁日志 + 场所/热门缓存逐出）；</li>
     *   <li><b>报告软删</b>（deleted=true）——不再作为活跃信号，公开列表/管理端列表/
     *       热度计数立即过滤（与移除同语义，见 {@link #removeReport}）；</li>
     *   <li><b>积分奖励</b>——上报者（userId 非空）经
     *       {@link PointsService#rewardStatusReport} 发放，流水幂等键
     *       (user, STATUS_REPORT_REWARD, reportId) 兜底并发；</li>
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
        // 1. 门店营业状态随之改动（状态变迁日志 + 场所/热门缓存逐出，同事务）
        venueService.markSuspendedByReport(report.getVenueId(), adminId);
        // 2. 报告不再作为活跃信号
        report.setDeleted(true);
        statusReportRepository.save(report);
        // 3. 积分奖励（同事务；匿名不发、流水幂等键兜底并发）
        if (report.getUserId() != null) {
            pointsService.rewardStatusReport(report.getUserId(), report.getId());
        }
        // 4. 处理结果站内信（同事务；匿名不通知）
        notifyAdopted(report);
        // 5. 热度缓存失效（活跃报告数是热度输出之一；门店已是 SUSPENDED 时
        //    markSuspendedByReport 早退不失效，此处必须无条件失效——与提交/撤销/移除同模式）
        venueHeatService.invalidate(report.getVenueId());
    }

    /**
     * 采纳结果站内信（2026-08-10 新增）：采纳流转实际发生时向上报者发送
     * STATUS_REPORT_RESULT，与采纳同事务（通知不丢失）。仅陈述事实：场所名 + 采纳结论
     * + 门店已标记暂停营业 + 积分已发放——奖励数额不在消息内硬编码（以积分流水为唯一
     * 事实源，同 {@link VenueFeedbackService#notifyHandled} 约定）；软关联 VENUE 深链。
     * 匿名上报（userId null）无法归属，不通知（与积分奖励同一匿名边界）。
     */
    private void notifyAdopted(VenueStatusReport report) {
        if (report.getUserId() == null) {
            return;
        }
        String venueName = venueRepository.findByIdAndDeletedFalse(report.getVenueId())
                .map(Venue::getName)
                .orElse("已下架场所");
        messageService.create(report.getUserId(), MessageType.STATUS_REPORT_RESULT,
                "暂停报已采纳",
                "「" + venueName + "」的暂停营业报告已被采纳，该门店已标记为暂停营业，奖励积分已发放。",
                "VENUE", report.getVenueId());
    }
}
