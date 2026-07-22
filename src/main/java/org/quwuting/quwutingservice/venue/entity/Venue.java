package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;

import java.time.LocalTime;

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

    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false, columnDefinition = "varchar(20) default 'OPEN'")
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

    @Column(nullable = false, length = 50)
    private String district;

    @Column(length = 200)
    private String address;

    private Double longitude;
    private Double latitude;

    // ===== 营业时间 =====

    /** 下午场开始 */
    private LocalTime afternoonOpen;
    /** 下午场结束 */
    private LocalTime afternoonClose;
    /** 晚场开始 */
    private LocalTime eveningOpen;
    /** 晚场结束 */
    private LocalTime eveningClose;

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

    /** 排序权重，越大越靠前 */
    @Column(nullable = false, columnDefinition = "int default 0")
    private Integer sortWeight = 0;

    // ===== 门店认领 =====

    /** 认领人用户 ID（qwt_users.id），null 表示未被认领。
     *  认领后该用户获得门店管理权（发布动态等），与平台管理员共享管理入口可见性。 */
    private Long claimedBy;
}
