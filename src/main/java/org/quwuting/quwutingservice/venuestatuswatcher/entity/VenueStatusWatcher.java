package org.quwuting.quwutingservice.venuestatuswatcher.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 关注门店营业状态（2026-08-12 新增，见 AGENTS.md「关注门店营业状态通知」）。
 * <p>
 * 语义：用户关注某门店的<b>营业状态变化</b>——门店状态每次实际变更（updateVenue /
 * 采纳暂停/恢复报告）时，关注者收到站内信（MessageType.VENUE_STATUS_CHANGED）。
 * 与收藏（qwt_favorites）解耦：不收藏也能开通知（如"这家暂停营业了，等它恢复"）。
 * <p>
 * 唯一约束 (user_id, venue_id)：同一用户对同一门店只关注一次（重复开启幂等）。
 * 取消关注 = 物理删除（无审计价值，与 reaction 取消同语义）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_status_watchers", indexes = {
        @Index(name = "qwt_idx_venue_status_watchers_user_venue",
                columnList = "userId, venueId", unique = true),
        @Index(name = "qwt_idx_venue_status_watchers_venue", columnList = "venueId")
})
public class VenueStatusWatcher extends BaseEntity {

    /** 关注者用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 被关注门店 ID */
    @Column(nullable = false)
    private Long venueId;
}
