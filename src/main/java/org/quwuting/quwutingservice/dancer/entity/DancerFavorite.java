package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 用户对舞伴的收藏记录（2026-08-14 舞伴收藏，V27）。
 * <p>
 * 设计完全复用门店收藏（qwt_favorites）确立的「软删 + 唯一约束 + restore 恢复」模式：
 * <ul>
 *   <li>UNIQUE(user_id, dancer_id)：软删行可被重新收藏复用（restore 语义——清
 *       deleted 标志即恢复，created_at 不变，与门店收藏恢复逻辑一致）；</li>
 *   <li>取消收藏 = 软删（deleted=true），行保留——被收藏舞伴 HIDDEN 下架后行
 *       仍留存，恢复 NORMAL 后自动重现于收藏列表（见 V27 迁移注释）；</li>
 *   <li>与门店收藏的关键差异：<b>无 unfavorited_at / 无频控</b>——门店收藏的
 *       取消时刻列与 Caffeine 60s 阈值频控根因是"取消收藏刷高热度趋势折线"；
 *       舞伴收藏趋势为<b>新增单序列</b>（按 created_at 分组，无门店式 unfavorited_at
 *       取消线，2026-08-14 舞伴统计第一期——软删取消不改变新增计数），无膨胀风险，
 *       只需幂等。</li>
 * </ul>
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_favorites", indexes = {
        @Index(name = "qwt_idx_dancer_fav_user", columnList = "userId"),
        @Index(name = "qwt_idx_dancer_fav_dancer", columnList = "dancerId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_dancer_fav_user_dancer", columnNames = {"userId", "dancerId"})
})
public class DancerFavorite extends BaseEntity {

    @Column(nullable = false)
    private Long userId;

    @Column(nullable = false)
    private Long dancerId;
}
