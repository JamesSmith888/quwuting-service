package org.quwuting.quwutingservice.taginteraction;

import java.util.List;

/**
 * 系统评分维度定义。
 * <p>
 * 评分维度是标准化的体验评估轴，跨所有舞厅一致（便于横向比较、计算综合评分），
 * 与管理员添加的描述性标签（tags）相互独立。
 * 所有舞厅详情页均展示这些维度的评分入口，不依赖管理员是否添加了对应标签。
 * <p>
 * 后续新增维度只需在此列表追加，前端通过 tags/stats 接口的 dimensions 字段自动同步。
 * <p>
 * <b>历史沿革</b>：早期本列表还包含"现场状况"三个维度（舞伴氛围、客流热度、舞伴年龄层），
 * 用众包 1-10 打分表达实时体感。Reaction 快速反馈系统上线后（见
 * {@link org.quwuting.quwutingservice.venuereaction.ReactionCode}），这三个维度与新增的
 * Reaction（👧年轻舞伴多/👴舞伴年龄偏成熟/🔥人气旺等）语义重叠，产生"标签、评分、热度模块
 * 信息混杂"的问题——用户需要在两套交互（1-10 打分 vs 一键表情）中重复表达同一件事。
 * 故将这三个维度从评分体系中移除，改由 Reaction 承载（点击成本更低，更适合舞厅这类
 * 强时效场景）。历史评分数据（tag 为旧维度名的 qwt_tag_interactions 行）保留在库中作为
 * legacy 数据，不再被 {@link #isValid} 承认、不参与任何聚合计算，与 avatar_url 等历史遗留
 * 字段同处理原则（见 AGENTS.md「用户资料」章节）。
 */
public final class RatingDimensions {

    private RatingDimensions() {}

    /** 当前系统支持的评分维度（有序，前端按此顺序展示），均为体验评估类，全部参与综合评分计算 */
    public static final List<String> ALL = List.of("服务", "环境", "音响效果", "性价比");

    /** 判断给定名称是否为合法的评分维度 */
    public static boolean isValid(String dimension) {
        return dimension != null && ALL.contains(dimension);
    }
}
