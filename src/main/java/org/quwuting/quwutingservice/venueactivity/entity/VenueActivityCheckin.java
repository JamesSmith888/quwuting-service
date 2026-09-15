package org.quwuting.quwutingservice.venueactivity.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDate;

/**
 * 活动到店打卡 —— 平台侧**唯一自有的**活动归因信源。
 * <p>
 * 为什么必须有它（前一轮的核心结论）：活动数据若只来自门店老板口述，而老板与
 * 平台诉求并不一致（他想要更多曝光、将来要谈分成）⇒ 有夸大动机 ⇒ 用一个有利益
 * 冲突的对手方作唯一数据源，得到的数字撑不起决策。这一张表就是那个"平台自有信源"。
 * <p>
 * ⛔ <b>不参与热度公式</b>：热度指标四问判据的"难伪造"一关过不了——同一个人在
 * 店里反复打卡零成本，而做地理围栏校验又违背 dancer 地址域"克制采集、避免精确
 * 坐标"的既定立场。它只服务一个用途：<b>与门店对账</b>（老板唯一看得懂的凭证）。
 * 平台自身的"活动是否真的带来使用深度"，看另一个信号——既有「报一下」（27 号
 * 门店人气上报）行为的增量，那是本来就有可信度机制的既有行为。
 * <p>
 * 幂等由唯一键 {@code (activity_id, user_id, activity_date)} 兜底（每人每活动
 * 每日一次），查询前置 + 唯一索引兜底并发，与公告 dedupKey 同一模式。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_activity_checkins",
        uniqueConstraints = @UniqueConstraint(name = "qwt_uk_venue_activity_checkin",
                columnNames = {"activityId", "userId", "activityDate"}))
public class VenueActivityCheckin extends BaseEntity {

    @Column(nullable = false)
    private Long activityId;

    @Column(nullable = false)
    private Long venueId;

    @Column(nullable = false)
    private Long userId;

    /**
     * 打卡所属日期（自然日）。
     * <p>
     * ⚠️ 一律按<b>自然日</b>归属，接口层用服务端 {@code LocalDate.now()} 填写、
     * <b>禁客户端传入</b>（设备时钟可改，归因数据不能建立在客户端时间上）。
     * 刻意不引入"营业日"（跨夜归前一日）概念——它会让"今日 13:00"在两个不同的
     * 日子里产生歧义，而活动时段本身按自然日呈现，两者口径必须一致。
     */
    @Column(nullable = false)
    private LocalDate activityDate;
}
