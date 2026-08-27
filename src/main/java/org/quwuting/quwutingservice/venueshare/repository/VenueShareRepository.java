package org.quwuting.quwutingservice.venueshare.repository;

import org.quwuting.quwutingservice.venueshare.entity.VenueShare;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

/**
 * 场所分享事件日志仓库（纯 append，事件日志语义：每次分享/打开都是独立事件）。
 * <p>
 * 2026-08-27 贡献档案（docs/agents/23）：新增批量统计——「分享」贡献只计
 * event_type = SHARE（分享动作，actor = user_id；OPEN = 被分享者打开，归因给
 * share_from，不是分享者本人的行为，不计）。
 */
public interface VenueShareRepository extends JpaRepository<VenueShare, Long> {

    /**
     * 批量统计：指定用户集的分享动作数（2026-08-27 贡献档案/管理端用户列表聚合，
     * docs/agents/23）：只计 SHARE（分享动作），OPEN 归因不计。返回
     * Object[]{userId, count}；无分享用户不出现在结果（调用方按 0 兜底）。
     */
    @Query("SELECT s.userId, COUNT(s) FROM VenueShare s " +
           "WHERE s.userId IN :userIds AND s.eventType = :eventType GROUP BY s.userId")
    List<Object[]> countGroupByUserIdsAndEventType(@Param("userIds") Collection<Long> userIds,
                                                   @Param("eventType") ShareEventType eventType);
}
