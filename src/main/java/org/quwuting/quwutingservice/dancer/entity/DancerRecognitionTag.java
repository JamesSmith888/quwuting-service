package org.quwuting.quwutingservice.dancer.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;
import org.quwuting.quwutingservice.dancer.DancerTagCode;

/**
 * 认可记录携带的标签（标签来源 = 用户认可行为）。
 * <p>
 * 每条认可（DancerRecognition）可携带 0-N 个字典标签（服务层限制每次最多 3 个，
 * 见 {@code DancerService#MAX_TAGS_PER_RECOGNITION}），tag 必须是 {@link DancerTagCode}
 * 字典内代码（后台维护，禁止用户自由创建——防色情/攻击/广告/竞对刷评价）。
 * <p>
 * dancerId / userId 冗余存储：便于按舞伴聚合标签计数（无 JPA 关系，遵循项目
 * "纯 Long 列 + 索引"模式，同 DancerVenue）。取消认可时服务层级联物理删除本行。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_dancer_recognition_tags", indexes = {
        @Index(name = "qwt_idx_drt_dancer_tag", columnList = "dancerId, tag"),
        @Index(name = "qwt_idx_drt_dancer_created", columnList = "dancerId, createdAt"),
        @Index(name = "qwt_idx_drt_recognition", columnList = "recognitionId")
}, uniqueConstraints = {
        @UniqueConstraint(name = "qwt_uk_drt_recognition_tag", columnNames = {"recognitionId", "tag"})
})
public class DancerRecognitionTag extends BaseEntity {

    /** 所属认可记录 ID（qwt_dancer_recognitions.id） */
    @Column(nullable = false)
    private Long recognitionId;

    /** 冗余舞伴 ID（按舞伴聚合标签计数用） */
    @Column(nullable = false)
    private Long dancerId;

    /** 冗余用户 ID（我的认可明细用） */
    @Column(nullable = false)
    private Long userId;

    /** 对应 {@link DancerTagCode} 的枚举名 */
    @Column(nullable = false, length = 50)
    private String tag;
}
