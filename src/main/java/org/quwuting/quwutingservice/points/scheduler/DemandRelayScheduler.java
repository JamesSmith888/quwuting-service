package org.quwuting.quwutingservice.points.scheduler;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.points.service.DemandRelayService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 邀约中转定时任务（2026-08-26，22-invite-relay-and-auto-release）。
 * <p>
 * 每 5 分钟扫描超时仍 PENDING 的邀约 → 按舞伴 autoRelease 开关自动降级：
 * true = AUTO_RELEASED（平台自动发放，兜底不卡单——客人不能被无限期干等）；
 * false = EXPIRED（告知客人暂未回复）。12h 催办不在此落字段——工作台按等待
 * 时长排序 + 超 12h 行高亮「建议催办」，由管理员人工微信催舞伴（人工中转的
 * 轻量定位，避免定时任务打扰面）。降级本身 = 条件更新天然幂等。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DemandRelayScheduler {

    private final DemandRelayService demandRelayService;

    /** 每 5 分钟（fixedDelay = 上次结束起 5 分钟；单实例部署无并发） */
    @Scheduled(fixedDelay = 300_000)
    public void autoReleaseOverdueDemands() {
        try {
            int handled = demandRelayService.autoRelease();
            if (handled > 0) {
                log.info("邀约中转定时降级完成：处理 {} 条超时邀约", handled);
            }
        } catch (Exception e) {
            log.error("邀约中转定时降级失败", e);
        }
    }
}
