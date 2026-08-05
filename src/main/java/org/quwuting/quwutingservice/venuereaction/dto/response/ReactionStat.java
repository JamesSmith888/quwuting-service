package org.quwuting.quwutingservice.venuereaction.dto.response;

/**
 * 详情页单个 Reaction 的完整统计（含四个时间窗口）。
 * <p>
 * 时间窗口不做周期性清零，而是永久保留原始记录、按窗口实时统计——
 * "今日/7天"反映实时状态（今晚气氛如何），"30天/全部"反映长期画像（这家店整体如何），
 * 见 AGENTS.md「Reaction 时效性设计」根因说明。
 *
 * @param countToday  今日计数（自然日）
 * @param count7d     近7天计数
 * @param count30d    近30天计数
 * @param countAll    全部历史计数（当前生效记录，不含已取消）
 * @param reactedByMe 当前用户是否已参与（未登录恒为 false）
 */
public record ReactionStat(
        String code,
        String emoji,
        String label,
        long countToday,
        long count7d,
        long count30d,
        long countAll,
        boolean reactedByMe
) {}
