package org.quwuting.quwutingservice.venuefeedback.enums;

/**
 * 场所信息纠错反馈类型。
 * <p>
 * 用户在详情页发现场所状态可能过时时，选择具体问题类型提交反馈，
 * 管理员在管理端查看并处理。
 */
public enum FeedbackType {
    CLOSED_DOWN("门店已关门/停业"),
    SUSPENDED("门店暂停营业"),
    INACCURATE("信息有误"),
    OTHER("其他问题");

    private final String displayName;

    FeedbackType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
