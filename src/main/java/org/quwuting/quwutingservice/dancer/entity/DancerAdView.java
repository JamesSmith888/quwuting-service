package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 舞伴广告观看记录（创作者收益计划，2026-08-14）。
 * <p>
 * 用途：① 激励视频广告"观看完成"后的收益记账（线下结算依据——平台按月线下转账）；
 * ② 防刷锚点：UNIQUE(user_id, dancer_id, view_date) 同一用户同舞伴每天至多
 * 支持一次（SQLState 23505 幂等返回已有计数，不重复计收益）。
 * <p>
 * <b>不继承 BaseEntity</b>（与 qwt_daily_checkins 同模式）：收益锚点只写一次，
 * 无 updatedAt、无软删（观看行为不可撤销）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_ad_views", indexes = {
        @Index(name = "qwt_idx_ad_views_dancer", columnList = "dancerId")
})
public class DancerAdView {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 所属舞伴 ID（收益归属） */
    @Column(nullable = false)
    private Long dancerId;

    /** 观看者用户 ID（防刷主体） */
    @Column(nullable = false)
    private Long userId;

    /** 观看日（每日一次的防刷锚点，UNIQUE 约束列） */
    @Column(nullable = false)
    private LocalDate viewDate;
}
