package org.quwuting.quwutingservice.venue.dailyopening.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyVenueSuspendBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.VenueSuspendItemRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchSuspendResult;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningConfidence;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningStatus;
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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 门店状态反转服务（2026-08-31，V63；**2026-09-01 快照机制退出，仅剩反转**）。
 * <p>
 * 职责演进：原为「每日营业快照落库 + 高置信反转」两件事；2026-09-01 用户明确
 * 「不用快照机制」后，<b>写库 = 仅状态反转</b>——不再写 qwt_venue_daily_openings
 * （表与存量保留、停止写入；每日营业记录/证据链机制整体退出）。
 * <p>
 * 反转语义（单向，避免误伤）：
 * <ul>
 *   <li>仅处理资讯 OPEN 条目：当信息源声称「今日营业」且平台 status ∈ {CEASED,
 *       SUSPENDED}（信息源营业 → 平台数据滞后）时，反转为 OPEN；</li>
 *   <li>反转需来源可信：EXACT/ALIAS 自动可反转；CONTAINED/FUZZY 低置信仅当
 *       forceReversal=true（2026-09-01：管理端单条写库人工确认放行）才反转；</li>
 *   <li>资讯 CLOSED 不产生任何动作（快照缺失 ≠ 停业，避免「源某日漏报」误伤；
 *       长期停业确认仍走管理端人工通道）；不会把营业中的门店标停业。</li>
 * </ul>
 * 反转走完整状态变更审计链（VenueStatusLog + 关注者通知 + 热度/详情/列表缓存失效），
 * 与人工编辑同权；changedBy 恒为 null（null = 系统/Agent 来源，人工=userId——
 * 「更新记录」按此区分系统反转与人工编辑）。
 * <p>
 * <b>2026-09-10 新增反向通道（白名单口径）</b>：{@link #applyBatchSuspend} —— 当日舞讯
 * <b>点名覆盖的城市</b>内、未出现在营业名单里的门店，视为今日未营业，OPEN → SUSPENDED。
 * 这是对上述「未上榜 ≠ 停业」保守语义的<b>有界推翻</b>：仅在「该城已被舞讯覆盖」这一
 * 事实成立时才推断（城市级覆盖精确、非匹配猜测），未被覆盖的城市一律不动。
 * 两个方向各自独立入口，互不覆盖：{@link #applyBatch} 只做恢复、
 * {@link #applyBatchSuspend} 只做暂停——同一批次内不会互相打架（只 OPEN↔SUSPENDED 互转）。
 */
@Service
@RequiredArgsConstructor
public class DailyOpeningService {

    private final VenueRepository venueRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final VenueStatusWatcherService venueStatusWatcherService;
    private final VenueHeatService venueHeatService;
    private final VenueService venueService;
    private final org.quwuting.quwutingservice.announcement.service.AnnouncementService announcementService;

    @Transactional
    public BatchApplyResult applyBatch(ApplyDailyOpeningBatchRequest request) {
        List<ApplyDailyOpeningRequest> items = request.items();

        int notFound = 0;
        List<BatchApplyResult.ReversalDetail> reversals = new ArrayList<>();
        Set<Long> reversedVenueIds = new LinkedHashSet<>();

        for (ApplyDailyOpeningRequest item : items) {
            // 快照机制已退出：仅 OPEN + 来源可信的条目评估反转，资讯休息不落任何记录
            // 来源可信 = EXACT/ALIAS（自动可反转）或 forceReversal
            //（2026-09-01：管理员在管理端单条写库确认放行，低置信 CONTAINED/FUZZY
            //  经人工审核也可反转）
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
            statusLog.setChangeSource(item.source()); // 批量更新标识（AGENT_BATCH=Agent+Skill 批量，V8）
            venueStatusLogRepository.save(statusLog);

            venueStatusWatcherService.notifyStatusChanged(venue.getId(), current, VenueStatus.OPEN);
            venueHeatService.invalidate(venue.getId());
            venueService.invalidateDetailPublic(venue.getId());
            reversedVenueIds.add(venue.getId());
            reversals.add(new BatchApplyResult.ReversalDetail(
                    venue.getId(), venue.getName(), current.name(),
                    VenueStatus.OPEN.name(), item.sourceId(), item.confidence().name(),
                    item.source()));
        }

        // 列表缓存为全局维度，批量反转后统一失效一次（避免逐店重复失效）
        if (!reversedVenueIds.isEmpty()) {
            venueService.invalidateVenueListCache();
            // 数据更新公告（2026-09-01，docs/agents/34）：营业状态批量反转成功触发
            // SYSTEM 公告；开关关闭/同日已存在 → 内部幂等跳过，不干扰写库主流程
            announcementService.createDataUpdateAnnouncement(0, reversals.size());
        }

        return new BatchApplyResult(items.size(), reversals.size(), notFound, reversals);
    }

    /** 自动可反转 = EXACT/ALIAS（高置信；低置信需管理员确认 forceReversal） */
    private boolean isAutoReversible(ApplyDailyOpeningRequest item) {
        DailyOpeningConfidence c = item.confidence();
        return c == DailyOpeningConfidence.EXACT || c == DailyOpeningConfidence.ALIAS;
    }

    /**
     * 批量置「暂停营业」（2026-09-10，白名单口径反向写库）。
     * <p>
     * 语义：当日舞讯点名覆盖的城市内，未上榜门店 → OPEN 置 SUSPENDED。
     * <ul>
     *   <li><b>仅 OPEN 参与</b>：CEASED（已停业）/CLOSED（休息中）/RENOVATING（装修中）
     *       保持不动——避免把长期停业/装修中的店「降级」成暂停营业，也避免与
     *       applyBatch 的反转结果互相覆盖；</li>
     *   <li><b>城市范围由调用方保证</b>：本方法只按 venueId 执行，不做城市推断——
     *       服务端无法区分「未上榜」与「该城压根没被舞讯覆盖」，越权推断会误伤全平台；</li>
     *   <li><b>不产生数据更新公告</b>：与 applyBatch 不同，暂停方向不发公告——
     *       公告口径是「新增/恢复」的正向信息，数百家暂停公告是纯噪音；</li>
     *   <li>审计链完整：VenueStatusLog（changedBy=null + changeSource）+ 关注者站内信/
     *       订阅消息 + 热度/详情/列表缓存失效，与人工改状态（markSuspendedByReport）同权；
     *       已有 SUSPENDED 逐条目幂等跳过（无冗余日志）。</li>
     * </ul>
     *
     * @param request 待暂停门店条目（调用方已按「同城 + 未上榜」筛出，≤500 条）
     * @return 暂停统计与明细（审计/回滚依据）
     */
    @Transactional
    public BatchSuspendResult applyBatchSuspend(ApplyVenueSuspendBatchRequest request) {
        List<VenueSuspendItemRequest> items = request.items();

        int notFound = 0;
        List<BatchSuspendResult.SuspendDetail> details = new ArrayList<>();
        Set<Long> suspendedVenueIds = new LinkedHashSet<>();

        for (VenueSuspendItemRequest item : items) {
            Venue venue = venueRepository.findById(item.venueId()).orElse(null);
            if (venue == null || venue.isDeleted()) {
                notFound++;
                continue;
            }
            VenueStatus current = venue.getStatus();
            if (current != VenueStatus.OPEN) {
                continue; // 非营业中：不做暂停（已停业/休息/装修保持原状，幂等跳过）
            }

            venue.setStatus(VenueStatus.SUSPENDED);
            venueRepository.save(venue);

            VenueStatusLog statusLog = new VenueStatusLog();
            statusLog.setVenueId(venue.getId());
            statusLog.setFromStatus(current);
            statusLog.setToStatus(VenueStatus.SUSPENDED);
            statusLog.setChangedBy(null); // null = 系统/Agent 来源（人工=userId）
            statusLog.setChangeSource(item.source()); // 批量更新标识（AGENT_BATCH=Agent+Skill 批量）
            venueStatusLogRepository.save(statusLog);

            venueStatusWatcherService.notifyStatusChanged(venue.getId(), current, VenueStatus.SUSPENDED);
            venueHeatService.invalidate(venue.getId());
            venueService.invalidateDetailPublic(venue.getId());
            suspendedVenueIds.add(venue.getId());
            details.add(new BatchSuspendResult.SuspendDetail(
                    venue.getId(), venue.getName(), current.name(),
                    VenueStatus.SUSPENDED.name(), item.sourceId(), item.source()));
        }

        // 列表缓存为全局维度，批量变更后统一失效一次（避免逐店重复失效）
        if (!suspendedVenueIds.isEmpty()) {
            venueService.invalidateVenueListCache();
            // 刻意不触发数据更新公告：暂停方向无正向信息量（见方法注释）
        }

        return new BatchSuspendResult(items.size(), details.size(), notFound, details);
    }
}
