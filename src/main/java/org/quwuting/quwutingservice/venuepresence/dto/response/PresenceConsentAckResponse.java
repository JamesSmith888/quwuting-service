package org.quwuting.quwutingservice.venuepresence.dto.response;

/**
 * 开关状态上报回执（POST /venues/presence-consent，2026-09-29 四轮 V34）。
 * 客户端 fire-and-forget，回执仅供联调确认。
 *
 * @param recorded true = 已写入流水
 */
public record PresenceConsentAckResponse(boolean recorded) {
}
