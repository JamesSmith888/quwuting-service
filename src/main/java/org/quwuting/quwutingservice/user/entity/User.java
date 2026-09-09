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
     * 微信审核账号标记（2026-09-09 V17）：管理端一切用户量统计口径排除该账号
     * ——不删除账号、不影响小程序端任何功能，仅统计去噪。
     * <p>
     * 背景：微信提审期间审核员以真实账号进入产生注册/上报/打卡（2026-09-06 起
     * 打卡型噪音占比 60%+；单账号 34 条「照片有误」上报全被忽略），污染 admin
     * 平台统计图与统计条。存量名单见 V17 迁移注释（审计依据 + 用户点名）；
     * 后续由管理员在 admin-web 用户详情页手动标记/取消
     * （POST /admin/users/{id}/wechat-review）。
     */
    @Column(nullable = false)
    private Boolean wechatReview = Boolean.FALSE;

    /**
     * 突发窗口内最多下发的微信订阅通知条数（2026-09-08 新增，V13，
     * docs/agents/41-wx-subscribe-notify.md）。
     * <p>
     * 语义：<b>限制的是「我们一次能打断用户几次」，不是限制用户授权额度</b>——
     * 微信一次性订阅额度可无限累加（每次 accept +1，勾「总是保持 + 允许」后静默
     * 累加），发送侧保留自缚能力以防批量状态更新时轰炸用户。
     * <p>
     * 档位：{@value #BATCH_LIMIT_UNLIMITED}（0）= 不限，接收全部门店变动；
     * {@value #BATCH_LIMIT_CONSERVATIVE} = 保守档；{@value #BATCH_LIMIT_HEAVY} =
     * 重度档。窗口时长是技术参数（识别「一批」用），走配置
     * {@code wechat.subscribe.burst-window-minutes}，见 {@code WxSubscribeBurstLimiter}。
     * <p>
     * <b>2026-09-09 默认档反转（V15）</b>：默认 3 → 0（不限）。根因 = 原默认档防的
     * 是「用户同时收藏很多门店、批量变更同分钟轰炸」的假想场景，而业务现实是用户
     * 不会同时收藏很多门店——防御机制让全体用户为低概率场景买单；且该参数曾以
     * 「通知上限」设置项暴露给用户，把发送侧内部参数泄漏进了用户心智（用户只关心
     * 「想不想被提醒」，不关心「一批最多几条」）。本字段自此降级为<b>运维安全阀</b>：
     * 字段与设置接口保留（可按用户显式调档），无用户侧 UI，日常恒为不限。
     */
    @Column(nullable = false)
    private Integer wxNotifyBatchLimit = DEFAULT_WX_NOTIFY_BATCH_LIMIT;

    /** 「不限」档位值（0 = 不限制突发窗口内的通知条数），2026-09-09 起为默认档 */
    public static final int BATCH_LIMIT_UNLIMITED = 0;

    /** 默认档位 = 不限（2026-09-09 V15 反转，原 3；与 DDL DEFAULT 0 保持一致） */
    public static final int DEFAULT_WX_NOTIFY_BATCH_LIMIT = BATCH_LIMIT_UNLIMITED;

    /** 保守档位（3 条）——原默认档，现仅作显式选择的低打扰档 */
    public static final int BATCH_LIMIT_CONSERVATIVE = 3;

    /** 重度用户档位（5 条） */
    public static final int BATCH_LIMIT_HEAVY = 5;
}
