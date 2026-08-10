package org.quwuting.quwutingservice.points.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * 积分账户（一用户一行，user_id 唯一）。
 * <p>
 * balance 是<b>读写快照</b>（详情页/赠送校验高频读，不每次 SUM 流水）；
 * 流水（{@link PointsTransaction}）才是余额的唯一事实源，本表余额/累计由
 * 账务操作同步维护，靠流水 balance_after 支持日终对账（SUM(delta) vs balance）。
 * <p>
 * <b>不继承 BaseEntity</b>（与 qwt_venue_status_logs 同模式）：账户是用户资产的
 * 伴随记录，无软删语义（用户删除走 qwt_users.deleted，账户随行）；updatedAt 由
 * 余额变更触发（@UpdateTimestamp），供运营观测账户活跃。
 * <p>
 * 赠送扣减用<b>原子条件更新</b>（{@code UPDATE ... SET balance = balance - :amt
 * WHERE user_id = :id AND balance >= :amt}）——无锁、防并发超扣（affected=0 即
 * 余额不足），见 {@code PointsAccountRepository#deductBalance}。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_points_accounts", uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_points_accounts_user", columnNames = {"userId"})
})
public class PointsAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @Column(nullable = false)
    private Long userId;

    /** 当前余额（读写快照，恒 >= 0；由原子条件更新保证不出现负余额） */
    @Column(nullable = false)
    private long balance = 0;

    /** 累计挣取（balance_after 对账的冗余累计） */
    @Column(nullable = false)
    private long earnedTotal = 0;

    /** 累计赠送 */
    @Column(nullable = false)
    private long spentTotal = 0;
}
