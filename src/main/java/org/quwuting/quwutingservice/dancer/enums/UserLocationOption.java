package org.quwuting.quwutingservice.dancer.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 用户位置表态（2026-08-26 舞伴「加好友需告知位置」，qwt_demand_records.user_location）。
 * <p>
 * 语义：开启「加好友需告知位置」（qwt_dancers.require_user_location）的舞伴，用户获取
 * 联系方式前须二选一表态——<b>同城</b> 或 <b>非同城·自行前往</b>。设计原则：
 * <ul>
 *   <li><b>相对关系而非真实地址</b>：不收集坐标/区划/门牌（平台无舞伴精确位置，
 *       也不维护区划数据），只需用户确认能否到达——与舞伴服务范围 location_scope
 *       （附近X公里）叙事闭环；</li>
 *   <li><b>只存枚举 code 不存自由文本</b>（隐私克制 + 合规安全——「自行前往」=
 *       用户主动到服务地点，绝无「上门/接送」暗示）；</li>
 *   <li>消息拼接用 display 文案（后端权威下发，同 DemandDuration 模式）。</li>
 * </ul>
 */
public enum UserLocationOption {

    /** 同城 */
    SAME_CITY("同城"),
    /** 非同城 · 自行前往（用户主动到服务地点） */
    WILL_TRAVEL("自行前往");

    private final String display;

    UserLocationOption(String display) {
        this.display = display;
    }

    /** 展示/消息拼接文案（需求弹层 chip 消息段 + 需求描述） */
    public String display() {
        return display;
    }

    /** 解析位置表态代码（非法值 → 1001「无效的位置选项」） */
    public static UserLocationOption parse(String code) {
        try {
            return UserLocationOption.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(1001, "无效的位置选项");
        }
    }
}
