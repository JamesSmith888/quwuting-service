package org.quwuting.quwutingservice.venueactivity.repository;

import org.quwuting.quwutingservice.venueactivity.entity.VenueActivityCheckin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface VenueActivityCheckinRepository extends JpaRepository<VenueActivityCheckin, Long> {

    /** 幂等查询前置：该用户该活动该日是否已打卡（并发由唯一键兜底） */
    boolean existsByActivityIdAndUserIdAndActivityDateAndDeletedFalse(Long activityId, Long userId, LocalDate activityDate);

    /**
     * 对账口径·批量：一批活动在指定日期的打卡数（管理端列表用，**一次查询取全部**）。
     * <p>
     * 刻意做成 GROUP BY 批量查询而不是逐个 activityId 调用：管理端列表一页 10~20 条，
     * 逐条 count 就是 N+1 —— 这是运营天天要看的页面，不值得留一处可预见的慢查询。
     *
     * @return 每行 = [activityId, count]
     */
    @Query("""
            SELECT c.activityId, COUNT(c) FROM VenueActivityCheckin c
            WHERE c.deleted = false AND c.activityId IN :activityIds AND c.activityDate = :date
            GROUP BY c.activityId
            """)
    List<Object[]> countByActivityIdsOnDate(@Param("activityIds") Collection<Long> activityIds,
                                            @Param("date") LocalDate date);

    /** 对账口径·批量：一批活动的累计打卡数（活动期间总到店人次） */
    @Query("""
            SELECT c.activityId, COUNT(c) FROM VenueActivityCheckin c
            WHERE c.deleted = false AND c.activityId IN :activityIds
            GROUP BY c.activityId
            """)
    List<Object[]> countByActivityIdsTotal(@Param("activityIds") Collection<Long> activityIds);
}
