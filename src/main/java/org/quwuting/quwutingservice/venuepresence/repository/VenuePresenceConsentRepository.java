package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 到店足迹开关状态流水仓储（2026-09-29 四轮，V34；文档 =
 * docs/agents/52-venue-presence.md）。
 * <p>
 * 当前态口径 = 每用户最新一条（窗口函数）；「每用户最新一条」在 JPQL 里表达不了
 * （无 FROM 派生表能力），分布统计走 native MySQL 8 窗口函数——受
 * {@code VenueListQueryHqlSyntaxTest} 括号配平门禁约束，改写后必跑。
 */
public interface VenuePresenceConsentRepository extends JpaRepository<VenuePresenceConsent, Long> {

    /**
     * 默认态确立（最少 DB 往返约束 = 单语句）：该用户**无任何** consent 行时补一条
     * enabled=true / source=DEFAULT——「默认开启人群」由此进入统计，无需用户动作。
     * <p>
     * 并发窗口可能双写 DEFAULT 行（无唯一约束，有意）：统计按「每用户最新一条」
     * 口径天然吸收，双 true 行无害；为它上唯一键反而把「USER 行之后又回落默认态」
     * 的语义搞复杂（用户手动关闭后没有「再默认」这种状态转移）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_presence_consents " +
            "(created_at, updated_at, deleted, user_id, enabled, source) " +
            "SELECT :now, :now, false, :userId, true, 'DEFAULT' FROM DUAL " +
            "WHERE NOT EXISTS (SELECT 1 FROM qwt_venue_presence_consents " +
            "WHERE user_id = :userId AND deleted = 0)",
            nativeQuery = true)
    void insertDefaultIfAbsent(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * 当前态分布（admin 统计数据源）：每用户最新一条的 (enabled, source) → 用户数。
     * ROW_NUMBER 按 (created_at DESC, id DESC)——同一秒连拨两次开关时以大 id 为准，
     * 与「后写的覆盖先写的」直觉一致。
     * <p>
     * 返回 Object[]{enabled(Boolean), source(String), users(Long)}。
     */
    @Query(value = "SELECT t.enabled, t.source, COUNT(*) AS users FROM (" +
            "SELECT c.user_id, c.enabled, c.source, " +
            "ROW_NUMBER() OVER (PARTITION BY c.user_id ORDER BY c.created_at DESC, c.id DESC) AS rn " +
            "FROM qwt_venue_presence_consents c WHERE c.deleted = 0) t " +
            "WHERE t.rn = 1 GROUP BY t.enabled, t.source",
            nativeQuery = true)
    List<Object[]> countLatestByEnabledAndSource();

    /**
     * 近 N 天用户手动变更次数（admin 统计：开关热度趋势的分子）。
     * 只数 USER 来源——DEFAULT 确立是采集触达的副产品，不是用户动作。
     */
    @Query("SELECT COUNT(c) FROM VenuePresenceConsent c " +
            "WHERE c.source = org.quwuting.quwutingservice.venuepresence.enums.ConsentSource.USER " +
            "AND c.createdAt >= :since AND c.deleted = false")
    long countUserChangesSince(@Param("since") LocalDateTime since);
}
