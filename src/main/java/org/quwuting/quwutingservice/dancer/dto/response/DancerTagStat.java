package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴标签聚合项（标签云：标签来源 = 用户认可行为）。
 * tag 为 {@code DancerTagCode} 枚举名，emoji/label 由字典渲染（后端唯一事实源）。
 */
public record DancerTagStat(
        String tag,
        String emoji,
        String label,
        long count
) {}
