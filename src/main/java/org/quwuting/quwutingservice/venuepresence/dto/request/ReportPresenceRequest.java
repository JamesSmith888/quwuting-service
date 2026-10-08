package org.quwuting.quwutingservice.venuepresence.dto.request;

/**
 * 到访痕迹上报请求体（POST /venues/{venueId}/presence，2026-09-29 V33；2026-10-08 V46 增坐标）。
 * <p>
 * 2026-10-08 V46 修订：坐标随行（端侧本次定位快照，gcj02）。原「用户经纬度在协议上
 * 不存在」的隐私红线按用户裁决改为「主动同意 + 用途限定」形态——采集门禁不变
 * （仅显式同意用户），用途限定 = admin 内部统计与分析，同意文案四处已同批改真；
 * 完整说明见 V46 迁移头注与 docs/agents/52-venue-presence.md。
 * <p>
 * 坐标两值**成对出现**：旧端不带（null）照常受理（协议向后兼容）；只带一半 = 1022。
 *
 * @param distanceMeters 用户到该店的距离（米，来自 GET /venues/nearby 服务端
 *                       Haversine 结果，端侧原样回传；服务端限幅校验）
 * @param accuracyMeters 端侧定位精度（米，wx.getLocation accuracy；可空 = 端侧未提供）
 * @param latitude       端侧定位快照纬度（gcj02；可空 = 旧端 / 历史语义）
 * @param longitude      端侧定位快照经度（gcj02；可空 = 旧端 / 历史语义）
 */
public record ReportPresenceRequest(Integer distanceMeters, Integer accuracyMeters,
                                    Double latitude, Double longitude) {
}
