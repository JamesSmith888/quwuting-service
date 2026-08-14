package org.quwuting.quwutingservice.points.enums;

/**
 * 积分门槛目标类型（可扩展：未来任何"内容点"需支持"自由设置是否需要积分"
 * 时，只需加枚举值，无需改表——公共模块，见 AGENTS.md「积分系统 · 积分解锁」）。
 * <p>
 * 与 {@link PointsTargetType}（赠送/收到积分的聚合维度：门店/舞伴）是<b>两套枚举</b>：
 * 前者是"积分花到哪个内容点上"（照片/联系方式等细粒度目标），后者是"积分送给
 * 谁/谁收到"（舞厅/舞伴级目标）。解锁消耗的积分<b>不转移</b>（单向燃烧），
 * 故解锁流水只挂 PointsGateTargetType，不参与 receivedTotal 聚合。
 */
public enum PointsGateTargetType {
    /** 舞伴相册照片（qwt_dancer_photos.id） */
    DANCER_PHOTO,
    /** 舞伴联系方式（qwt_dancers.id——联系方式是舞伴实体的字段，门槛挂在舞伴上） */
    DANCER_CONTACT
}
