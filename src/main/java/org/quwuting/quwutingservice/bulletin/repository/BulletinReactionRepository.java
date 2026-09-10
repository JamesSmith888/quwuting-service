package org.quwuting.quwutingservice.bulletin.repository;

import org.quwuting.quwutingservice.bulletin.entity.BulletinReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 快讯表态仓储（2026-09-10，docs/agents/47-bulletins.md「快讯表态」；V19）。
 * <p>
 * 与门店域 {@code VenueReactionRepository} 的差异：<b>无窗口统计、无聚合缓存 loader、
 * 无"当日票"锁</b>——一人一票由唯一键 {@code qwt_uk_br_user_bulletin} 直接承载，
 * 并发写入也只需一条 {@link #upsertReaction} 原子语句收口（门店域按 code 建约束、
 * 一票语义落不到键上，才不得不加 {@code FOR UPDATE} 咨询锁）。
 */
public interface BulletinReactionRepository extends JpaRepository<BulletinReaction, Long> {

    /** 当前用户对该快讯的表态行（至多一行：唯一键保证；取消即硬删，无历史行干扰） */
    Optional<BulletinReaction> findByUserIdAndBulletinId(Long userId, Long bulletinId);

    /**
     * 表态写入（参与 / 换票共用一条路径，确定性原子 upsert）。
     * <p>
     * 命中唯一键即原地改 code + updated_at（<b>换票</b>），未命中即插入（<b>首次参与</b>）——
     * 一条语句覆盖两种路径，并发同用户同快讯也只会收敛到一行（无需应用层锁）。
     * <p>
     * 注意：不用 {@code VALUES(col)} 形式（MySQL 8.0.20 起废弃），参数直接二次绑定；
     * 时间戳由 Java 传入（全库约定，禁 DB now()）。
     *
     * @return 受影响行数（1 = 插入，2 = 更新，MySQL 语义；本域不依赖返回值分支，仅用于日志）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_bulletin_reactions " +
                   "(user_id, bulletin_id, reaction_code, created_at, updated_at, deleted) " +
                   "VALUES (:userId, :bulletinId, :code, :now, :now, false) " +
                   "ON DUPLICATE KEY UPDATE reaction_code = :code, updated_at = :now, deleted = false",
           nativeQuery = true)
    int upsertReaction(@Param("userId") Long userId,
                       @Param("bulletinId") Long bulletinId,
                       @Param("code") String code,
                       @Param("now") LocalDateTime now);

    /**
     * 整页快讯的表态计数（列表接口用），一次 IN 查询覆盖多条内容。
     * 返回 {@code Object[]{bulletinId, reactionCode, count}}；count=0 的行不存在（GROUP BY）。
     * <p>
     * 不缓存：快讯列表每次请求的内容集合随分页变化，且表态量级远低于门店
     * （门店域为此专设聚合缓存 + 事务提交后失效链路），这里直接查库更简单也不可能陈旧。
     */
    @Query(value = "SELECT r.bulletin_id, r.reaction_code, COUNT(*) AS cnt " +
                   "FROM qwt_bulletin_reactions r " +
                   "WHERE r.bulletin_id IN :bulletinIds AND r.deleted = false " +
                   "GROUP BY r.bulletin_id, r.reaction_code",
           nativeQuery = true)
    List<Object[]> countByBulletinIdsGroupByCode(@Param("bulletinIds") List<Long> bulletinIds);

    /** 整页快讯中当前用户的表态（一人一票 ⇒ 每条内容至多一行）。返回 {@code Object[]{bulletinId, reactionCode}} */
    @Query("SELECT r.bulletinId, r.reactionCode FROM BulletinReaction r " +
           "WHERE r.userId = :userId AND r.bulletinId IN :bulletinIds")
    List<Object[]> findCodesByUserAndBulletinIds(@Param("userId") Long userId,
                                                 @Param("bulletinIds") List<Long> bulletinIds);
}
