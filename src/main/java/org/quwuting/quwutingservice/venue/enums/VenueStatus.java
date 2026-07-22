package org.quwuting.quwutingservice.venue.enums;

public enum VenueStatus {
    OPEN("营业中"),
    RENOVATING("装修中"),
    CLOSED("休息中"),
    SUSPENDED("暂停营业"),
    CEASED("已停业");

    private final String displayName;

    VenueStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
