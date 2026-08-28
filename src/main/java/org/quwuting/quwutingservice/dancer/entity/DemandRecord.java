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

import java.time.LocalDateTime;

/**
 * 联系方式需求记录（2026-08-24 需求 5 风控留痕，qwt_demand_records）。
 * <p>
 * 语义：用户在获取某舞伴联系方式前选择了本次需求（服务 ≤2 + 时间 ≤2 + 时长可选），
 * 服务端生成添加好友需求描述并随本记录落库——支撑骚扰投诉排查（按 dancer 维度
 * 反查"哪些需求在找 TA"）与异常访问识别（按 user 维度统计单日解锁频次）。
 * <p>
 * <b>隐私克制</b>：只存枚举/服务 id（service_ids = qwt_dancer_services.id 逗号串、
 * time_slots = 具体日期（YYYY-MM-DD，2026-08-25 改版：今天起 7 天窗口）逗号串、
 * duration = DemandDuration 枚举）——
 * 需求 = 用户行为数据，非身份信息；message 为服务端拼接的完整需求描述（审计用，
 * 与用户微信添加好友时粘贴的内容一致）。
 * <p>
 * <b>不继承 BaseEntity</b>（锚点记录只写一次，无更新/软删语义，同 PointsUnlock 模式）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_demand_records", indexes = {
        @Index(name = "idx_qwt_demand_records_user", columnList = "user_id, created_at"),
        @Index(name = "idx_qwt_demand_records_dancer", columnList = "dancer_id, created_at"),
        @Index(name = "idx_qwt_demand_records_user_dancer", columnList = "user_id, dancer_id")
})
public class DemandRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @CreationTimestamp
    private LocalDateTime createdAt;

    /** 提出需求的用户 ID */
    @Column(nullable = false)
    private Long userId;

    /** 目标舞伴 ID */
    @Column(nullable = false)
    private Long dancerId;

    /** 选中的服务 id 列表（qwt_dancer_services.id 逗号串，≤2；风控只存 id 不存文本） */
    @Column(nullable = false, length = 100)
    private String serviceIds;

    /** 选中的时间（具体日期 YYYY-MM-DD 逗号串，恰好 1；2026-08-25 改版） */
    @Column(nullable = false, length = 50)
    private String timeSlots;

    /** 时长（DemandDuration 枚举；可空 = 未选） */
    @Column(length = 20)
    private String duration;

    /**
     * 用户位置表态（2026-08-26，UserLocationOption 枚举 code：SAME_CITY 同城 /
     * WILL_TRAVEL 自行前往；可空 = 舞伴未开启「加好友需告知位置」）。
     * 只存枚举 code（相对关系非真实地址，隐私克制，见 UserLocationOption javadoc）。
     */
    @Column(length = 20)
    private String userLocation;

    /** 服务端拼接的完整需求描述（与用户微信添加好友时粘贴的内容一致，审计用） */
    @Column(nullable = false, length = 120)
    private String message = "";

    /**
     * 邀约状态（2026-08-26 新增，V50；DemandStatus 枚举 code，可空）。
     * <p>
     * 语义：邀约中转状态机（docs/agents/22）——开启中转开关（contact_relay）的
     * 舞伴，邀约提交后 = PENDING，管理员人工转发给舞伴后按舞伴回复置
     * APPROVED/REJECTED，24h 无回复按 auto_release 置 AUTO_RELEASED/EXPIRED。
     * <b>NULL = 存量锚点记录</b>（V42 前无状态语义，历史客人在当时已拿到微信，
     * 等价 APPROVED，前端徽标兼容不渲染）。本表语义 = 锚点记录只写一次，status
     * 是唯一允许更新的列（发放/拒绝/降级写路径，条件更新 WHERE status='PENDING'
     * 天然幂等）。
     */
    @Column(length = 20)
    private String status;

    /**
     * 履约确认时间（2026-08-27 新增，V54；docs/agents/23「P1 履约闭环」）。
     * <p>
     * 非空 = 客人已确认本次邀约履约完成（幂等写一次，不更新）——「与舞伴已合作
     * N 次」的私域履约信号（仅本人邀约详情 + 管理端邀约单可见，不公开广播）；
     * NULL = 未确认（含存量记录，前端不渲染履约卡）。仅邀约已获批
     * （APPROVED/AUTO_RELEASED 或存量 NULL 等价已发放）可确认，由
     * DemandFulfillmentService 应用层校验。
     */
    @Column
    private LocalDateTime fulfilledAt;

    /**
     * 舞伴拒绝原因（2026-08-27 新增，V55；DemandRejectReason 枚举 code，可空）。
     * <p>
     * 语义：管理员按舞伴微信回复「不给」拒绝邀约时选填原因标签（档期冲突/距离太远/
     * 需求类型不符/暂不接新客/其他）——客人侧据此展示知因文案（「TA 暂时不方便
     * （档期冲突）」），拒绝从「句号」变「信息」；管理端撮合台按原因优化推荐
     * （帮客人找替代时避开同因舞伴）。NULL = 未填原因/存量记录（客人侧回退
     * 通用状态文案，见 DemandStatus.statusText）。
     */
    @Column(length = 20)
    private String rejectReason;

    /**
     * 客人请求替代时间（2026-08-27 新增，V55；docs/agents/24「换乘站」）。
     * <p>
     * 语义：被拒/超时（REJECTED/EXPIRED）终态页客人点「让平台帮您找类似的」时
     * 置非空（只置一次，幂等）——管理端工作台据此识别「这位被拒客人想要续」
     * （高亮优先处理）。NULL = 未请求。客人侧不可反复请求（已请求后按钮变已请求态）。
     */
    @Column
    private LocalDateTime rescueRequestedAt;

    /**
     * 替代邀约溯源（2026-08-27 新增，V55；docs/agents/24「换乘站」）。
     * <p>
     * 语义：非空 = 本记录是管理员为另一条被拒/超时邀约代找的<b>替代邀约</b>——
     * 管理员微信人工确认替代舞伴同意后，以原邀约的四要素 + message 原样代建一条
     * 新记录（status=APPROVED 直接发放替代舞伴联系方式），原邀约状态保持
     * REJECTED/EXPIRED 不动。客人侧「我的邀约」天然出现新记录（平台代找标记），
     * 站内信直达新邀约详情。部分唯一索引 idx_qwt_demand_records_rescue_origin
     * 保证一次救援只产出一条替代邀约（防重复代建）。NULL = 普通邀约。
     */
    @Column
    private Long originDemandId;

    /**
     * 邀约被查看时间（2026-08-27 新增，V56；docs/agents/25「分享闭环自动化」）。
     * <p>
     * 语义：舞伴打开邀约落地页（demand-invite，分享卡片携带 demandId）时置位
     * （幂等只置第一次）——平台自动感知"分享生效"，客人侧「TA 已查看你的邀约」
     * 零操作可见（无需客人确认分享成功）。NULL = 未被查看/尚未分享。与
     * dancershare 的 OPEN 归因统计正交：本字段是<b>邀约粒度</b>的反馈信号，
     * 归因统计是 user×dancer 粒度的分享追踪，各自独立。
     */
    @Column
    private LocalDateTime shareOpenedAt;

    /**
     * 客人反馈（2026-08-27 新增，V56；docs/agents/25「反馈闭环」；
     * DemandGuestFeedback 枚举 code，可空）。
     * <p>
     * 语义：非中转舞伴（平台不感知线下结果）的客人遇到「没加上 TA / 被 TA 拒绝 /
     * 未回复」时一键反馈——平台感知真实世界结果 + 自动返还该邀约的原扣费积分
     * （幂等，返还键 = (user, UNLOCK_REFUND, demandId)）。管理端工作台据此识别
     * 需人工介入的邀约。NULL = 未反馈；feedbackRequestedAt 非空 = 已反馈（幂等
     * 只置一次，重复提交不重复返还）。
     */
    @Column(length = 20)
    private String guestFeedback;

    /**
     * 客人反馈提交时间（2026-08-27 新增，V56；docs/agents/25「反馈闭环」）。
     * 非空 = 客人已提交反馈（幂等只置一次，WHERE feedback_requested_at IS NULL
     * 条件更新——防重复提交重复返还积分）；NULL = 未反馈。
     */
    @Column
    private LocalDateTime feedbackRequestedAt;

    /**
     * 客人反馈已核实时间（2026-08-28 新增，V58；docs/agents/25「反馈闭环 ·
     * 管理端可见性修复」）。
     * <p>
     * 语义：管理端待办闭环的"已处理"侧——管理员微信侧核实（联系舞伴/客人）
     * 完成后置位（幂等，WHERE guest_feedback_handled_at IS NULL 条件更新）。
     * NULL + feedbackRequestedAt 非空 = 反馈待处理（计入邀约工作台待处理视图 +
     * me 页红点）；非空 = 已核实归档（从待办消失，已处理/全部视图可见）。
     * 无独立已读态（同 pending-count 范式：计数随处理动作自然归零）。
     */
    @Column
    private LocalDateTime guestFeedbackHandledAt;
}
