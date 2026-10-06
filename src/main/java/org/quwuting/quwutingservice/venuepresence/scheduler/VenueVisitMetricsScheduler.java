package org.quwuting.quwutingservice.venuepresence.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.service.HeatAccountExclusionService;
import org.quwuting.quwutingservice.venuepresence.repository.VenueVisitMetricRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitShare;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 门店到访指标刷新调度器（2026-10-06，V38；文档 = docs/agents/52-venue-presence.md「到访进排序」）。
 * <p>
 * <b>职责</b>：把「派生量」到访人数（归因 × 分摊 × 排除内部账号）落到
 * {@code qwt_venue_visit_metrics}，让热度公式可以只做一次主键点查的标量子查询。
 * 这是到访能进 {@code HEAT_BEHAVIOR} 的**唯一合规路径**（JPQL 无派生表能力，
 * 见 {@code VenueVisitMetric} 类注释）。
 *
 * <h3>为什么是独立 Bean</h3>
 * 与 {@code VenueOpeningScheduler} 同款理由：刷新方法带 {@code @Transactional}，
 * 同类内自调用不经 Spring 代理会让注解静默失效。
 *
 * <h3>两段式刷新（积分清零 + 逐店 upsert）</h3>
 * <ol>
 *   <li>{@link VenueVisitMetricRepository#resetAll}：整表归零。到访是 30 天滚动窗口，
 *       "上月有过访、本月没有"的门店必须回落为 0，否则排序被过期信号持续加分；</li>
 *   <li>逐店 upsert 当前窗口内的份额（{@link VenuePresenceService#visitSharesForRanking}）。</li>
 * </ol>
 * 两段合起来等价于「全量重算」，但写量 = 有到访历史门店数（稀疏量级），无需记录"上轮有哪些店"。
 *
 * <h3>节奏与代价</h3>
 * 默认 30 分钟（{@code fixedDelay}）。到访 ping 的到达是稀疏事件（2026-10-06 现网约 70 条/周），
 * 30 分钟的相对滞后对"近 30 天人数"这个滚动量无意义损失。
 * <b>代价必须知晓</b>：到访项因此<b>有刷新延迟</b>，与列表其余项"当日行为当天反映排序"的
 * 现网契约不同——这是**有意例外**，已登记在 05 号文档，勿当成缺陷"顺手改回实时"。
 *
 * <h3>⚠️ 对生产库的门禁测试</h3>
 * 本类带 {@code @Scheduled} ⇒ <b>禁止对生产库跑 {@code -Drun.db.tests=true} 的
 * {@code @SpringBootTest} 全上下文</b>（会真实写生产，同 {@code VenueOpeningScheduler} 先例）。
 *
 * <h3>失败隔离</h3>
 * 整轮 try-catch：刷新失败只影响本轮的"到访项陈旧"，公式其余项不受影响
 * （公式读的是物化值，读到旧值仍是**有效值**，不是脏值）。下一轮自动重试，无需补偿任务。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VenueVisitMetricsScheduler {

    /**
     * 刷新周期（毫秒）：30 分钟。
     * <p>
     * 取值依据：到访人数是 30 天滚动量，30 分钟的相对滞后 &lt; 0.1% 窗口；
     * 更频繁只增加写量（每轮 N 次 UPDATE + M 次 upsert），不改变结果。
     * 调更短前先读 {@code VenueVisitMetric} 类注释里的"排序不再全实时"取舍。
     */
    private static final long REFRESH_INTERVAL_MS = 30 * 60 * 1000L;

    /**
     * 首轮延迟（毫秒）：60 秒。
     * <p>
     * 让应用完成启动/迁移/连接池预热后再跑第一轮——避免与 Flyway 建表竞争，
     * 也让本地起服务时能立刻看到一轮真实刷新日志（便于联调）。
     */
    private static final long INITIAL_DELAY_MS = 60 * 1000L;

    private final VenuePresenceService venuePresenceService;
    private final VenueVisitMetricRepository metricRepository;
    private final HeatAccountExclusionService heatExclusionService;

    /**
     * 刷新到访指标物化表。整轮一个事务（清零 + 全部 upsert 要么全成、要么全回滚，
     * 不会留下"清零了但没写回"的中间态）。
     */
    @Scheduled(initialDelay = INITIAL_DELAY_MS, fixedDelay = REFRESH_INTERVAL_MS)
    @Transactional
    public void refresh() {
        try {
            LocalDateTime now = LocalDateTime.now();
            Map<Long, VenueVisitShare> shares =
                    venuePresenceService.visitSharesForRanking(heatExclusionService.excludedUserIds());
            metricRepository.resetAll(now);
            shares.forEach((venueId, share) -> metricRepository.upsert(
                    venueId, share.visitUsers30d(), share.visitUsers7d(), share.groupSize(), now));
            // 无到访（或全被让渡 / 不在营）的轮次很常见——只在真有数据时记日志，避免 30 分钟一条噪音
            if (!shares.isEmpty()) {
                log.info("[venue-visit-metrics] 到访指标刷新：门店数={}", shares.size());
            }
        } catch (Exception e) {
            // 本轮失败不影响公式其余项（公式读到的是上一轮的有效值，不是脏值），下一轮自动重试
            log.error("[venue-visit-metrics] 到访指标刷新失败，本轮跳过", e);
        }
    }
}
