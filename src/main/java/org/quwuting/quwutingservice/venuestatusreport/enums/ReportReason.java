package org.quwuting.quwutingservice.venuestatusreport.enums;

/**
 * 用户上报的暂停原因。
 * <p>
 * 用户在详情页极速上报或补充详情时选择，用于帮助其他用户和管理员理解暂停原因。
 * 命名使用事实描述，避免"警察""扫黄"等敏感词，确保微信审核安全。
 */
public enum ReportReason {
    CHECK("门店检查"),
    UNKNOWN("情况不明"),
    CLEARED("清场");

    private final String displayName;

    ReportReason(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
