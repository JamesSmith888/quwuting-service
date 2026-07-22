package org.quwuting.quwutingservice.user.enums;

import lombok.Getter;

@Getter
public enum UserRole {

    ADMIN("超级管理员"),
    USER("普通用户");

    private final String displayName;

    UserRole(String displayName) {
        this.displayName = displayName;
    }
}
