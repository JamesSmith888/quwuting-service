package org.quwuting.quwutingservice.venueactivity.repository;

import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface VenueActivityRepository extends JpaRepository<VenueActivity, Long> {

    Optional<VenueActivity> findByIdAndDeletedFalse(Long id);

    /**
     * 门店详情页数据源：某门店的全部 PUBLISHED 活动（权重升序、id 倒序兜底）。
     * <p>
     * 刻意**不在 SQL 里过滤时效**：到期由 30s 调度强转 OFFLINE（单点状态机），
     * 查询只认状态；而"今天还有没有效 / 此刻是否命中"是 {@code ActivityState} 派生，
     * 由 {@code ActivityStateResolver} 单点负责。两边都过滤会出现同一条活动在
     * 不同路径上表现不一致（公告域明令禁止的写法）。
     */
    @Query("""
            SELECT a FROM VenueActivity a
            WHERE a.deleted = false AND a.status = :status AND a.venueId = :venueId
            ORDER BY a.sortWeight DESC, a.id DESC
            """)
    List<VenueActivity> findPublishedByVenue(@Param("venueId") Long venueId,
                                             @Param("status") ActivityStatus status);

    /**
     * 门店**列表页**数据源：一次 IN 查询批量取多店活动，避免列表页 N+1。
     * <p>
     * 列表页只需要"此刻命中的那一批"（方案 A：只在命中当前时段时打标），
     * 所以彻底不过滤时间段、只按状态与门店集合取回，命中判定全部交给派生器。
     */
    @Query("""
            SELECT a FROM VenueActivity a
            WHERE a.deleted = false AND a.status = :status AND a.venueId IN :venueIds
            ORDER BY a.sortWeight DESC, a.id DESC
            """)
    List<VenueActivity> findPublishedByVenueIds(@Param("venueIds") Collection<Long> venueIds,
                                                @Param("status") ActivityStatus status);

    /** 管理端列表（状态 / 门店 双重筛选，可空 = 不限制；id 倒序最新在前） */
    @Query("""
            SELECT a FROM VenueActivity a
            WHERE a.deleted = false
              AND (:status IS NULL OR a.status = :status)
              AND (:venueId IS NULL OR a.venueId = :venueId)
            ORDER BY a.id DESC
            """)
    Page<VenueActivity> findPageByFilters(@Param("status") ActivityStatus status,
                                          @Param("venueId") Long venueId,
                                          Pageable pageable);

    /**
     * 到期强转下线（@Scheduled 调用，状态权威）。
     * <p>
     * 判据 {@code endDate < today} ⇒ <b>结束当天仍然有效</b>，次日凌晨的第一次调度
     * 才把它转下线。{@code ALWAYS} 类型（长期有效）不参与，靠 {@code outerType}
     * 排除——不用 "endDate IS NULL" 判空，因为 enum 是显式契约、判空是隐式的。
     */
    @Modifying
    @Query("""
            UPDATE VenueActivity a SET a.status = :offline
            WHERE a.deleted = false AND a.status = :published
              AND a.outerType IN :expiringTypes
              AND a.endDate IS NOT NULL AND a.endDate < :today
            """)
    int expireDue(@Param("published") ActivityStatus published,
                  @Param("offline") ActivityStatus offline,
                  @Param("expiringTypes") Collection<ActivityOuterSchedule> expiringTypes,
                  @Param("today") LocalDate today);
}
