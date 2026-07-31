package org.quwuting.quwutingservice.venuefeedback.repository;

import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VenueFeedbackRepository extends JpaRepository<VenueFeedback, Long> {

    /** 按场所查询未处理反馈（管理端） */
    List<VenueFeedback> findByVenueIdAndHandledFalseOrderByCreatedAtDesc(Long venueId);

    /** 按场所查询全部反馈（管理端） */
    List<VenueFeedback> findByVenueIdOrderByCreatedAtDesc(Long venueId);
}
