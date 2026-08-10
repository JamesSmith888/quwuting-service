package org.quwuting.quwutingservice.dancer.entity;

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
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;

/**
 * 舞伴相册照片（独立实体——照片必须**逐张**审核，JSON 数组列无法表达逐张状态，
 * 故不复用 venue.photos 的 JSON 列模式；见 AGENTS.md「舞伴生态体系 · 相册与照片审核」）。
 * <p>
 * 可见性规则（与 Dancer 资料的可见性分层）：
 * <ul>
 *   <li>PENDING / REJECTED：仅照片上传者（createdBy）与平台管理员可见（本人编辑页回显状态）；</li>
 *   <li>PUBLIC：仅当所属舞伴 NORMAL（公开）时随主页公开展示——舞伴 HIDDEN/PENDING/REJECTED
 *       时主页本身不可见，照片自然不公开（详情服务先校验舞伴可见性，天然不泄露）。</li>
 * </ul>
 * sortOrder 为本人上传顺序（同名照片维持上传序，列表页封面取 sortOrder 最小的一张 PUBLIC）。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_photos", indexes = {
        @Index(name = "qwt_idx_dp_dancer", columnList = "dancerId"),
        @Index(name = "qwt_idx_dp_dancer_status", columnList = "dancerId, status"),
        @Index(name = "qwt_idx_dp_status_created", columnList = "status, createdAt")
})
public class DancerPhoto extends BaseEntity {

    /** 所属舞伴 ID */
    @Column(nullable = false)
    private Long dancerId;

    /** 公开访问 URL（Supabase Storage 直传返回，前端经 /storage/upload-token 签发） */
    @Column(nullable = false, length = 500)
    private String url;

    /** 审核状态（列默认值唯一声明通道 = @ColumnDefault；默认 PENDING = 上传即待审） */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private DancerPhotoStatus status = DancerPhotoStatus.PENDING;

    /** 上传人用户 ID（本人上传 = 舞伴创建人 createdBy；管理员后台代传 = 管理员 ID） */
    @Column(nullable = false)
    private Long createdBy;

    /** 展示顺序（上传追加，取当前最大值 +1；列表封面 = sortOrder 最小的一张 PUBLIC） */
    @Column(nullable = false)
    @ColumnDefault("0")
    private int sortOrder = 0;
}
