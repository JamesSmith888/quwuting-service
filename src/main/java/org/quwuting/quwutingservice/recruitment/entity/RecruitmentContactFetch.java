package org.quwuting.quwutingservice.recruitment.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.quwuting.quwutingservice.base.BaseEntity;

/**
 * 招工联系方式获取留痕（V61）。
 * <p>
 * 用户点击「获取联系方式」时一记（唯一键 recruitment_id + user_id，原生 upsert
 * 幂等写入）——管理端「N 人获取联系方式」效果反馈的数据源，不重复计数。
 */
@Getter
@Setter
@Entity
@Table(name = "qwt_recruitment_contacts", indexes = {
        @Index(name = "qwt_idx_recruitment_contacts_unique", columnList = "recruitmentId, userId", unique = true)
})
public class RecruitmentContactFetch extends BaseEntity {

    @Column(nullable = false)
    private Long recruitmentId;

    @Column(nullable = false)
    private Long userId;
}
