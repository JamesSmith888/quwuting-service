package org.quwuting.quwutingservice.bulletin.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDate;

/**
 * 用户对一条行业快讯的一次展示浏览（2026-09-11，docs/agents/47-bulletins.md「九、浏览统计」；V20）。
 * <p>
 * <b>口径：信息流展示即计</b>——快讯在列表/详情被加载展示时上报一次浏览，服务端按
 * 唯一约束 {@code (bulletinId, userId, viewDate)} 去重：同一用户同一条快讯同一天
 * 只计 1 次（跨天再刷重新计，每日展示 PV 口径）。与门店域 {@code qwt_venue_views}
 * 的差异：<b>无 source 维度</b>——快讯只有信息流一个展示入口，无需按来源归因分列。
 * <p>
 * <b>计数不缓存</b>：快讯体量小（对齐表态域"无聚合缓存"的理由），列表页按整页 id
 * 一次 IN + GROUP BY 聚合；写入走 {@code BulletinViewService} 的 JdbcTemplate
 * 单语句多行批量 upsert（一次 DB 往返覆盖整页，满足"最少 DB 往返"第一约束）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_bulletin_views", indexes = {
        @Index(name = "qwt_idx_bv_bulletin_date", columnList = "bulletinId, viewDate")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_bv_user_bulletin_date", columnNames = {"bulletinId", "userId", "viewDate"})
})
public class BulletinView extends BaseEntity {

    /** 被浏览的快讯 id（qwt_announcements 中 category='FLASH' 的条目） */
    @Column(nullable = false)
    private Long bulletinId;

    /** 浏览者用户 id（信息流接口全部需登录，恒非空，无匿名路径） */
    @Column(nullable = false)
    private Long userId;

    /** 浏览日期（去重粒度：同一用户同一条快讯同一天仅计 1 次） */
    @Column(nullable = false)
    private LocalDate viewDate;
}
