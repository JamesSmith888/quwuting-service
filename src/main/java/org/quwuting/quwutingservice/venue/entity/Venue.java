package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;

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
}
