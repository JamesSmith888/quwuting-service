package org.quwuting.quwutingservice.webauth.dto.response;

/**
 * 创建扫码登录会话响应（POST /web-auth/sessions）。
 *
 * @param sessionId        会话 ID（网页轮询 GET /web-auth/sessions/{id} 使用）
 * @param qrImageDataUrl   小程序码图片（data:image/png;base64,…，前端直接 img src）
 * @param expiresInSeconds 会话 TTL（秒），过期后需重新生成
 */
public record CreateSessionResponse(
        String sessionId,
        String qrImageDataUrl,
        int expiresInSeconds
) {}
