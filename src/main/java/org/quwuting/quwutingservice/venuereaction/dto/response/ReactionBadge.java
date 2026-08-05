package org.quwuting.quwutingservice.venuereaction.dto.response;

/**
 * 列表卡片展示的 Reaction 徽标（Top N，按近30天热度排序）。
 * <p>
 * 含 reactedByMe（个人状态）——刻意打破"列表层不含个人状态"的惯例，
 * 因为列表页 Reaction 明确要求"点击即知是否已参与"（产品规则）。
 * 该字段来自单独的、不缓存的实时批量查询，不与场所级聚合缓存混在一起
 * （聚合计数仍然缓存共享，个人状态永远实时查询——与既有的"缓存内容强制约束"一致）。
 * <p>
 * 双计数语义（2026-08 确立）：排序/筛选仍以 {@code count30d}（近30天热度信号）为准，
 * 前端展示的是 {@code countAll}（全部生效记录数 = "总数量"）——选择 countAll 展示的根因：
 * 前端乐观更新（Telegram 式：点击立即 +1，失败回滚）下，countAll 的本地 ±1 恒精确
 * （生效记录数随 toggle 确定性增减），而 count30d 的 ±1 依赖该记录的 createdAt 是否落在
 * 30 天窗口内、无法本地精确推导。见 AGENTS.md「Reaction 快速反馈系统」章节。
 *
 * @param code        Reaction 代码
 * @param emoji       表情符号
 * @param label       文字说明（长按展示）
 * @param count30d    近30天计数（徽标排序依据，热度信号）
 * @param countAll    全部历史生效计数（"总数量"展示依据，乐观更新本地 ±1 恒精确）
 * @param reactedByMe 当前用户是否已参与（未登录恒为 false）
 */
public record ReactionBadge(
        String code,
        String emoji,
        String label,
        long count30d,
        long countAll,
        boolean reactedByMe
) {}
