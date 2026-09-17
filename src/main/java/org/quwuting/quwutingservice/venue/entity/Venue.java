package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueStatusSource;
import org.quwuting.quwutingservice.venue.enums.VenueType;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "qwt_venues", indexes = {
        @Index(name = "qwt_idx_city", columnList = "city"),
        @Index(name = "qwt_idx_district", columnList = "district"),
        @Index(name = "qwt_idx_status", columnList = "status"),
        @Index(name = "qwt_idx_sort_weight", columnList = "sortWeight"),
        @Index(name = "qwt_idx_claimed_by", columnList = "claimedBy")
})
public class Venue extends BaseEntity {

    @Column(nullable = false, length = 100)
    private String name;

    /** 营业状态。列默认值唯一声明通道 = @ColumnDefault（见 AGENTS.md「Schema 演进」） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'OPEN'")
    private VenueStatus status = VenueStatus.OPEN;

    /**
     * 预期开业日（2026-09-17 新增，V29；方案见 docs/agents/50-venue-opening-plan.md）。
     * <p>
     * 与 {@link #status} 构成「现在时 × 将来时」这一对：status 是此刻的事实，本列是
     * 开业计划。一家「明天开业」的门店，status 保持停业类（今天的事实正确无误），
     * 本列写 9-18 —— 展示层据此派生 {@code UPCOMING}（徽标直接读作「9月18日开业」），
     * 到点由 {@code VenueOpeningScheduler} 走 VenueService 正规通道自动转 OPEN
     * 并清空本列（写状态日志 change_source=SCHEDULED + 打 3 天人工锁 + 清缓存 + 通知关注者）。
     * <p>
     * <b>为什么不是一个新增的 UPCOMING 状态枚举值</b>：本列的值是「日期 × 今天」的
     * <b>函数</b>而非独立事实——落库会出现「开业日已过、库里还写着即将开业」的假状态
     * （同活动域 NOT_STARTED 由 ActivityStateResolver 派生而不落库）；且不新增枚举
     * ⇒ 所有基于存储态的守卫（热度上报「非营业禁报」、报告类型守卫、V25 人工锁与豁免
     * 判定）天然正确、零改动。详见 V29 迁移注释。
     * <p>
     * null = 无开业计划（存量门店与「计划已兑现」的全量常态）。
     */
    private LocalDate expectedOpenDate;

    /**
     * 门店类型（2026-09-13 新增，V24 迁移）。列默认值唯一声明通道 = @ColumnDefault。
     * <p>
     * 与 {@link VenueStatus} 正交：状态管"现在开不开"，类型管"它是什么"。
     * <b>地址可见性由本字段派生</b>——{@code cityOnlyAddress} 的类型（歌友会）不落
     * 精确地址、公开响应只到城市级，详见 {@link VenueType} 类注释与
     * {@code VenueResponseMapper} 脱敏闸门。默认 HALL（存量门店全量回退值）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'HALL'")
    private VenueType venueType = VenueType.HALL;

    /** 封面图片 URL */
    @Column(length = 500)
    private String imageUrl;

    // ===== 相册（JSON 数组字符串, 如 ["url1","url2"]，与 tags 同模式） =====

    @Column(length = 5000)
    private String photos;

    /** 简介 */
    @Column(length = 500)
    private String description;

    // ===== 地址 =====

    @Column(nullable = false, length = 50)
    private String city;

    /** 区/县，选填（2026-08-08 放宽：行政区非业务必填，城市已足够定位粒度；缺失时展示以 '' 兜底） */
    @Column(length = 50)
    private String district;

    @Column(length = 200)
    private String address;

    private Double longitude;
    private Double latitude;

    // ===== 营业时间（时段列表） =====

    /**
     * 营业时段列表 JSON，如
     * [{"name":"午场","open":"13:30","close":"17:30"},{"name":"晚场","open":"18:30","close":"01:00"}]。
     * 与 tickets/partnerFees 同模式（变长结构化列表 → JSON 数组字符串列，DTO 序列化/反序列化）。
     * <p>
     * 建模背景（2026-08-08，根因见 AGENTS.md「场所数据模型」）：旧固定列
     * （afternoon_open/afternoon_close/evening_open/evening_close）把"1 个舞厅 → N 个场次"
     * 的业务维度硬编码成 2 个固定场次，新增场次需改表结构；本列改为时段列表，
     * 时段数量与命名自由。
     * <p>
     * 跨天契约：close &lt; open 表示结束于次日凌晨（如 18:30-01:00），原样存取；
     * name 可空（空时段展示省略前缀）；open/close 必填（请求端 @Valid 校验）。
     */
    @Column(length = 1000)
    private String businessHours;

    // ===== 消费（JSON 数组字符串，与 tags/photos 同模式） =====

    /**
     * 门票规则列表 JSON，如 [{"label":"下午4点前","type":"FREE"},{"label":"晚场","type":"FIXED","price":30}]。
     * 舞厅无"人均消费"概念，门票形态多样（固定票/免票/时段免票），用规则列表表达。
     */
    @Column(length = 2000)
    private String tickets;

    /** 舞伴费用阶梯 JSON，如 [{"minutes":5,"price":30},{"minutes":10,"price":50}] */
    @Column(length = 1000)
    private String partnerFees;

    // ===== 联系方式 =====

    @Column(length = 20)
    private String contactPhone;

    /** 微信二维码 URL */
    @Column(length = 500)
    private String wechatQr;

    // ===== 标签（JSON 数组字符串, 如 ["爵士","商务"]） =====

    @Column(length = 500)
    private String tags;

    /** 排序权重，越大越靠前。列默认值唯一声明通道 = @ColumnDefault（见 AGENTS.md「Schema 演进」） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private Integer sortWeight = 0;

    // ===== 门店认领 =====

    /** 认领人用户 ID（qwt_users.id），null 表示未被认领。
     *  认领后该用户获得门店管理权（发布动态等），与平台管理员共享管理入口可见性。 */
    private Long claimedBy;

    // ===== 门店照片同步（2026-09-11，V66） =====

    /**
     * 已标记不参与高德图片<b>批量</b>同步（2026-09-11 新增）：
     * <ul>
     *   <li>置位来源：① 用户主动清除门店照片（photo-sync/clear，人工判定错配拒绝高德图）；
     *       ② V66 存量回填——迁移时刻所有缺图门店（原批量同步候选）统一带标；</li>
     *   <li>生效范围：批量同步候选查询（findMissingImages / countMissingImages）排除，
     *       管理端「缺图待同步」计数与一键同步不再触碰；单店人工重匹配（retrySync，
     *       显式操作）不受限，成功后门店自然离开缺图口径；</li>
     *   <li>default false：新建门店不阻塞首次批量同步。</li>
     * </ul>
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean photoSyncExcluded = false;

    // ===== 状态权威层级（2026-09-14，V25；方案见 docs/agents/48） =====
    //
    // 解决「管理员手工修正的状态隔天被每日舞讯冲掉」：status 原先没有所有权模型，
    // 任何写入方都是 last-write-wins。本组字段把「人工 > 外部舞讯推断」的层级
    // 落成可判定的数据，门禁判定唯一实现 = VenueStatusGuardService。

    /**
     * 状态值归谁所有：MANUAL 人工直改 / SYNC 外部舞讯推断 / null = 旧数据或系统默认。
     * <p>
     * 仅表达「这个值代表谁的判断」（权威层级依据），与 {@code VenueStatusLog.changeSource}
     * （表达「谁写的」通道标签）分工不重叠，切勿混用。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private VenueStatusSource statusSource;

    /**
     * 人工锁到期时刻（null = 无锁）。非空且在将来 ⇒ 外部舞讯通道（{@code applyBatch} /
     * {@code applyBatchSuspend}）禁止覆盖本店状态，逐店跳过并计入返回体 skippedLocked。
     * <p>
     * 到期即自动失效，无需清理任务——「人工优先」是有时限的优先权，不是永久黑名单。
     * 时长按人工设定的目标状态不对称（3 天 / 7 天，运营配置可改），理由见 V25 迁移注释。
     */
    private LocalDateTime statusLockedUntil;

    /**
     * 永久豁免：不参与舞讯白名单 / 未上榜差集推断（人工声明的**结构性**例外）。
     * <p>
     * 适用「该店不在舞讯覆盖范围」「被舞讯系统性漏报」——这类问题不在时间维度上，
     * 靠反复加长人工锁是打补丁；改由人工一次性声明豁免，直到人工撤销。
     */
    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean dailySyncExempt = false;

    /** 人工备注：改状态 / 设豁免的原因（后台可读，可空）。 */
    @Column(length = 200)
    private String syncNote;
}
