package org.quwuting.quwutingservice.venue.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 场所列表排序方式（GET /venues?sort=...）。
 * <p>
 * 排序必须由服务端在库内完成（分页正确性要求排序与分页同一查询，见 AGENTS.md「复合评分排序」），
 * 前端只传语义 code，不参与排序计算。
 * <ul>
 *   <li>RECOMMENDED（推荐排序，默认）：复合评分 = 运营权重 + 热度（收藏×20 + 动态×10）+ 邻近加成
 *       100/(1+距离km)。有定位时本地场所自然置顶，无定位时退化为权重 + 热度。</li>
 *   <li>DISTANCE（距离最近）：纯距离升序（Haversine），仅展示有坐标的场所；无定位时由 Service
 *       降级为推荐排序（见 VenueService.listVenues 分流注释）。</li>
 *   <li>HEAT（热度最高）：复合评分去掉距离项（运营权重 + 热度），与「热门场所标记」同口径——
 *       热度是场所属性，不随请求者位置变化。</li>
 *   <li>NEWEST（最新收录）：按创建时间倒序（新增场所优先露出）。</li>
 * </ul>
 * 非法值抛业务异常（与 {@link org.quwuting.quwutingservice.venuereaction.ReactionWindow#from}
 * 的防御风格一致，400 而非静默降级）。
 */
public enum VenueSortMode {
    RECOMMENDED("recommended"),
    DISTANCE("distance"),
    HEAT("heat"),
    NEWEST("newest");

    private final String code;

    VenueSortMode(String code) {
        this.code = code;
    }

    public String getCode() {
        return code;
    }

    /** 解析请求参数（"recommended"/"distance"/"heat"/"newest"）。null/空白 → 默认 RECOMMENDED。 */
    public static VenueSortMode from(String code) {
        if (code == null || code.isBlank()) {
            return RECOMMENDED;
        }
        for (VenueSortMode mode : values()) {
            if (mode.code.equals(code)) {
                return mode;
            }
        }
        throw new BusinessException(1009, "无效的排序方式");
    }
}
