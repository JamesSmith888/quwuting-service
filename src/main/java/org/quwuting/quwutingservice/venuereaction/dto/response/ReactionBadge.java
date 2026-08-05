package org.quwuting.quwutingservice.venuereaction.dto.response;

/**
 * 列表卡片展示的 Reaction 徽标（Top N，按近30天热度排序）。
 * <p>
 * 含 reactedByMe（个人状态）——刻意打破"列表层不含个人状态"的惯例，
 * 因为列表页 Reaction 明确要求"点击即知是否已参与"（产品规则）。
 * 该字段来自单独的、不缓存的实时批量查询，不与场所级聚合缓存混在一起
 * （聚合计数仍然缓存共享，个人状态永远实时查询——与既有的"缓存内容强制约束"一致）。
 *
 * @param code        Reaction 代码
 * @param emoji       表情符号（卡片只展示 emoji，不展示文字）
 * @param label       文字说明（长按展示）
 * @param count       近30天计数
 * @param reactedByMe 当前用户是否已参与（未登录恒为 false）
 */
public record ReactionBadge(
        String code,
        String emoji,
        String label,
        long count,
        boolean reactedByMe
) {}
