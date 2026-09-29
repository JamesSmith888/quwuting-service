package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * 门店到访统计（admin 详情卡 / 列表列的唯一口径载体，2026-09-29 V33）。
 * <p>
 * 口径必须随数值一起下发：admin 展示的每个数字旁边都要能说出它的半径与时间窗
 * （管理端纪律：统计量必须带口径说明，否则运营会当承诺值讲）。所有字段
 * {@code ALWAYS} 序列化——「无记录 = 0 / null」与「字段缺失」是两件事，
 * non_null 全局策略会把 null 静默删掉，前端拿到 undefined 会算出 NaN
 * （35 号文档实证过的坑）。
 *
 * @param visitUsers7d    近 7 天到访人数（去重 UV；命中口径：distance ≤ hitRadiusM
 *                        且 accuracy 达标）
 * @param visitUsers30d   近 30 天到访人数（同上口径，时间窗放大）
 * @param nearbyUsers30d  近 30 天附近人数（片区覆盖度：distance ≤ nearbyRadiusM，
 *                        同一精度门槛；语义 = 「这一带出现过多少用户」，非实时在场）
 * @param lastPresenceAt  最近一条到访痕迹时刻；null = 从无记录
 * @param hitRadiusM      到访命中半径（米，服务端权威，供展示口径）
 * @param nearbyRadiusM   附近覆盖半径（米，服务端权威，供展示口径）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VenuePresenceStats(
        long visitUsers7d,
        long visitUsers30d,
        long nearbyUsers30d,
        LocalDateTime lastPresenceAt,
        int hitRadiusM,
        int nearbyRadiusM) {
}
