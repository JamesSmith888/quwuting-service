package org.quwuting.quwutingservice.venue.dailyopening.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venue.dailyopening.entity.VenueDailyOpening;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningConfidence;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningStatus;
import org.quwuting.quwutingservice.venue.dailyopening.repository.VenueDailyOpeningRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venuestatuswatcher.service.VenueStatusWatcherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 门店每日营业快照服务（2026-08-31，V63，docs/agents/ 管线侧 quwuting-ops/venue-opening）。
 * <p>
 * 职责（两条路径，语义分离）：
 * <ol>
 *   <li><b>快照落库</b>：把信息源的「今日营业」当日快照写入
 *       qwt_venue_daily_openings（幂等 upsert，同店同日报导源至多一条）——
 *       只记录事实，不改 venue.status；</li>
 *   <li><b>高置信反转</b>：当快照 status=OPEN 且匹配置信度 ∈ {EXACT, ALIAS} 时，
 *       若平台 status ∈ {CEASED, SUSPENDED}（信息源今日营业 → 平台数据滞后），
 *       自动反转为 OPEN——走完整状态变更审计链（VenueStatusLog + 关注者通知 +
 *       热度/详情/列表缓存失效），与人工编辑同权。CONTAINED/FUZZY 只落快照不反转
 *       （存在误配风险，如「小马」命中酒吧），留人工复核。</li>
 * </ol>
 * 单向语义：反转只做 CEASED/SUSPENDED → OPEN（「救活」滞后门店）；OPEN 门店
 * 信息源某日漏报不会自动改回（快照缺失 ≠ 停业，避免误伤），长期停业确认仍走
 * 管理端人工通道。changedBy 恒为 null（系统/Agent 来源，与人工操作可区分审计）。
 */
@Service
@RequiredArgsConstructor
public class DailyOpeningService {

    private final VenueDailyOpeningRepository dailyOpeningRepository;
    private final VenueRepository venueRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final VenueStatusWatcherService venueStatusWatcherService;
    private final VenueHeatService venueHeatService;
    private final VenueService venueService;

    @Transactional
    public BatchApplyResult applyBatch(ApplyDailyOpeningBatchRequest request) {
        List<ApplyDailyOpeningRequest> items = request.items();
        // ⚠️ 时间红线：JVM 时区（北京时间）传入，禁止 DB now()（Supabase 会话 UTC 错位 8h）
        LocalDateTime now = LocalDateTime.now();

        int applied = 0;
        int notFound = 0;
        List<BatchApplyResult.ReversalDetail> reversals = new ArrayList<>();
        Set<Long> reversedVenueIds = new LinkedHashSet<>();

        for (ApplyDailyOpeningRequest item : items) {
            // 1) 快照 upsert（幂等）
            dailyOpeningRepository.upsert(
                    item.venueId(), item.reportDate(), item.sourceId(),
                    item.status().name(), item.confidence().name(), now, now);
            applied++;

            // 2) 反转：仅 OPEN + 可信来源触发
            //    可信来源 = EXACT/ALIAS（自动可反转）或 forceReversal
            //    （2026-09-01：管理员在管理端单条写库确认放行，低置信 CONTAINED/FUZZY
            //    经人工审核也可反转；快照 confidence 仍存原始值，审计不失真）
            if (item.status() != DailyOpeningStatus.OPEN
                    || (!isAutoReversible(item)
                        && item.forceReversal() != Boolean.TRUE)) {
                continue;
            }
            Venue venue = venueRepository.findById(item.venueId()).orElse(null);
            if (venue == null || venue.isDeleted()) {
                notFound++;
                continue;
            }
            VenueStatus current = venue.getStatus();
            if (current != VenueStatus.CEASED && current != VenueStatus.SUSPENDED) {
                continue; // 已营业/装修/休息：无需反转（快照缺失不反向改状态）
            }

            // 反转 + 完整审计链（与人工编辑同权）
            venue.setStatus(VenueStatus.OPEN);
            venueRepository.save(venue);

            VenueStatusLog statusLog = new VenueStatusLog();
            statusLog.setVenueId(venue.getId());
            statusLog.setFromStatus(current);
            statusLog.setToStatus(VenueStatus.OPEN);
            statusLog.setChangedBy(null); // null = 系统/Agent 来源（人工=userId）
            venueStatusLogRepository.save(statusLog);

            venueStatusWatcherService.notifyStatusChanged(venue.getId(), current, VenueStatus.OPEN);
            venueHeatService.invalidate(venue.getId());
            venueService.invalidateDetailPublic(venue.getId());
            reversedVenueIds.add(venue.getId());
            reversals.add(new BatchApplyResult.ReversalDetail(
                    venue.getId(), venue.getName(), current.name(),
                    VenueStatus.OPEN.name(), item.sourceId(), item.confidence().name()));
        }

        // 列表缓存为全局维度，批量反转后统一失效一次（避免逐店重复失效）
        if (!reversedVenueIds.isEmpty()) {
            venueService.invalidateVenueListCache();
        }

        return new BatchApplyResult(items.size(), applied, reversals.size(), notFound, reversals);
    }

    /** 自动可反转 = EXACT/ALIAS（高置信；低置信需管理员确认 forceReversal） */
    private boolean isAutoReversible(ApplyDailyOpeningRequest item) {
        DailyOpeningConfidence c = item.confidence();
        return c == DailyOpeningConfidence.EXACT || c == DailyOpeningConfidence.ALIAS;
    }
}
