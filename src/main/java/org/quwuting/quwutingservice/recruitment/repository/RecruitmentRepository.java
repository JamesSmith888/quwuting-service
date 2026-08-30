package org.quwuting.quwutingservice.recruitment.repository;

import org.quwuting.quwutingservice.recruitment.entity.Recruitment;
import org.quwuting.quwutingservice.recruitment.enums.RecruitStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 招工信息仓储。
 * <p>
 * 用户侧列表谓词 = PUBLISHED 且未过期且门店未软删（EXISTS 子查询），过期硬过滤；
 * 管理端列表支持 status / venueId / keyword 过滤，独立「已过期」视图
 * （PUBLISHED 且 expires_at <= now，供一键续期决策）。
 */
public interface RecruitmentRepository extends JpaRepository<Recruitment, Long> {

    Optional<Recruitment> findByIdAndDeletedFalse(Long id);

    /**
     * 用户侧分页列表：急聘置顶 + 发布时间倒序（排序经 Pageable 传入）。
     */
    @Query("SELECT r FROM Recruitment r WHERE r.deleted = false "
            + "AND r.status = :status "
            + "AND r.expiresAt > :now "
            + "AND EXISTS (SELECT 1 FROM Venue v WHERE v.id = r.venueId AND v.deleted = false) "
            + "AND (:filterCity IS NULL OR r.venueId IN "
            + "  (SELECT v2.id FROM Venue v2 WHERE v2.deleted = false AND v2.city = :filterCity)) "
            + "AND (:filterVenueId IS NULL OR r.venueId = :filterVenueId)")
    Page<Recruitment> findPublished(@Param("status") RecruitStatus status,
                                    @Param("now") LocalDateTime now,
                                    @Param("filterCity") String filterCity,
                                    @Param("filterVenueId") Long filterVenueId,
                                    Pageable pageable);

    /**
     * 管理端分页列表（status 可空 = 全部；keyword 匹配描述）。
     */
    @Query("SELECT r FROM Recruitment r WHERE r.deleted = false "
            + "AND (:status IS NULL OR r.status = :status) "
            + "AND (:filterVenueId IS NULL OR r.venueId = :filterVenueId) "
            + "AND (:keyword IS NULL OR r.description LIKE %:keyword%)")
    Page<Recruitment> adminSearch(@Param("status") RecruitStatus status,
                                  @Param("filterVenueId") Long filterVenueId,
                                  @Param("keyword") String keyword,
                                  Pageable pageable);

    /**
     * 管理端「已过期」视图：PUBLISHED 且已过有效期（僵尸待续期/下架决策）。
     */
    @Query("SELECT r FROM Recruitment r WHERE r.deleted = false "
            + "AND r.status = :status "
            + "AND r.expiresAt <= :now "
            + "AND (:filterVenueId IS NULL OR r.venueId = :filterVenueId) "
            + "AND (:keyword IS NULL OR r.description LIKE %:keyword%)")
    Page<Recruitment> adminExpired(@Param("status") RecruitStatus status,
                                   @Param("now") LocalDateTime now,
                                   @Param("filterVenueId") Long filterVenueId,
                                   @Param("keyword") String keyword,
                                   Pageable pageable);

    /**
     * 批量统计各招工的联系方式获取人数（管理端列表「N 人获取」，单次 GROUP BY 防 N+1）。
     * 投影接口命名与 alias 对应（getRecruitmentid/getCnt）。
     */
    interface FetchCount {
        Long getRecruitmentid();

        Long getCnt();
    }

    @Query("SELECT f.recruitmentId AS recruitmentid, COUNT(f.id) AS cnt "
            + "FROM RecruitmentContactFetch f "
            + "WHERE f.deleted = false AND f.recruitmentId IN :ids "
            + "GROUP BY f.recruitmentId")
    List<FetchCount> countFetchesByRecruitmentIds(@Param("ids") Collection<Long> ids);
}
