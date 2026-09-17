package org.quwuting.quwutingservice.venue.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 门店开业计划调度器（2026-09-17，V29；方案见 docs/agents/50-venue-opening-plan.md）。
 * <p>
 * <b>解决什么</b>：运营设了「9月18日开业」的门店，到点必须**真的变成营业中**——
 * 否则徽标永远停在派生展示态、关注者收不到通知、门店也不会进入热度/统计的正常口径。
 * 这是「计划」与「事实」之间唯一的桥。
 *
 * <h3>为什么单独一个类，不写在 VenueService 里</h3>
 * {@code VenueService#applyScheduledOpening} 带 {@code @Transactional} + {@code @Caching}，
 * 而 <b>同类内自调用不经 Spring 代理</b>——把调度循环写进 VenueService 会让那两个注解
 * <b>静默失效</b>（事务不生效、缓存不逐出，且不报任何错）。拆成独立 Bean 经代理调用，
 * 是本仓既有的同款手法（对齐 {@code StatusReportLatestService} 为打破构造器循环而拆微服务
 * 的先例：能力边界与注入关系决定类的边界）。
 *
 * <h3>为什么 30s 轮询而不是每日定点</h3>
 * 与公告域 {@code processScheduledTransitions} / 活动域 {@code processScheduledTransitions}
 * 完全同款（同一个系统里"到点自动变更"只该有一种节奏）。判据是<b>日期粒度</b>
 * （{@code expected_open_date <= today}），所以 00:00 一过即生效——当天的
 * 「还没到营业时段」由前端 {@code NOT_OPEN_YET} 派生自然接管（徽标显示「今晚 19:00 开门」），
 * 与开业日无缝衔接，无需在这里判断具体时刻。
 * <p>
 * <b>幂等</b>：兑现后 {@code expected_open_date} 立即被清空 ⇒ 下一轮查不到该行。
 * 重复提交/多实例并发由行级 {@code applyScheduledOpening} 的"开业日为空即早退"兜底。
 *
 * <h3>失败隔离</h3>
 * 单店 {@code try-catch}：一家店失败不阻塞同批其它店；抛出的店本轮不推进、下一轮自动重试
 * （开业日仍是「已到期」，查询条件不依赖任何"已处理"标记位——<b>无需补偿任务</b>）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VenueOpeningScheduler {

    /**
     * 单轮处理上限（防御性）。
     * <p>
     * 正常情况下"已到期待兑现"的门店是 0~个位数（开业是低频事件）。设上限是为了让
     * 任何一种数据异常（如批量误填了过去的日期）都不会演变成一次全表串行事务风暴——
     * 超出的部分留给下一轮（30s 后），系统永远不失控。
     */
    private static final int MAX_PER_ROUND = 200;

    private final VenueRepository venueRepository;
    private final VenueService venueService;

    /**
     * 每 30s 扫一次到期的开业计划并逐店兑现（走 {@link VenueService#applyScheduledOpening}
     * 正规通道：写状态日志 + 打 3 天人工锁 + 清三级缓存 + 通知关注者）。
     * <p>
     * <b>刻意不加 {@code @Transactional}</b>：本方法只做"取一批 + 逐店委派"，每店自己一个
     * 事务——加在这里会让整批共享一个事务，一家店失败即回滚已成功的那几家
     * （与上方的失败隔离要求直接冲突）。
     */
    @Scheduled(fixedDelay = 30_000)
    public void processScheduledOpenings() {
        List<Venue> due = venueRepository.findDueOpeningPlans(
                LocalDate.now(), PageRequest.of(0, MAX_PER_ROUND));
        if (due.isEmpty()) {
            return; // 绝大多数轮次走这里：不记日志，避免 30s 一条噪音
        }
        int applied = 0;
        for (Venue venue : due) {
            try {
                venueService.applyScheduledOpening(venue.getId());
                applied++;
            } catch (Exception e) {
                // 单店失败隔离：本轮跳过，下一轮自查（开业日未清空 ⇒ 仍在候选集内，天然重试）
                log.error("[venue/{}/scheduled-opening] 开业计划兑现失败，本轮跳过",
                        venue.getId(), e);
            }
        }
        log.info("[venue-opening] 开业计划调度：due={} applied={}", due.size(), applied);
    }
}
