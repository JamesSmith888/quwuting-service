package org.quwuting.quwutingservice.venuestatuswatcher.repository;

import org.quwuting.quwutingservice.venuestatuswatcher.entity.VenueStatusWatcher;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * 关注门店营业状态仓储（见 AGENTS.md「关注门店营业状态通知」）。
 */
public interface VenueStatusWatcherRepository extends JpaRepository<VenueStatusWatcher, Long> {

    /** 某门店的全部关注者（状态变更通知发送查询路径，按 venue_id 索引） */
    List<VenueStatusWatcher> findByVenueIdAndDeletedFalse(Long venueId);

    /** 我是否关注了该门店（详情页开关态） */
    boolean existsByUserIdAndVenueIdAndDeletedFalse(Long userId, Long venueId);

    /** 取消关注（物理删除，幂等——不存在时影响行数 0 静默成功） */
    void deleteByUserIdAndVenueId(Long userId, Long venueId);
}
