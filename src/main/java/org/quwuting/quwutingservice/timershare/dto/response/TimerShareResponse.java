package org.quwuting.quwutingservice.timershare.dto.response;

/**
 * 创建 / 刷新分享会话的响应（2026-10-07，V42）。
 *
 * @param token        二维码凭据（主持方端用它拼码图地址、轮询状态、关闭）
 * @param qrPath       码图相对路径 {@code /timer-shares/{token}/wxacode.jpg}（image 直连，由前端拼 API_BASE_URL；
 *                     服务端不返回绝对地址——API 域名是部署配置，客户端已有唯一事实源）
 * @param expiresAtMs  二维码失效时刻（服务端时钟 epoch ms）
 * @param serverNowMs  服务端当前时刻：前端据此校准本机与服务端的时钟偏移（用于判断「还剩多久失效」）
 * @param joinCount    已加入人数（刷新同一场时可能 &gt; 0）
 * @param maxJoins     人数上限
 */
public record TimerShareResponse(
        String token,
        String qrPath,
        long expiresAtMs,
        long serverNowMs,
        int joinCount,
        int maxJoins) {
}
