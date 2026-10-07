package org.quwuting.quwutingservice.venuecrowd.stat;

import java.time.LocalDateTime;

/**
 * 一张「票」：某个用户在某一时刻对某个档位维度（女 / 男）的一次上报（2026-10-07）。
 *
 * @param userId      投票者
 * @param level       档位 1-8（有序量表，相邻档约差 1.5 倍）
 * @param trustWeight 该用户的可信度权重（原始值，≥1.0；统计时是否生效、是否封顶见 {@link CrowdConsensus}）
 * @param at          上报时刻（JVM 北京时间，同表 created_at 口径）
 * @param sourceId    来源上报行 id（确认积分的幂等键；聚合出来的「代表票」取其代表行）
 */
public record CrowdVote(long userId, int level, double trustWeight, LocalDateTime at, long sourceId) {
}
