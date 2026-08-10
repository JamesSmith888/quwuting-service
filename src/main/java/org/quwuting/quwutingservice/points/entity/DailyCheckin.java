package org.quwuting.quwutingservice.points.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 每日打卡记录（幂等锚点 + 防刷硬约束）。
 * <p>
 * UNIQUE(user_id, checkin_date) 保证"一天一次"的<b>业务语义</b>（可查询
 * "今日是否已打卡"）；发分的账务幂等由流水唯一键 (user, DAILY_CHECK_IN,
 * checkin_id) 承担——两表双约束职责分离（与 feedback 防刷的"冷却 + 唯一索引"
 * 双保险模式一致）。
 * <p>
 * <b>不继承 BaseEntity</b>（与 qwt_venue_status_logs 同模式）：打卡是幂等锚点
 * 记录，无 updatedAt（写入后不变）、无 deleted（不做软删）。
 * <p>
 * 打卡无连续加成（V2 决策：不引入游戏化杠杆，防止马甲批量打卡的收益放大）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_daily_checkins", indexes = {
        @Index(name = "qwt_idx_checkins_user", columnList = "userId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_checkins_user_date", columnNames = {"userId", "checkinDate"})
})
public class DailyCheckin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private Long userId;

    /** 打卡自然日（服务器时区 Asia/Shanghai，见 application.yaml） */
    @Column(nullable = false)
    private LocalDate checkinDate;
}
