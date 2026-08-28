package org.quwuting.quwutingservice.appfeedback.repository;

import org.quwuting.quwutingservice.appfeedback.entity.AppFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

import java.util.List;

/**
 * 平台级意见反馈仓库。
 * <p>
 * 用户侧读路径 = userId 倒序（「我的反馈」列表）；管理侧 = Specification 组合
 * 筛选分页（状态），与 venuefeedback 同模式。PENDING 计数供「上报管理」红点聚合。
 */
public interface AppFeedbackRepository extends JpaRepository<AppFeedback, Long>,
        JpaSpecificationExecutor<AppFeedback> {

    List<AppFeedback> findByUserIdOrderByCreatedAtDesc(Long userId);

    long countByStatus(ReportStatus status);
}
