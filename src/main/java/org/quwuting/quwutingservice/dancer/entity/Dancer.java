package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;

import java.time.LocalDateTime;

/**
 * 舞伴实体（独立领域模型，与舞厅解耦——不设计强绑定单一舞厅，多舞厅关系见 DancerVenue）。
 * <p>
 * 可见性规则（AGENTS.md「舞伴生态体系」）：status=PENDING/HIDDEN 时不对公众展示，
 * 仅创建人本人与平台管理员可见；NORMAL 才进入公开列表/详情。
 * <p>
 * 隐私边界：本实体只承载公开可展示的资料（昵称/头像/简介/性别可选/常驻城市），
 * 不存联系方式、不存私人信息；用户对本实体唯一可写的公开影响是「认可 + 字典标签」
 * （见 DancerRecognition / DancerRecognitionTag）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancers", indexes = {
        @Index(name = "qwt_idx_dancers_status", columnList = "status"),
        @Index(name = "qwt_idx_dancers_created_by", columnList = "createdBy"),
        @Index(name = "qwt_idx_dancers_city", columnList = "city")
})
public class Dancer extends BaseEntity {

    /** 舞伴昵称（展示名，创建时必填） */
    @Column(nullable = false, length = 30)
    private String nickname;

    /** 头像 URL（不开放用户上传私人照片，由舞伴本人或管理员提供） */
    @Column(length = 500)
    private String avatarUrl;

    /** 简介（舞伴自述，最长 300 字符） */
    @Column(length = 300)
    private String bio;

    /** 性别（可选，按业务需求开放；null = 未声明，前端不展示） */
    @Column(length = 20)
    private String gender;

    /**
     * 联系方式（2026-08-14 新增，微信号等，可空）。
     * <p>
     * 隐私边界（与 2026-08-14 积分解锁公共模块配套，见 V24 迁移）：
     * 联系方式属于舞伴资料的一部分，随 UpsertDancerRequest 走既有 PENDING 审核流程；
     * 公开后是否直接可见由积分门槛决定（qwt_points_gates target_type='DANCER_CONTACT'，
     * target_id = 本舞伴 ID）——无门槛 → 详情页直接展示；有门槛 → 解锁后展示
     * （未解锁不下发真实值）。明文存储（与昵称/简介同级别，MVP 不加密）。
     */
    @Column(length = 100)
    private String contact;

    /**
     * 联系方式图片（2026-08-14 新增，二维码等，可空；见 V29 迁移）。
     * <p>
     * 语义与 contact 同一门槛/遮挡（详情页三态一致）：图片是联系方式的一部分，
     * 未解锁/遮挡时不下发真实 URL（防绕过）。入库前必须经
     * storage/ImageContentValidator 内容校验（08-12 安全加固约定，见 AGENTS.md
     * 「文件上传与存储」——新增图片 URL 落库字段必须挂载校验）。
     */
    @Column(length = 500)
    private String contactImageUrl;

    /**
     * 联系方式遮挡开关（2026-08-14 新增，默认遮挡）。
     * <p>
     * true = 详情页联系方式<b>打码展示</b>（遮罩）：无门槛（免费）→ 用户点击遮罩
     * 直接显示；有门槛（cost>0）→ 用户先支付积分解锁再显示（POST /points/unlock）。
     * false = 不遮挡，联系方式<b>恒直接展示</b>（后端忽略积分门槛下发真实值，
     * 残留门槛值保留，重新遮挡后恢复生效）。与 qwt_points_gates 门槛正交。
     * 列默认值唯一声明通道 = @ColumnDefault（见 V28 迁移）。
     */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean hideContact = true;

    /**
     * 创作者收益计划开关（2026-08-14 新增，默认关闭）。
     * <p>
     * 开启后舞伴详情页接入微信小程序<b>激励视频广告</b>（用户主动点击观看"支持 TA"），
     * 观看记录写入 qwt_dancer_ad_views（线下结算依据），收益由平台<b>线下转账</b>
     * 结算（MVP 无线上结算）。列默认值唯一声明通道 = @ColumnDefault。
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean earningsEnabled = false;

    /**
     * 加好友需告知位置（2026-08-26 新增，默认关闭；per-dancer 开关）。
     * <p>
     * 语义：开启后用户获取联系方式（需求确认层）须二选一表态——「同城」或
     * 「非同城 · 自行前往」（UserLocationOption，随需求记录落库）。面向需要
     * 确认用户能否到达服务地点的舞伴（服务范围 location_scope 的配套确认），
     * 非所有舞伴都需要。设计上<b>不收集真实地址</b>（相对关系而非 PII，
     * 见 UserLocationOption javadoc / docs/agents/20）。列默认值唯一声明通道
     * = @ColumnDefault（见 V47 迁移）。
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean requireUserLocation = false;

    /**
     * 邀约中转开关（2026-08-26 新增，默认关闭；per-dancer 开关，V50 迁移）。
     * <p>
     * 语义：开启后该舞伴的联系方式获取改为<b>管理员中转 + 舞伴批准</b>流程
     * （docs/agents/22）——客人填邀约单后不再直接拿到微信（unlock 返回
     * PENDING），邀约进入管理员后台待办，由管理员微信人工转发给舞伴，舞伴回
     * 「给/不给」，管理员一键发放/拒绝；24h 无回复按 autoRelease 自动降级。
     * 关闭（默认）= 填单即得微信现状，存量舞伴零回归。面向在意「把关权」
     * （过滤口嗨）的舞伴，如高流量舞伴。
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean contactRelay = false;

    /**
     * 24h 无回复自动降级策略（2026-08-26 新增，默认开启；per-dancer 开关，
     * V50 迁移；仅 contact_relay=true 时有意义）。
     * <p>
     * true = 24h 无回复自动发放联系方式（平台兜底，默认策略——客人不能被
     * 无限期干等，转化要兜底）；false = 告知客人「暂未回复」（EXPIRED）。
     * <b>高流量舞伴建议 false</b>：她明确在乎把关权（宁可客人流失，不让平台
     * 代发微信）；配合 12h 管理员微信催办，降级成为「催过无回应」的兜底而非
     * 「平台默默放行」。
     */
    @Column(nullable = false)
    @ColumnDefault("true")
    private boolean autoRelease = true;

    /**
     * 当前所在城市（2026-08-26 新增，可空；V48 迁移）。
     * <p>
     * 语义：从已选常驻城市（{@code qwt_dancer_cities}）中再选中一个作为舞伴
     * <b>当前</b>所在城市——用户在「解锁联系方式-是否同城」时据此判断是否与
     * 舞伴同城（多城市场景下「城市列表」无法表达当前所在）。约束：必须为已选
     * 城市之一（服务层校验，非法 → 1001）；未填城市（纯线上舞伴）恒为 null；
     * 存量舞伴为 null 时前端回退主城市（city）展示。
     */
    @Column(length = 50)
    private String currentCity;

    /**
     * 资料状态。列默认值唯一声明通道 = @ColumnDefault（见 AGENTS.md「Schema 演进」）。
     * 默认 PENDING：所有新资料必须经管理员认证后才公开（真实个人隐私边界的第一道闸）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private DancerStatus status = DancerStatus.PENDING;

    /**
     * 信息核验状态（2026-08-14 官方认证——「信息已核验」标识）。
     * <p>
     * 语义 = 身份与公开信息经平台人工核验属实（裁决事实，不裁决人品）；
     * 状态机：UNVERIFIED（默认）→ VERIFIED（admin 授予）→ PENDING_REVIEW
     * （舞伴本人编辑触发待复核）→ admin 复核确认 VERIFIED 或撤销回 UNVERIFIED。
     * 与 DancerStatus（先认证、后展示的隐私闸门）互补：DancerStatus 管"是否公开"，
     * 本状态管"平台是否背书信息真实性"——两条链路显式分离，审核通过不等于认证。
     * 全部变迁留痕 qwt_dancer_verification_logs（见类 javadoc / AGENTS.md）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'UNVERIFIED'")
    private DancerVerificationStatus verificationStatus = DancerVerificationStatus.UNVERIFIED;

    /** 最近一次授予认证的时间（非 VERIFIED 恒 null；历史变迁在审计日志表） */
    private LocalDateTime verifiedAt;

    /** 最近一次授予认证的管理员 ID（非 VERIFIED 恒 null；历史变迁在审计日志表） */
    private Long verifiedBy;

    /** 创建人用户 ID（舞伴主动注册 = 本人认领；后台创建 = 管理员 ID） */
    @Column(nullable = false)
    private Long createdBy;

    /**
     * 主城市（2026-08-14 多城市：= qwt_dancer_cities 第一个城市，service 单一写入方）。
     * <p>
     * 冗余筛选字段（创建时填写，多城市见 {@code qwt_dancer_cities} 子表）——
     * 本列保留为<b>主城市</b>（cities[0]）：列表/详情/分享展示位与列表排序 SQL
     * 零改动；列表按任意城市筛选时经子表匹配（见 DancerRepository#findPublicPage）。
     * 不构成与舞厅的强绑定——舞伴的场所归属以 DancerVenue 关系表为准，
     * 本字段仅服务列表按城市筛选与展示。
     */
    @Column(length = 50)
    private String city;

    /**
     * 资料标签（2026-08-24，V40 迁移）：通用标签字典 {@code qwt_tag_dict} 的 id 数组
     * （JSON 字符串，如 "[1,3]"；null/空 = 无标签）。
     * <p>
     * 语义 = <b>管理员/运营设置的资料标签</b>（线上/线下/龙女…，黄页内容，平台代发模型）——
     * 与「舞伴认可标签」（qwt_dancer_recognition_tags，用户认可行为产生）完全独立；
     * 展示时经 {@code TagDictService#resolveOrdered} 解析为 TagItemResponse
     * （text + description，长按/点击弹说明的权威文案）。
     * 存 id 而非 text：标签重命名/改说明不影响历史关联。编辑为全量覆盖语义
     * （传 null/空 = 清除全部标签，与多城市/常驻舞厅同「编辑 = 变更而非追加」约定）。
     */
    @Column(length = 500)
    private String profileTags;

    /**
     * 联系方式最近一次变更时间（2026-08-26 晚 新增，可空；V51 迁移）。
     * <p>
     * 语义：contact / contact_image_url 任一有变更且变更后非空 → 服务层写入
     * LocalDateTime.now()。用于列表 / 详情「最近更新了联系方式」信号——与
     * Dancer.updated_at（任意字段变更都跳动）正交，专指联系方式这一项。
     * 存量舞伴恒 NULL（前端不渲染该信号）；列无 NOT NULL / 无 DEFAULT。
     */
    private LocalDateTime contactUpdatedAt;
}
