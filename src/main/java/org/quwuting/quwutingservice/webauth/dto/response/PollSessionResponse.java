package org.quwuting.quwutingservice.webauth.dto.response;

/**
 * 轮询扫码登录会话状态（GET /web-auth/sessions/{sessionId}）。
 * <p>
 * 状态语义：
 * <ul>
 *   <li>PENDING   — 等待用户扫码确认（前端继续轮询）</li>
 *   <li>CONFIRMED — 已确认；本次响应携带 token（一次性，取走后后端置空）</li>
 *   <li>REJECTED  — 用户拒绝，前端停止轮询并提示</li>
 *   <li>EXPIRED   — 会话超时，前端提示重新扫码</li>
 * </ul>
 */
public record PollSessionResponse(
        String status,
        String token,
        Long userId,
        String nickname
) {}
