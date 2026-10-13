package org.quwuting.quwutingservice.venuepresence.dto.response;

/**
 * 到店足迹感谢 · 状态（GET /venues/{venueId}/visit-thanks 的响应，2026-10-13 V50）。
 * <p>
 * 供 C 侧「到店足迹」浮层驱动感谢按钮的显隐与文案；<b>不含任何名单/身份信息</b>
 * （52 号「永不公开谁到过」裁决——前端只拿得到两个数字，服务端从设计上就没有可泄露面）。
 *
 * @param contributors  <b>可被感谢</b>的到访贡献者数 = 该店附近 30 天足迹并集去掉自己
 *                      （与卡片「感谢 N 位舞友」同一份证据与窗口，差集 = 去掉感谢者本人；
 *                      0 = 我是唯一贡献者，按钮隐藏）
 * @param pendingThanks 其中我<b>尚未感谢过</b>的人数（0 = 全部已感谢过，按钮转「已感谢」态）
 */
public record VisitThanksStatusResponse(int contributors, int pendingThanks) {
}
