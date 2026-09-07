package org.quwuting.quwutingservice.venuestatuswatcher.event;

import org.quwuting.quwutingservice.venue.enums.VenueStatus;

import java.util.List;

/**
 * 门店营业状态实际变更事件（2026-09-07 新增）。
 * <p>
 * 发布方 = {@code VenueStatusWatcherService#notifyStatusChanged}（站内信同点，
 * 事务内发布）；消费方 = 微信订阅消息发送（{@code WxSubscribeSendService}，
 * AFTER_COMMIT 异步语义：通知失败绝不影响已提交的状态变更主流程）。
 * <p>
 * watcherUserIds 已由发布方按 deleted=false 过滤（当次关注者快照）。
 *
 * @param venueId        门店 ID
 * @param venueName      门店名称（订阅消息「当前状态」字段需拼门店名——模板仅
 *                       消息类型/当前状态/时间三个关键词，无独立门店名字段）
 * @param from           原状态（可能为 null）
 * @param to             新状态
 * @param watcherUserIds 关注者用户 ID 集合（非空；空集合发布方不发事件）
 */
public record VenueStatusChangedEvent(
        Long venueId,
        String venueName,
        VenueStatus from,
        VenueStatus to,
        List<Long> watcherUserIds) {
}
