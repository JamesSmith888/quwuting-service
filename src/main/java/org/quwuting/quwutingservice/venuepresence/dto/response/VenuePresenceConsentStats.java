package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 到店足迹开关统计（GET /admin/venues/presence-consent-stats，2026-09-29 四轮 V34）。
 * 口径说明：当前态 = 每用户**最新一条** consent 行；「默认开启」= 最新态仍是
 * DEFAULT 来源的用户（从未手动改过设置）。全字段 ALWAYS 序列化（non_null
 * 全局策略会删 null/0 字段，35 号教训）。
 *
 * @param enabledUsers  当前采集开启的去重用户数（含默认态确立者）
 * @param disabledUsers 当前采集关闭的去重用户数
 * @param defaultUsers  其中「从未手动改过设置」的默认开启人数（最新态 source=DEFAULT）
 * @param changes30d    近 30 天用户手动变更次数（USER 来源行数，开关热度趋势分子）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VenuePresenceConsentStats(
        long enabledUsers,
        long disabledUsers,
        long defaultUsers,
        long changes30d) {
}
