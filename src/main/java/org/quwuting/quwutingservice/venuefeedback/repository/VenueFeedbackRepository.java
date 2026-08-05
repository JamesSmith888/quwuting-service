package org.quwuting.quwutingservice.venuefeedback.repository;

import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

public interface VenueFeedbackRepository extends JpaRepository<VenueFeedback, Long>,
        JpaSpecificationExecutor<VenueFeedback> {

    /**
     * 按场所查询某状态下报（管理端按场所维度使用）。
     * 平台级列表走 {@link #findAll(org.springframework.data.jpa.domain.Specification, org.springframework.data.domain.Pageable)}
     * 组合筛选（状态/类型可选），不在此派生。
     */
    List<VenueFeedback> findByVenueIdAndStatusOrderByCreatedAtDesc(Long venueId, ReportStatus status);

    /** 按场所查询全部上报（管理端按场所维度使用） */
    List<VenueFeedback> findByVenueIdOrderByCreatedAtDesc(Long venueId);
}
