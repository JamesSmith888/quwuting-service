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

    /**
     * 单用户分享动作明细（2026-08-28 管理端用户详情下钻，docs/agents/23）：只计
     * SHARE（分享动作），时间倒序——「分享 N 次」统计点击查看每条明细的数据源。
     */
    List<VenueShare> findByUserIdAndEventTypeOrderByCreatedAtDesc(Long userId, ShareEventType eventType);

    /**
     * 对账口径·批量：一批**活动**的事件数（2026-09-16，V28，docs/agents/49-venue-activities.md §8）。
     * <p>
     * 用途是回答那个唯一能拿去跟门店谈的问题——「**这条活动被传播了多少次 / 带来了多少人
     * 打开**」。SHARE = 传播次数（分享意图），OPEN = 卡片被点开次数（真实回流）；
     * 两者差值即"传了但没人看"，是判断分享文案/卡片图是否有效的信号。
     * <p>
     * 与 23 号贡献档案的 countGroupByUserIdsAndEventType 同构（同一张表、同一种聚合形态，
     * 只是分组键从 userId 换成 activityId）——刻意不合并成一个"万能分组"方法：
     * 分组键不同就是两个查询意图，硬合并会引入字符串参数决定 group by 的动态 JPQL。
     * <p>
     * GROUP BY 批量而非逐条 count：管理端列表一页 10~20 条，逐条就是 N+1。
     *
     * @return 每行 = [activityId, count]；无事件的 activityId 不出现（调用方按 0 兜底）
     */
    @Query("SELECT s.activityId, COUNT(s) FROM VenueShare s " +
           "WHERE s.activityId IN :activityIds AND s.eventType = :eventType " +
           "GROUP BY s.activityId")
    List<Object[]> countGroupByActivityIdsAndEventType(@Param("activityIds") Collection<Long> activityIds,
                                                       @Param("eventType") ShareEventType eventType);
}
