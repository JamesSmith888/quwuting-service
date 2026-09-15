package org.quwuting.quwutingservice.venueactivity.enums;

import lombok.Getter;

/**
 * 活动的**当前态**（派生结果，不落库）—— 用户端唯一消费的状态字面量。
 * <p>
 * 派生权威在后端（照抄公告域 {@code AnnouncementService#isUnread} 的既有纪律）：
 * 前端只按 {@code stateKey} 做展示分支，**不允许自己拿 windows 再算一遍**。
 * 理由：策略逻辑一旦两端各写一份必然漂移（项目已有 {@code check:protocol} 门禁
 * 之痛，不再新增一处）；且前端零业务分支是 WXML 的硬约束。
 * <p>
 * 每态同时下发 {@code nextChangeAt}（绝对时间戳），前端的"还有 25 分钟"就是
 * 一次纯减法——绝对时刻不依赖"服务端此刻"的缓存新鲜度，缓存过期也不会算错。
 * <p>
 * 「活动已彻底结束」（有效期已过）**不在此枚举内**：这类活动由 30s 调度强转
 * OFFLINE，用户端根本查不到——状态机单点，不做查询时过滤。
 */
@Getter
public enum ActivityState {

    /** 有效期还没开始（预热期：双节活动 9/25 开始，9/15 就该能看到并收藏） */
    NOT_STARTED("预告"),

    /** 在有效期内、今天还有未到的时段 */
    UPCOMING_TODAY("今日待开始"),

    /** 此刻命中某个生效时段（唯一需要强调的态，列表页标记只在它成立时出现） */
    ACTIVE("进行中"),

    /** 在有效期内、今天的时段都过了（保留并明确"还有明天"） */
    ENDED_TODAY("今日已结束");

    private final String displayName;

    ActivityState(String displayName) {
        this.displayName = displayName;
    }
}
