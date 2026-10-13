package org.quwuting.quwutingservice.payqr.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

import java.time.LocalDateTime;

/**
 * 计时收款码配置状态（2026-10-13，V49；前端权威文档 = quwuting 仓 docs/agents/60-pay-qr.md 决策 14）。
 * <p>
 * 每用户恒一行（user_id 唯一）。<b>只存张数，不存图</b>：收款码本体仍只在用户手机本机
 * （60 号决策 1：上传 = 平台开始保管支付凭证）；本表回答的唯一问题是「这个用户在自己手机里配置过几张」，
 * 供 admin 统计与识别——数据面是一个 0~6 的整数加两个时刻，没有任何可还原收款码的信息。
 * <p>
 * 时间列语义：{@link #firstConfiguredAt} 服务端时钟下首次 {@code qrCount>0} 的时刻（之后不再改）；
 * {@code updatedAt} 仅在张数变化时推进（native upsert 控制，见 Repository）⇒ 即「最近一次变更」。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_user_pay_qr_status")
public class UserPayQrStatus extends BaseEntity {

    /** 用户（qwt_users.id；接口恒取登录态，禁客户端传入） */
    @Column(name = "user_id", nullable = false, unique = true)
    private Long userId;

    /** 本机当前持有的收款码张数（0 = 已清空） */
    @Column(name = "qr_count", nullable = false)
    private Integer qrCount;

    /** 首次 qrCount>0 的服务端时刻；null = 从未配置过（只报过 0） */
    @Column(name = "first_configured_at")
    private LocalDateTime firstConfiguredAt;
}
