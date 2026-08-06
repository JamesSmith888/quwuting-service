package org.quwuting.quwutingservice.venuestatusreport.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.MyStatusReportResponse;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.dao.DataIntegrityViolationException;
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

    private final StatusReportRepository statusReportRepository;
    private final VenueRepository venueRepository;
    private final VenueHeatService venueHeatService;

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
            // 刷新 createdAt 续期 TTL（@CreationTimestamp 仅在 INSERT 时设值，UPDATE 需手动设）
            report.setCreatedAt(LocalDateTime.now());
            statusReportRepository.save(report);
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
}
