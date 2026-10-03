package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenuePresenceConsent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 到店足迹开关状态流水仓储（2026-09-29 四轮 V34；2026-10-03 五轮「到店首问 + 服务端门禁」；
 * 文档 = docs/agents/52-venue-presence.md §5）。
 * <p>
 * 当前态口径 = 每用户最新一条（created_at DESC, id DESC）。单用户当前态走派生查询
 * （采集门禁热路径，命中 {@code qwt_idx_vpcons_user_created}）；全量分布统计需要
 * 「每用户最新一条」，JPQL 无 FROM 派生表能力，走 native MySQL 8 窗口函数——受
 * {@code VenueListQueryHqlSyntaxTest} 括号配平门禁约束，改写后必跑。
 * <p>
 * ⚠️ 2026-10-03 删除 {@code insertDefaultIfAbsent}（首次 ping 补记 DEFAULT 行）：默认开启模型
 * 退役后，「首次 ping 时没有任何 consent 行」只可能是旧版默认开启端（未经询问）——它恰是
 * 门禁要拒收的对象，不该再被补记成一条看似合法的状态确立。
 */
public interface VenuePresenceConsentRepository extends JpaRepository<VenuePresenceConsent, Long> {

    /**
     * 用户当前态 = 最新一条状态确立行（采集门禁数据源；无行 = 从未确立）。
     * 次序键 id DESC：同一毫秒连拨两次时以后写者为准，与分布统计的窗口函数次序一致。
     */
    Optional<VenuePresenceConsent> findFirstByUserIdAndDeletedFalseOrderByCreatedAtDescIdDesc(Long userId);

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
     * 到店首问的回答分布（admin 统计：首问文案效果的直接度量）：enabled → 回答过的去重用户数。
     * 同一用户多端各答一次时两边各计 1（量级可忽略，不为此做去重裁决）。
     * <p>
     * 返回 Object[]{enabled(Boolean), users(Long)}。
     */
    @Query("SELECT c.enabled, COUNT(DISTINCT c.userId) FROM VenuePresenceConsent c " +
            "WHERE c.source = org.quwuting.quwutingservice.venuepresence.enums.ConsentSource.PROMPT " +
            "AND c.deleted = false GROUP BY c.enabled")
    List<Object[]> countPromptAnswersByEnabled();

    /**
     * 近 N 天用户手动变更次数（admin 统计：开关热度趋势的分子）。
     * 只数 USER 来源——首问回答是一次性决定（另有 {@link #countPromptAnswersByEnabled}），
     * DEFAULT 是历史补记，都不是「改设置」。
     */
    @Query("SELECT COUNT(c) FROM VenuePresenceConsent c " +
            "WHERE c.source = org.quwuting.quwutingservice.venuepresence.enums.ConsentSource.USER " +
            "AND c.createdAt >= :since AND c.deleted = false")
    long countUserChangesSince(@Param("since") LocalDateTime since);
}
