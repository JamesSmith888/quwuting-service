package org.quwuting.quwutingservice.venuestatusreport.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuestatusreport.dto.request.SubmitReportRequest;
import org.quwuting.quwutingservice.venuestatusreport.dto.response.ActiveReportSummary;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportReason;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 场所实时状态报告服务。
 * <p>
 * 用户在详情页极速上报"暂停营业"，系统作为独立信号层展示（不修改 Venue.status），
 * 通过 TTL 过期 + {@link org.quwuting.quwutingservice.venue.service.VenueHeatService} 的
 * StatusConfidence override 形成闭环。
 * <p>
 * 写操作即时失效热度缓存（{@link CacheConfig#CACHE_VENUE_HEAT}），
 * 确保其他用户很快看到最新活跃报告数——与 TagInteractionService 的 @CacheEvict 模式一致。
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

    /**
     * 提交或更新状态报告（upsert 语义）。
     * 同一用户对同一场所只保留一条活跃记录，再次报告覆盖更新。
     */
    @CacheEvict(value = CacheConfig.CACHE_VENUE_HEAT, key = "#venueId")
    @Transactional
    public ActiveReportSummary submitReport(Long venueId, SubmitReportRequest req) {
        Long userId = UserContext.requireAuth();

        if (venueRepository.findByIdAndDeletedFalse(venueId).isEmpty()) {
            throw new BusinessException(1001, "场所不存在");
        }

        var existing = statusReportRepository.findByUserIdAndVenueIdAndDeletedFalse(userId, venueId);

        // 仅新建报告时检查全局频率限制（更新已有报告不限频）
        if (existing.isEmpty()) {
            checkRateLimit(userId);
        }

        if (existing.isPresent()) {
            VenueStatusReport report = existing.get();
            if (req.reason() != null) report.setReason(req.reason());
            report.setOccurredAt(req.occurredAt());
            report.setNote(req.note());
            statusReportRepository.save(report);
        } else {
            VenueStatusReport report = new VenueStatusReport();
            report.setVenueId(venueId);
            report.setUserId(userId);
            report.setReason(req.reason() != null ? req.reason() : ReportReason.UNKNOWN);
            report.setOccurredAt(req.occurredAt());
            report.setNote(req.note());
            try {
                statusReportRepository.save(report);
            } catch (DataIntegrityViolationException e) {
                // 并发竞态：另一请求已创建同一 (userId, venueId) 记录，幂等忽略
                log.debug("submitReport 并发冲突，幂等忽略: userId={}, venueId={}", userId, venueId);
            }
        }

        return getActiveReportSummary(venueId);
    }

    /**
     * 撤销当前用户对某场所的状态报告（soft delete）。
     */
    @CacheEvict(value = CacheConfig.CACHE_VENUE_HEAT, key = "#venueId")
    @Transactional
    public ActiveReportSummary cancelReport(Long venueId) {
        Long userId = UserContext.requireAuth();

        statusReportRepository.findByUserIdAndVenueIdAndDeletedFalse(userId, venueId)
                .ifPresent(report -> {
                    report.setDeleted(true);
                    statusReportRepository.save(report);
                });

        return getActiveReportSummary(venueId);
    }

    /**
     * 获取某场所的活跃报告摘要（供 VenueHeatService 调用）。
     * <p>
     * 活跃 = 未删除且 createdAt >= now - TTL。
     * 此方法无 @Cacheable——它被 {@link org.quwuting.quwutingservice.venue.service.VenueHeatService#getHeat}
     * 的 @Cacheable 包裹，其结果随 VenueHeatResponse 整体缓存，写操作通过 @CacheEvict 失效。
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
}
