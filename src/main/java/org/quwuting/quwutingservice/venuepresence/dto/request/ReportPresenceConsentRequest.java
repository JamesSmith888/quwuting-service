package org.quwuting.quwutingservice.venuepresence.dto.request;

/**
 * 到店足迹开关状态上报请求体（POST /venues/presence-consent，2026-09-29 四轮 V34）。
 * 「我的-设置-到店足迹」拨动开关时 fire-and-forget 上报（失败静默不重试）。
 *
 * @param enabled 确立后的开关状态（true = 采集开启）
 */
public record ReportPresenceConsentRequest(Boolean enabled) {
}
