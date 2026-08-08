package org.quwuting.quwutingservice.venuereaction.dto.response;

/**
 * 列表卡片展示的 Reaction 徽标（Top N，按所选统计窗口热度排序；
 * 当前用户已参与的 code 不受 Top N 截断，见 {@code VenueReactionService#buildTopBadgesFromCounts}）。
 * <p>
 * 含 reactedByMe（个人状态）——刻意打破"列表层不含个人状态"的惯例，
 * 因为列表页 Reaction 明确要求"点击即知是否已参与"（产品规则）。
 * 该字段来自单独的、不缓存的实时批量查询，不与场所级聚合缓存混在一起
 * （聚合计数仍然缓存共享，个人状态永远实时查询——与既有的"缓存内容强制约束"一致）。
 * <p>
 * 三窗口计数语义（2026-08 每日一记模型确立）：徽标同时携带 {@code countAll} / {@code count7d} /
 * {@code count30d} 三个窗口的计数。根因：每日一记模型下取消只可能作用于**当日**记录，
 * 因此一次 toggle 对三个窗口计数的本地 ±1 **全部精确**（新增的今日记录必落在所有窗口内，
 * 取消删掉的也必是今日记录）——前端乐观更新（Telegram 式：点击立即 ±1，失败回滚）不再需要
 * 旧模型的"展示 countAll、排序 count30d"双计数 hack，列表页时间筛选（近7天/近30天/全部）
 * 直接展示对应窗口计数，切换窗口仅重排序/过滤本地数据。见 AGENTS.md「Reaction 快速反馈系统」。
 *
 * @param code        Reaction 代码
 * @param emoji       表情符号
 * @param label       文字说明（长按展示）
 * @param countAll    全部历史记录数（"全部"窗口展示值）
 * @param count7d     近7天计数（"近7天"窗口展示值，列表默认窗口）
 * @param count30d    近30天计数（"近30天"窗口展示值）
 * @param reactedByMe 当前用户今日是否已参与（未登录恒为 false）
 */
public record ReactionBadge(
        String code,
        String emoji,
        String label,
        long countAll,
        long count7d,
        long count30d,
        boolean reactedByMe
) {}
