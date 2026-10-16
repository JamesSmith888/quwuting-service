package org.quwuting.quwutingservice.venuepresence.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 门店详情页「到店足迹」行的数据（GET /venues/{venueId}/visit-footprint，2026-10-16）。
 * <p>
 * <b>为什么是独立接口、而不是塞进门店详情响应</b>：详情响应是缓存体（服务端聚合缓存 + 小程序会话内
 * 详情缓存 + SWR 持久缓存，三层都以门店 id 为键），而本数据由<b>30 天滚动窗实时计算</b>且带
 * <b>浏览者维度</b>（{@code mineText}）——塞进去，要么把「我的足迹」写进共享缓存（串人），
 * 要么让整个详情响应因它失去可缓存性。故独立取数、不进首屏关键路径，与今晚热度同形态。
 * 列表走的是另一条路（{@code VenueResponse#visitBadgeText}，列表本就按页实时装配）。
 * <p>
 * <b>两句话都是服务端权威文案</b>（前端禁自拼——同 {@code VisitThanksResponse#feedbackText}）：
 * 渲染函数在 {@code VenueVisitBadgeService}（{@code badgeTextOf} / {@code renderMineText}），
 * 与列表卡片、admin 回显同源。
 * <p>
 * <b>接口面即隐私面</b>：只有两个字符串。{@code mineText} 只陈述调用者<b>自己</b>的痕迹与
 * 「有几位舞友感谢了你」的计数，不含任何其他人的身份或明细（52 号「永不公开谁到过」裁决）。
 * 全字段 {@code ALWAYS} 序列化——null 与「字段缺失」对前端是两件事（35 号教训，同 VenuePresenceStats）。
 *
 * @param badgeText 与列表卡片逐字相同的足迹行文案（「感谢 N 位舞友 · N 次到过这附近的足迹」等）；
 *                  null = 附近 30 天无足迹，前端整行不渲染
 * @param mineText  调用者自己的足迹句（「你在这附近留下过 N 次足迹 · N 位舞友感谢了你」）；
 *                  null = 未登录 / 不在被计入的贡献者之列。<b>非 null ⇒ badgeText 必非 null</b>
 *                  （我在被计入的人里，则卡片人数 ≥ 1）
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VenueVisitFootprintResponse(String badgeText, String mineText) {
}
