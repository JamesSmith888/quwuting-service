package org.quwuting.quwutingservice.taginteraction;

import java.util.List;

/**
 * 系统评分维度定义。
 * <p>
 * 评分维度是标准化的体验评估轴，跨所有舞厅一致（便于横向比较），
 * 与管理员添加的描述性标签（tags）相互独立。
 * 所有舞厅详情页均展示这些维度的评分入口，不依赖管理员是否添加了对应标签。
 * <p>
 * 维度分两类：
 * <ul>
 *   <li>体验评估（服务、环境、音响效果、性价比）：主观质量打分，锚定"最差/最好"</li>
 *   <li>现场状况（舞伴氛围、客流热度、舞伴年龄层）：众包实时体感上报，锚定文案各异，
 *       时效性强，近 7 天 / 30 天窗口均分更有参考价值</li>
 * </ul>
 * <p>
 * 后续新增维度只需在此列表追加，前端通过 tag-stats 接口的 dimensions 字段自动同步。
 */
public final class RatingDimensions {

    private RatingDimensions() {}

    /** 当前系统支持的评分维度（有序，前端按此顺序展示） */
    public static final List<String> ALL = List.of(
            "服务", "环境", "音响效果", "性价比",
            "舞伴氛围", "客流热度", "舞伴年龄层"
    );

    /** 体验评估维度（用于满意度计算）——排除"现场状况"类维度（实时体感非质量评价） */
    public static final List<String> QUALITY_DIMENSIONS = List.of("服务", "环境", "音响效果", "性价比");

    /** 判断给定名称是否为合法的评分维度 */
    public static boolean isValid(String dimension) {
        return dimension != null && ALL.contains(dimension);
    }

    /** 判断给定维度是否属于体验评估类（参与满意度计算） */
    public static boolean isQualityDimension(String dimension) {
        return dimension != null && QUALITY_DIMENSIONS.contains(dimension);
    }
}
