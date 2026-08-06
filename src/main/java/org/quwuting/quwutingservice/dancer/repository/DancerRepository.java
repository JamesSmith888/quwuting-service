package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface DancerRepository extends JpaRepository<Dancer, Long> {

    Optional<Dancer> findByIdAndDeletedFalse(Long id);

    /** 批量查询（列表页/详情页一次 IN 查询覆盖整页舞伴，规避 N+1） */
    @Query("SELECT d FROM Dancer d WHERE d.id IN :ids AND d.deleted = false")
    List<Dancer> findByIds(@Param("ids") List<Long> ids);

    /** 我的舞伴主页列表（创建人视角，含 PENDING/HIDDEN 自有资料） */
    List<Dancer> findByCreatedByAndDeletedFalseOrderByUpdatedAtDesc(Long createdBy);

    /**
     * 公开舞伴列表（仅 NORMAL），按近7天认可数倒序（时间属性优先，避免老数据永久占优），
     * 同分以 id 倒序兜底（新资料优先）。返回 Object[]：
     * {id, nickname, avatar_url, bio, gender, city, count_all, count_today, count_7d}。
     * <p>
     * 近7天窗口为滚动锚点（createdAt >= now-7d，与 Reaction 同口径）；排序只依据
     * count7d 而非 countAll——"被认可的历史总量"不应让活跃度低的旧资料长期霸榜。
     * countQuery 与主查询共用城市过滤条件，保证分页总数与筛选一致。
     */
    @Query(value = """
            SELECT d.id, d.nickname, d.avatar_url, d.bio, d.gender, d.city,
                   COALESCE(a.cnt_all, 0) AS cnt_all,
                   COALESCE(a.cnt_today, 0) AS cnt_today,
                   COALESCE(a.cnt7, 0) AS cnt7
            FROM qwt_dancers d
            LEFT JOIN (
                SELECT dancer_id, COUNT(*) AS cnt_all,
                       COUNT(*) FILTER (WHERE created_at >= :sinceToday) AS cnt_today,
                       COUNT(*) FILTER (WHERE created_at >= :since7d) AS cnt7
                FROM qwt_dancer_recognitions WHERE deleted = false
                GROUP BY dancer_id
            ) a ON a.dancer_id = d.id
            WHERE d.status = 'NORMAL' AND d.deleted = false
              AND (:city IS NULL OR d.city = :city)
            ORDER BY COALESCE(a.cnt7, 0) DESC, d.id DESC
            """,
            countQuery = """
            SELECT COUNT(*) FROM qwt_dancers d
            WHERE d.status = 'NORMAL' AND d.deleted = false
              AND (:city IS NULL OR d.city = :city)
            """,
            nativeQuery = true)
    Page<Object[]> findPublicPage(@Param("city") String city,
                                  @Param("sinceToday") LocalDateTime sinceToday,
                                  @Param("since7d") LocalDateTime since7d,
                                  Pageable pageable);
}
