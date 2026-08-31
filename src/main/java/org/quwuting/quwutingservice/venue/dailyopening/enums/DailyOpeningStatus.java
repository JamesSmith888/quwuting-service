package org.quwuting.quwutingservice.venue.dailyopening.enums;

/**
 * 信息源对门店当日的营业状态判断（快照语义，与 venue.status 持久态解耦）。
 */
public enum DailyOpeningStatus {
    OPEN("今日营业"),
    CLOSED("今日休息");

    private final String displayName;

    DailyOpeningStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
