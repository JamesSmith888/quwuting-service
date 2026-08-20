package org.quwuting.quwutingservice.venue.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.venue.enums.VenuePhotoStatus;

/**
 * 门店相册照片（独立实体——照片必须**逐张**审核/管理，JSON 数组列无法表达逐张
 * 状态与归属；2026-08-20 由 venue.photos JSON 列升级，完整复用舞伴照片
 * （DancerPhoto）已验证的模式，见 AGENTS.md「门店照片域」根因链）。
 * <p>
 * 可见性规则：
 * <ul>
 *   <li>PENDING / REJECTED：仅上传者（createdBy）与平台管理员可见（本人管理入口
 *       回显状态）；</li>
 *   <li>PUBLIC：公开——随门店详情/列表轮播展示（详情/列表响应只含 PUBLIC）。</li>
 * </ul>
 * sortOrder 为上传顺序（上传追加，取当前最大值 +1），读路径按 sortOrder 升序展示。
 * 与舞伴照片的差异：门店照片无收费解锁需求，故无 blur_url 列；写者开放给任意
 * 登录用户（管理方直发 PUBLIC、普通用户 PENDING 先审后发，见 VenuePhotoService）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_venue_photos", indexes = {
        @Index(name = "qwt_idx_vp_venue", columnList = "venueId"),
        @Index(name = "qwt_idx_vp_venue_status", columnList = "venueId, status"),
        @Index(name = "qwt_idx_vp_status_created", columnList = "status, createdAt")
})
public class VenuePhoto extends BaseEntity {

    /** 所属门店 ID */
    @Column(nullable = false)
    private Long venueId;

    /** 公开访问 URL（Supabase Storage 直传返回，前端经 /storage/upload-token 签发） */
    @Column(nullable = false, length = 500)
    private String url;

    /** 审核状态（列默认值唯一声明通道 = @ColumnDefault；默认 PENDING = 上传即待审） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private VenuePhotoStatus status = VenuePhotoStatus.PENDING;

    /** 上传人用户 ID（普通用户 UGC / 门店管理方 / 管理员后台代传 = 各自 ID；存量导入 = 0） */
    @Column(nullable = false)
    private Long createdBy;

    /** 展示顺序（上传追加，取当前最大值 +1；详情/列表按升序展示） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int sortOrder = 0;
}
