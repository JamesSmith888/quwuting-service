package org.quwuting.quwutingservice.dancer.dto.response;

/**
 * 舞伴标签聚合项（认可 chip：标签来源 = 用户认可行为）。
 * tag 为 {@code DancerTagCode} 枚举名，emoji/label 由字典渲染（后端唯一事实源）。
 * <p>
 * 2026-08-15 窗口化：count = countAll（全量，兼容旧消费方：列表 topTags），
 * 另带 countToday/count7d/count30d——详情页认可 chip 默认展示近7天、可切换
 * 近30天/全部（对齐 Reaction 四窗口统计口径，窗口锚点 = createdAt "此刻"）。
 */
public record DancerTagStat(
        String tag,
        String emoji,
        String label,
        long count,
        long countToday,
        long count7d,
        long count30d
) {}
