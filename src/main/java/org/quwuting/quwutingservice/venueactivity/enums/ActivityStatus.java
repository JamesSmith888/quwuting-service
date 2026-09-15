package org.quwuting.quwutingservice.venueactivity.enums;

import lombok.Getter;

/**
 * 活动生命周期状态（权威在后端）。
 * <p>
 * 状态机：{@code DRAFT →（publish）→ PUBLISHED →（有效期结束由 30s 调度强转 /
 * 运营手动下线）→ OFFLINE →（重新 publish，唯一复活通道）→ PUBLISHED}。
 * <p>
 * 与公告域同一心智（见 34 号文档「失效机制」）：用户端可见性 = {@code PUBLISHED}，
 * **不做查询时过滤**，保持"单点状态机"原则——否则过期活动会在不同查询路径上
 * 表现不一致。
 * <p>
 * 到期自动下线由 outer 调度的有效期驱动（{@code ALWAYS} 类型不参与，长期有效）。
 */
@Getter
public enum ActivityStatus {

    DRAFT("草稿"),
    PUBLISHED("进行中"),
    OFFLINE("已下线");

    private final String displayName;

    ActivityStatus(String displayName) {
        this.displayName = displayName;
    }

    public boolean isPublished() {
        return this == PUBLISHED;
    }
}
