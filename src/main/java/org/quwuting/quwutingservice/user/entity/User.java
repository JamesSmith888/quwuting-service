package org.quwuting.quwutingservice.user.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.user.enums.UserRole;

@Getter
@Setter
@Entity
@Table(name = "qwt_users", indexes = {
        @Index(name = "qwt_idx_users_open_id", columnList = "openId", unique = true)
})
public class User extends BaseEntity {

    /** 微信 openid，用户唯一标识 */
    @Column(nullable = false, length = 64, unique = true)
    private String openId;

    @Column(length = 64)
    private String nickname;

    @Column(length = 500)
    private String avatarUrl;

    /** 年龄（用户自主录入，null = 未填写；仅经 GET /users/{id} 自愿分享通道下发） */
    @Column
    private Integer age;

    /** 性别（MALE / FEMALE，null = 未声明不展示；自愿分享通道下发） */
    @Column(length = 16)
    private String gender;

    /** 常驻城市（行政区划名，如「杭州市」；自愿分享通道下发，便于同城按时段匹配） */
    @Column(length = 64)
    private String city;

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    private UserRole role = UserRole.USER;

    /**
     * 突发窗口内最多下发的微信订阅通知条数（2026-09-08 新增，V13，
     * docs/agents/41-wx-subscribe-notify.md）。
     * <p>
     * 语义：<b>限制的是「我们一次能打断用户几次」，不是限制用户授权额度</b>——
     * 微信一次性订阅额度可无限累加（每次 accept +1，勾「总是保持 + 允许」后静默
     * 累加），但发送侧必须自缚：批量状态更新时关注 N 家的用户若一次收到 N 条服务
     * 通知，会直接去设置里关闭订阅（微信侧永久且不可逆），唯一被动触达通道报废。
     * <p>
     * 档位：{@value #BATCH_LIMIT_UNLIMITED}（0）= 不限，接收全部门店变动；
     * 3 = 默认档；5 = 重度用户档。窗口时长是技术参数（识别「一批」用），走配置
     * {@code wechat.subscribe.burst-window-minutes}，见 {@code WxSubscribeBurstLimiter}。
     */
    @Column(nullable = false)
    private Integer wxNotifyBatchLimit = DEFAULT_WX_NOTIFY_BATCH_LIMIT;

    /** 「不限」档位值（0 = 不限制突发窗口内的通知条数） */
    public static final int BATCH_LIMIT_UNLIMITED = 0;

    /** 默认档位（3 条）——新用户建号即生效，与 V13/V14 DDL 的 DEFAULT 3 保持一致 */
    public static final int DEFAULT_WX_NOTIFY_BATCH_LIMIT = 3;

    /** 重度用户档位（5 条） */
    public static final int BATCH_LIMIT_HEAVY = 5;
}
