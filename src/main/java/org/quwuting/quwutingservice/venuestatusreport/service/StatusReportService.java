package org.quwuting.quwutingservice.venuestatusreport.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
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

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 提交或更新状态报告（upsert 语义）。
     * 同一用户对同一场所只保留一条物理记录，再次报告覆盖更新。
     * 撤销后再次上报 = 恢复软删记录（不 INSERT，避免 UNIQUE 约束冲突）。
     */
    @CacheEvict(value = CacheConfig.CACHE_VENUE_HEAT, key = "#venueId")
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
            report.setNote(req.note());
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
            report.setNote(req.note());
            try {
                statusReportRepository.save(report);
            } catch (DataIntegrityViolationException e) {
                // 并发竞态：另一请求已创建同一 (userId, venueId) 记录
                // 必须清除 session 中的脏实体（null id），否则后续查询的 auto-flush 会抛 AssertionFailure
                log.debug("submitReport 并发冲突，幂等忽略: userId={}, venueId={}", userId, venueId);
                entityManager.clear();
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

    /**
     * 当前用户是否已对此场所有活跃报告（详情页个人状态）。
     * <p>
     * 个人状态实时查询、不缓存——与 likedByMe/myScore 同原则：
     * VenueDetailResponse 已是 per-user 响应（canManage 同理），不经过 @Cacheable。
     */
    @Transactional(readOnly = true)
    public boolean hasMyReport(Long venueId) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) return false;
        return statusReportRepository.existsByUserIdAndVenueIdAndDeletedFalse(userId, venueId);
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
