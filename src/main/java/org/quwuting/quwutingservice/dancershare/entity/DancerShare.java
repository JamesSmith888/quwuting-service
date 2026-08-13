package org.quwuting.quwutingservice.dancershare.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;

import java.time.LocalDateTime;

/**
 * 舞伴分享事件日志（只追加，不修改不删除——分析型数据，镜像 {@code qwt_venue_shares} 结构）。
 * <p>
 * 一张表承载两类事件（eventType 区分，枚举与场所分享共享 {@link ShareEventType}）：
 * <ul>
 *   <li>SHARE — 分享动作：actor=分享者（user_id），channel=发起渠道（BUTTON/MENU/TIMELINE）</li>
 *   <li>OPEN — 被分享者打开舞伴详情页：actor=打开者（user_id），share_from=原分享者（归因）</li>
 * </ul>
 * 无唯一约束（事件日志语义：每次分享 / 打开都是一条独立事件，天然可 append）。
 * 匿名用户 userId 为 null，仅参与 IP 频控，不参与身份归因。
 * <p>
 * 边界：分享维度不在热度公式闭集内（与场所分享同语义，见 AGENTS.md「场所热度」章节），
 * 本表不参与热度计算，也不 invalidate 热度缓存——纯分析数据源（邀请排行 / 热门传播 / 回流归因）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_shares", indexes = {
        @Index(name = "qwt_idx_dancer_shares_dancer_time", columnList = "dancerId, createdAt"),
        @Index(name = "qwt_idx_dancer_shares_user", columnList = "userId"),
        @Index(name = "qwt_idx_dancer_shares_from", columnList = "shareFrom")
})
public class DancerShare {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long dancerId;

    /** 事件发起者（分享者 / 打开者），匿名时为 null（匿名参与 IP 频控，不参与身份归因） */
    private Long userId;

    /** 事件类型：SHARE=分享动作 / OPEN=被分享者打开 */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ShareEventType eventType;

    /** 分享发起渠道（仅 SHARE 事件），BUTTON / MENU / TIMELINE */
    @Column(length = 16)
    private String channel;

    /** 归因来源：OPEN 事件记录原分享者用户 ID（回流归因），SHARE 事件恒为 null */
    private Long shareFrom;

    @CreationTimestamp
    private LocalDateTime createdAt;
}
