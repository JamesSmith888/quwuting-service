package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.ColumnDefault;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;

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
     * 资料状态。列默认值唯一声明通道 = @ColumnDefault（见 AGENTS.md「Schema 演进」）。
     * 默认 PENDING：所有新资料必须经管理员认证后才公开（真实个人隐私边界的第一道闸）。
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 20, nullable = false)
    @ColumnDefault("'PENDING'")
    private DancerStatus status = DancerStatus.PENDING;

    /** 创建人用户 ID（舞伴主动注册 = 本人认领；后台创建 = 管理员 ID） */
    @Column(nullable = false)
    private Long createdBy;

    /**
     * 常驻城市（冗余筛选字段，创建时填写；不构成与舞厅的强绑定——
     * 舞伴的场所归属以 DancerVenue 关系表为准，本字段仅服务列表按城市筛选）。
     */
    @Column(length = 50)
    private String city;
}
