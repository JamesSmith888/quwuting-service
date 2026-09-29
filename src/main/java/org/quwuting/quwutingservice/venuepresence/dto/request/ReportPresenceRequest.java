package org.quwuting.quwutingservice.venuepresence.dto.request;

/**
 * 到访痕迹上报请求体（POST /venues/{venueId}/presence，2026-09-29 V33）。
 * <p>
 * 隐私红线：<b>只有三个标量</b>——门店 id 在路径上，body 只带距离与精度。
 * 用户经纬度在本接口没有承载字段（不是"暂不上传"，是协议上不存在），
 * 见 docs/agents/52-venue-presence.md §「隐私红线」。
 *
 * @param distanceMeters 用户到该店的距离（米，来自 GET /venues/nearby 服务端
 *                       Haversine 结果，端侧原样回传；服务端限幅校验）
 * @param accuracyMeters 端侧定位精度（米，wx.getLocation accuracy；可空 = 端侧未提供）
 */
public record ReportPresenceRequest(Integer distanceMeters, Integer accuracyMeters) {
}
