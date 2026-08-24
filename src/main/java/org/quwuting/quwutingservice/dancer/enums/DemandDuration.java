package org.quwuting.quwutingservice.dancer.enums;

import org.quwuting.quwutingservice.exception.BusinessException;

/**
 * 联系方式需求时长（2026-08-24 需求确认弹层「时长（可选）」选项，
 * qwt_demand_records.duration）。时长 = 最强"非口嗨"承诺信号（隐含预算），可选填。
 * 只存枚举 code 不存自由文本；消息拼接用 display 文案（后端权威下发）。
 */
public enum DemandDuration {

    /** 1小时 */
    ONE_HOUR("1小时"),
    /** 2小时 */
    TWO_HOURS("2小时"),
    /** 3小时 */
    THREE_HOURS("3小时"),
    /** 4小时以上 */
    FOUR_PLUS("4小时以上");

    private final String display;

    DemandDuration(String display) {
        this.display = display;
    }

    /** 展示文案（需求弹层 chip / 消息拼接） */
    public String display() {
        return display;
    }

    /** 解析时长代码（非法值 → 1001「无效的时长选项」） */
    public static DemandDuration parse(String code) {
        try {
            return DemandDuration.valueOf(code);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(1001, "无效的时长选项");
        }
    }
}
