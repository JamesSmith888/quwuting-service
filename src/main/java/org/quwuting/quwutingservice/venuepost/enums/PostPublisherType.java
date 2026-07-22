package org.quwuting.quwutingservice.venuepost.enums;

import lombok.Getter;

/**
 * 动态发布方类型。
 * <p>
 * OWNER — 门店认领人（商家）发布的公告，如招聘、活动预告；
 * ADMIN — 平台管理员发布的通知，如规范提醒、处罚公告。
 */
@Getter
public enum PostPublisherType {

    OWNER("商家"),
    ADMIN("平台");

    private final String displayName;

    PostPublisherType(String displayName) {
        this.displayName = displayName;
    }
}
