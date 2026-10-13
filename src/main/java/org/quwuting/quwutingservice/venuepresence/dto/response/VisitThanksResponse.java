package org.quwuting.quwutingservice.venuepresence.dto.response;

/**
 * 到店足迹感谢 · 提交结果（POST /venues/{venueId}/visit-thanks 的响应，2026-10-13 V50）。
 * <p>
 * <b>为什么反馈文案由服务端下发</b>：与 27 号 crowdNoticeText / rewardText 同款纪律——
 * 用户可见文案的口径单点在后端（改口不发版、前端零拼接）；且「0 人」与「N 人」两种分态
 * 文案的判定逻辑与幂等结果同源，不应让前端从数字二次推导。
 *
 * @param thankedPeople <b>本次新增</b>感谢的贡献者数（幂等：已感谢过的对不重复计）
 * @param pendingThanks 本次之后仍未感谢的人数（前端据此回写按钮态）
 * @param contributors  可被感谢的贡献者总数（同 {@link VisitThanksStatusResponse#contributors()}）
 * @param feedbackText  提交反馈文案（服务端权威，如「已感谢 4 位舞友」/「已经感谢过这里的舞友」）
 */
public record VisitThanksResponse(int thankedPeople, int pendingThanks, int contributors, String feedbackText) {
}
