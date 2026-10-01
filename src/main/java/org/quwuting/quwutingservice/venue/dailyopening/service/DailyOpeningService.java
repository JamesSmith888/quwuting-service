package org.quwuting.quwutingservice.venue.dailyopening.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyVenueSuspendBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.VenueSuspendItemRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchSuspendResult;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.SkippedByGuardDetail;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.SuspendCityImpact;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningConfidence;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningStatus;
import org.quwuting.quwutingservice.venue.dailyopening.enums.GuardSkipReason;
import org.quwuting.quwutingservice.venue.change.VenueChangePublisher;
import org.quwuting.quwutingservice.venue.change.VenueFactChange;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.service.VenueStatusGuardService;
import org.quwuting.quwutingservice.venuestatuswatcher.service.VenueStatusWatcherService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
 * <p>
 * <b>2026-09-14 叠加入口门禁（V25，方案见 docs/agents/48）</b>：两个通道在真正改状态前，
 * 都必须先问 {@link VenueStatusGuardService} —— 舞讯是第三方整理、并不 100% 可靠，而管理员
 * 手工修正过的状态是更权威的判断。逐店判定：
 * <ul>
 *   <li>已永久豁免 / 人工锁未过期 ⇒ **跳过**（不写库、不通知、不发公告），计入返回体
 *       {@code skippedLocked} / {@code skippedExempt} 与 {@code skipped} 明细；</li>
 *   <li>其余 ⇒ 允许覆盖，成功后由 Guard 接管所有权（statusSource=SYNC）并清除人工锁。</li>
 * </ul>
 * 例外：{@code source="ADMIN"}（管理端在同步报告里<b>勾选应用</b>）属人工背书，可越过人工锁
 * ——「人类明确的动作永远能推翻前一个人类判断」；但同样清锁并把所有权交回自动同步。
 * 门禁判定唯一实现在 {@link VenueStatusGuardService}，本类只负责按结果跳过与统计。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DailyOpeningService {

    private final VenueRepository venueRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final VenueStatusWatcherService venueStatusWatcherService;
    private final VenueStatusGuardService venueStatusGuardService;
    /** 门店事实变更声明（缓存失效由各属主订阅、提交后执行，见 VenueChangePublisher） */
    private final VenueChangePublisher venueChangePublisher;
    /** 关门方向影响面熔断（2026-10-01，见 SuspendBlastRadiusGuard） */
    private final SuspendBlastRadiusGuard suspendBlastRadiusGuard;
    private final org.quwuting.quwutingservice.announcement.service.AnnouncementService announcementService;

    /** 管理端「同步报告勾选应用」的来源标签：人工背书，可越过人工锁（语义见类注释）。 */
    private static final String SOURCE_ADMIN = "ADMIN";

    @Transactional
    public BatchApplyResult applyBatch(ApplyDailyOpeningBatchRequest request) {
        List<ApplyDailyOpeningRequest> items = request.items();

        int notFound = 0;
        int skippedLocked = 0;
        int skippedExempt = 0;
        List<SkippedByGuardDetail> skipped = new ArrayList<>();
        List<BatchApplyResult.ReversalDetail> reversals = new ArrayList<>();
        Set<Long> reversedVenueIds = new LinkedHashSet<>();
        LocalDateTime now = LocalDateTime.now();

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

            // 入口门禁（V25）：人工判断优先于外部舞讯推断。放在「确需动作」之后判定——
            // 已 OPEN 的店本来就不需要动作，不该被算成「门禁跳过」（会污染汇报口径）。
            // source="ADMIN" = 管理端勾选应用（人工背书），可越过人工锁。
            boolean humanConfirmed = SOURCE_ADMIN.equalsIgnoreCase(item.source());
            if (humanConfirmed) {
                // 人工确认放行：不改写来源标签（审计仍显示 ADMIN），但会把所有权交回自动同步
                venueStatusGuardService.takeOverByExternalWrite(venue);
            } else {
                VenueStatusGuardService.Decision decision =
                        venueStatusGuardService.decideExternalWrite(venue, now);
                if (!decision.allowed()) {
                    if (decision.reason() == GuardSkipReason.EXEMPT) {
                        skippedExempt++;
                    } else {
                        skippedLocked++;
                    }
                    skipped.add(new SkippedByGuardDetail(venue.getId(), venue.getName(),
                            decision.reason().name(), decision.lockedUntil()));
                    continue;
                }
                venueStatusGuardService.takeOverByExternalWrite(venue);
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
            reversedVenueIds.add(venue.getId());
            reversals.add(new BatchApplyResult.ReversalDetail(
                    venue.getId(), venue.getName(), current.name(),
                    VenueStatus.OPEN.name(), item.sourceId(), item.confidence().name(),
                    item.source()));
        }

        // 整批一次声明门店事实变更（逐店 + 全局读模型在提交后统一失效）
        if (!reversedVenueIds.isEmpty()) {
            venueChangePublisher.publish(VenueFactChange.STATUS, reversedVenueIds);
            // 数据更新公告（2026-09-01，docs/agents/34）：营业状态批量反转成功触发
            // SYSTEM 公告；开关关闭/同日已存在 → 内部幂等跳过，不干扰写库主流程
            announcementService.createDataUpdateAnnouncement(0, reversals.size());
        }
        if (!skipped.isEmpty()) {
            log.info("[venue-daily-openings/batch] 门禁跳过 {} 条（锁内 {} / 豁免 {}）",
                    skipped.size(), skippedLocked, skippedExempt);
        }

        return new BatchApplyResult(items.size(), reversals.size(), notFound,
                skippedLocked, skippedExempt, skipped, reversals);
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
     *       已有 SUSPENDED 逐条目幂等跳过（无冗余日志）；</li>
     *   <li><b>入口门禁（2026-09-14，V25）</b>：与 {@link #applyBatch} 同规律——人工锁未过期
     *       或已豁免的门店跳过，计入 {@code skippedLocked} / {@code skippedExempt}。
     *       这一方向最需要保护：本通道一次会暂停几十上百家，管理员手工纠错后若被批量冲回，
     *       用户看到「暂停营业」会直接白跑。</li>
     *   <li><b>影响面熔断 + 预演（2026-10-01，见 {@link SuspendBlastRadiusGuard}）</b>：执行分
     *       「规划 → 熔断 → 执行」三段，规划零副作用；某城本批暂停占比超上限且未在
     *       {@code confirmedCities} 中确认 ⇒ 整批拒绝（1036，不做部分执行）；{@code dryRun=true}
     *       只返回计划与逐城影响面。根因：「城市范围由调用方保证」把全部护栏放在 Skill 提示词里，
     *       一次来源漏发即可让整城被批量改成暂停并给收藏者推送真实通知。</li>
     * </ul>
     *
     * @param request 待暂停门店条目（调用方已按「同城 + 未上榜」筛出，≤500 条）
     * @return 暂停统计与明细（审计/回滚依据）
     */
    @Transactional
    public BatchSuspendResult applyBatchSuspend(ApplyVenueSuspendBatchRequest request) {
        List<VenueSuspendItemRequest> items = request.items();
        boolean dryRun = Boolean.TRUE.equals(request.dryRun());
        LocalDateTime now = LocalDateTime.now();
        Map<Long, Venue> venues = loadVenues(items);

        // ① 规划（零副作用）：确定「真正会被暂停」的门店——不存在 / 非 OPEN / 门禁跳过 / 批内重复
        //    都不计入。熔断必须作用在这个集合上，而不是作用在调用方提交的原始条目上
        //   （锁内门店本来就不会被改，算进分子会把正常批次误判成熔断）。
        int notFound = 0;
        int skippedLocked = 0;
        int skippedExempt = 0;
        List<SkippedByGuardDetail> skipped = new ArrayList<>();
        List<PlannedSuspend> planned = new ArrayList<>();
        Set<Long> plannedIds = new LinkedHashSet<>();
        for (VenueSuspendItemRequest item : items) {
            Venue venue = venues.get(item.venueId());
            if (venue == null || venue.isDeleted()) {
                notFound++;
                continue;
            }
            if (venue.getStatus() != VenueStatus.OPEN || plannedIds.contains(venue.getId())) {
                continue; // 非营业中不做暂停（已停业/休息/装修保持原状）；批内重复条目幂等跳过
            }
            // 入口门禁（V25）：同 applyBatch（先判「确需动作」再判门禁，语义见该方法注释）。
            // source="ADMIN" = 管理端人工背书，可越过人工锁。
            boolean humanConfirmed = SOURCE_ADMIN.equalsIgnoreCase(item.source());
            if (!humanConfirmed) {
                VenueStatusGuardService.Decision decision =
                        venueStatusGuardService.decideExternalWrite(venue, now);
                if (!decision.allowed()) {
                    if (decision.reason() == GuardSkipReason.EXEMPT) {
                        skippedExempt++;
                    } else {
                        skippedLocked++;
                    }
                    skipped.add(new SkippedByGuardDetail(venue.getId(), venue.getName(),
                            decision.reason().name(), decision.lockedUntil()));
                    continue;
                }
            }
            planned.add(new PlannedSuspend(venue, item));
            plannedIds.add(venue.getId());
        }

        // ② 影响面熔断（逐城）：预演只报告不拦截；正式执行时存在「熔断且未确认」的城市 ⇒ 整批拒绝
        List<SuspendCityImpact> cityImpacts = suspendBlastRadiusGuard.evaluate(
                planned.stream().map(p -> p.venue().getCity()).toList(), request.confirmedCities());
        if (!dryRun) {
            suspendBlastRadiusGuard.requireWithinLimits(cityImpacts);
        }

        List<BatchSuspendResult.SuspendDetail> details = new ArrayList<>(planned.size());
        for (PlannedSuspend p : planned) {
            details.add(new BatchSuspendResult.SuspendDetail(
                    p.venue().getId(), p.venue().getName(), VenueStatus.OPEN.name(),
                    VenueStatus.SUSPENDED.name(), p.item().sourceId(), p.item().source()));
        }
        if (!skipped.isEmpty()) {
            log.info("[venue-daily-openings/batch-suspend] 门禁跳过 {} 条（锁内 {} / 豁免 {}）{}",
                    skipped.size(), skippedLocked, skippedExempt, dryRun ? "（预演）" : "");
        }
        // ③ 预演：返回与正式执行同结构的计划，不写库、不通知、不失效缓存
        if (dryRun) {
            return new BatchSuspendResult(items.size(), 0, notFound,
                    skippedLocked, skippedExempt, skipped, details, true, cityImpacts);
        }

        // ④ 执行：审计链与人工改状态（markSuspendedByReport）同权
        for (PlannedSuspend p : planned) {
            Venue venue = p.venue();
            venueStatusGuardService.takeOverByExternalWrite(venue);
            venue.setStatus(VenueStatus.SUSPENDED);
            venueRepository.save(venue);

            VenueStatusLog statusLog = new VenueStatusLog();
            statusLog.setVenueId(venue.getId());
            statusLog.setFromStatus(VenueStatus.OPEN);
            statusLog.setToStatus(VenueStatus.SUSPENDED);
            statusLog.setChangedBy(null); // null = 系统/Agent 来源（人工=userId）
            statusLog.setChangeSource(p.item().source()); // 批量更新标识（AGENT_BATCH=Agent+Skill 批量）
            venueStatusLogRepository.save(statusLog);

            venueStatusWatcherService.notifyStatusChanged(venue.getId(), VenueStatus.OPEN, VenueStatus.SUSPENDED);
        }
        // 整批一次声明门店事实变更；刻意不触发数据更新公告：暂停方向无正向信息量（见方法注释）
        venueChangePublisher.publish(VenueFactChange.STATUS, plannedIds);

        return new BatchSuspendResult(items.size(), details.size(), notFound,
                skippedLocked, skippedExempt, skipped, details, false, cityImpacts);
    }

    /** 一次查询批量加载本批门店（替代逐条 findById 的 N 次往返） */
    private Map<Long, Venue> loadVenues(List<VenueSuspendItemRequest> items) {
        Set<Long> ids = new LinkedHashSet<>();
        for (VenueSuspendItemRequest item : items) {
            ids.add(item.venueId());
        }
        Map<Long, Venue> byId = new HashMap<>();
        for (Venue venue : venueRepository.findAllById(ids)) {
            byId.put(venue.getId(), venue);
        }
        return byId;
    }

    /** 规划阶段的产物：将被暂停的门店 + 其来源条目（审计字段取自条目） */
    private record PlannedSuspend(Venue venue, VenueSuspendItemRequest item) {}
}
