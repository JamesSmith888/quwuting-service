package org.quwuting.quwutingservice.groupchat.enums;

/**
 * 舞友群维度（V33 新增）。
 * <p>
 * 决定群聊的适用地域范围，city / region 字段按维度互斥必填（服务端校验）：
 * <ul>
 *   <li>{@link #NATIONWIDE} 全国群——面向所有用户，不填 city / region；</li>
 *   <li>{@link #CITY} 城市群——绑定标准行政区划名（picker region 词表，与
 *       舞厅城市筛选 / 舞伴城市子表同一词表），city 必填；</li>
 *   <li>{@link #REGION} 地域群——运营自定义地域名（如「长三角」「珠三角」），
 *       region 必填。</li>
 * </ul>
 * 枚举类列禁 CHECK 约束（扩维度免迁移），非法值由请求 DTO 校验拦截。
 */
public enum GroupChatScope {

    /** 全国群 */
    NATIONWIDE("全国"),
    /** 城市群 */
    CITY("城市"),
    /** 地域群 */
    REGION("地域");

    private final String displayName;

    GroupChatScope(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
