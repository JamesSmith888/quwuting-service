package org.quwuting.quwutingservice.points.enums;

/**
 * 积分赠送目标类型（可扩展：未来新增目标只需加枚举，无需改表）。
 * <p>
 * 赠送流水（delta &lt; 0）必带 target_type + target_id；"收到积分"统计 =
 * 按 (target_type, target_id, created_at) 聚合 SUM(-delta)，由
 * qwt_idx_pts_tx_target 索引支撑（热度公式积分项 / 趋势序列同源）。
 */
public enum PointsTargetType {
    /** 门店（qwt_venues.id） */
    VENUE,
    /** 舞伴（qwt_dancers.id） */
    DANCER
}
