package org.quwuting.quwutingservice.recruitment.enums;

/**
 * 招工职位类型（受控枚举，管理员只能勾选不能手写——防灰色职位变体）。
 * label 为服务端权威展示文案，前端零拼接。
 */
public enum RecruitPosition {

    PARTNER_DANCE("舞伴"),
    LEAD_DANCE("领舞"),
    DANCE_TEACHER("舞蹈老师"),
    DJ("DJ"),
    WAITER("服务员"),
    CASHIER("收银"),
    CLEANER("保洁"),
    OTHER("其他");

    private final String label;

    RecruitPosition(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
