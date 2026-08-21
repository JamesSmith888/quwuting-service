package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;

/**
 * 舞伴「用户解锁信息」统计项（2026-08-21 舞伴统计图追加）。
 * <p>
 * 语义 = "有多少用户愿意为 TA 的内容花积分"（社会证明，见 PointsService#unlock
 * 注释）。数据源 = {@code qwt_points_unlocks}（一人一目标只扣一次费，永久解锁）；
 * 按内容类型（照片/联系方式，可扩展）聚合，非时间序列——解锁是低频离散事件，
 * 折线趋势语义弱，前端以横向条形图做分类对比。
 * <p>
 * 口径：
 * <ul>
 *   <li>{@code unlockCount} = 累计解锁<b>人次</b>（行数；照片 = 每张照片一个
 *       target_id，同一用户解锁多张照片计多次行为）；</li>
 *   <li>{@code uniqueUsers} = 累计解锁<b>人数</b>（按 user_id 去重，表达覆盖用户）；</li>
 *   <li>{@code cost} = 该类内容<b>当前</b>门槛积分（{@code qwt_points_gates} 未软删
 *       且 cost&gt;0 时的最大值；无有效门槛 = 0——历史解锁过但已免门槛仍保留计数）。</li>
 * </ul>
 * label 由后端枚举映射下发（新增内容类型免前端改动，仅后端加枚举值 + 本映射项）。
 */
public record DancerUnlockStat(
        /** 内容类型（DANCER_PHOTO / DANCER_CONTACT，可扩展） */
        PointsGateTargetType targetType,
        /** 展示名（后端权威："照片" / "联系方式"；未知类型回退枚举名） */
        String label,
        /** 累计解锁人次（qwt_points_unlocks 行数） */
        long unlockCount,
        /** 累计解锁人数（按 user_id 去重） */
        long uniqueUsers,
        /** 当前门槛积分（无有效门槛 = 0） */
        int cost
) {
}
