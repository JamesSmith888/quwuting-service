package org.quwuting.quwutingservice.venuepresence.repository;

import org.quwuting.quwutingservice.venuepresence.entity.VenueVisitThank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;

/**
 * 门店到访感谢仓储（2026-10-13，V50；docs/agents/52-venue-presence.md「到访感谢」节）。
 * <p>
 * 写入 = 三元组幂等插入（{@link #thank}）：受影响行数 <b>1 = 首次感谢</b>
 * （唯一触发被感谢站内信的机会）、<b>2/0 = 重复感谢/并发双击</b>（已感谢过，不重发）——
 * 判定全由 DB 派生、无应用层锁、无标志列（对齐 {@code VenueCrowdReportLikeRepository#like}）。
 * 本域<b>没有取消动作</b>：感谢是一次性的心意表达，「同一对永不发第二次」由唯一键
 * 结构性保证。
 */
public interface VenueVisitThankRepository extends JpaRepository<VenueVisitThank, Long> {

    /**
     * 感谢一次（幂等插入）：新对插入（affected=1）；已感谢过的对 → 命中唯一键、
     * 仅刷新 updated_at（affected=2 或 0，均不重复通知）。
     * <p>
     * ⚠️ 时间口径：created_at/updated_at 由 Java 传 JVM {@code LocalDateTime.now()}
     * （北京时间），禁 DB now()——全库约定（同 {@code VenuePresencePingRepository#upsertInBucket}）。
     *
     * @return 受影响行数：1 = 首次感谢（调用方据此履行被感谢通知义务）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_visit_thanks "
            + "(created_at, updated_at, deleted, venue_id, thank_user_id, target_user_id) "
            + "VALUES (:now, :now, false, :venueId, :thankUserId, :targetUserId) "
            + "ON DUPLICATE KEY UPDATE deleted = false, updated_at = :now",
            nativeQuery = true)
    int thank(@Param("venueId") Long venueId,
              @Param("thankUserId") Long thankUserId,
              @Param("targetUserId") Long targetUserId,
              @Param("now") LocalDateTime now);

    /**
     * 我（感谢者）在某店已感谢过的贡献者 ID 集——「还差谁没感谢」的差集计算数据源
     * （status/thank 的 pendingThanks）。走唯一键前缀 (venue_id, thank_user_id)，
     * 行数 = 我感谢过的目标数（个位数级）。
     */
    @Query("SELECT t.targetUserId FROM VenueVisitThank t "
            + "WHERE t.venueId = :venueId AND t.thankUserId = :thankUserId AND t.deleted = false")
    Collection<Long> findTargetUserIds(@Param("venueId") Long venueId,
                                       @Param("thankUserId") Long thankUserId);

    /**
     * 某贡献者在一家店累计收到的<b>不同感谢者</b>数（被感谢站内信合并正文的 N）。
     * 走 {@code qwt_idx_vvt_target (target_user_id, venue_id)} 索引。
     * <p>
     * <b>为什么是 COUNT(DISTINCT thank_user_id) 而不是行数</b>：唯一键已保证
     * (感谢者, 贡献者) 不重复，两者当前同值；但口径写成 DISTINCT 表达的是语义——
     * 「多少位舞友感谢了你」（人数），未来若加软删恢复类逻辑也不会漂移。
     */
    @Query("SELECT COUNT(DISTINCT t.thankUserId) FROM VenueVisitThank t "
            + "WHERE t.venueId = :venueId AND t.targetUserId = :targetUserId AND t.deleted = false")
    long countThankers(@Param("venueId") Long venueId, @Param("targetUserId") Long targetUserId);
}
