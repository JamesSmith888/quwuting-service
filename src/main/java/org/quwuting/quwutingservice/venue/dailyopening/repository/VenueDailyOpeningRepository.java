package org.quwuting.quwutingservice.venue.dailyopening.repository;

import org.quwuting.quwutingservice.venue.dailyopening.entity.VenueDailyOpening;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * 门店每日营业快照仓储（2026-08-31，PG V63 / MySQL V3）。
 * <p>
 * upsert = 原生 INSERT ... ON DUPLICATE KEY UPDATE（MySQL 8；唯一键 = 生成列
 * uk_key_qwt_idx_daily_openings_unique，IF(deleted=0, MD5(venue_id#report_date#
 * source_id), NULL)——精确复刻 PG 部分唯一索引语义）——幂等确定性：同日同源重复
 * apply = UPDATE 原行（status/confidence 覆盖 + created_at 刷新），不产生新行；
 * 并发冲突由数据库兜底。
 * <p>
 * ⚠️ 时间口径（记忆红线，同 V59）：created_at/updated_at 必须由 Java 传入
 * LocalDateTime.now()（JVM 时区=北京时间），禁止 DB 端 now()——Supabase 会话
 * 时区是 UTC，DB now() 写入 UTC 墙钟，与 JVM 窗口比较错位 8h 恒不命中。
 */
public interface VenueDailyOpeningRepository extends JpaRepository<VenueDailyOpening, Long> {

    /** 某日多店快照（列表/详情注入「今日营业」数据源，一次 IN 防 N+1） */
    List<VenueDailyOpening> findByReportDateAndVenueIdInAndDeletedFalse(
            @Param("reportDate") LocalDate reportDate,
            @Param("venueIds") Collection<Long> venueIds);

    /**
     * 幂等 upsert：INSERT / ON DUPLICATE KEY UPDATE（刷新 status/confidence/
     * created_at）。冲突目标 = 生成列唯一索引 qwt_idx_daily_openings_unique
     * （venue_id#report_date#source_id，deleted=0 时非空）。
     * ⚠️ 时间口径：created_at/updated_at 必须由 Java 传 LocalDateTime.now()
     * （JVM 时区=北京时间），禁止 DB now()（同 VenueCrowdReportRepository）。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_daily_openings " +
            "(created_at, updated_at, deleted, venue_id, report_date, source_id, status, confidence) " +
            "VALUES (:createdAt, :updatedAt, false, :venueId, :reportDate, :sourceId, :status, :confidence) " +
            "ON DUPLICATE KEY UPDATE status = VALUES(status), " +
            "confidence = VALUES(confidence), " +
            "created_at = :createdAt, " +
            "updated_at = :updatedAt",
            nativeQuery = true)
    void upsert(@Param("venueId") Long venueId,
                @Param("reportDate") LocalDate reportDate,
                @Param("sourceId") String sourceId,
                @Param("status") String status,
                @Param("confidence") String confidence,
                @Param("createdAt") LocalDateTime createdAt,
                @Param("updatedAt") LocalDateTime updatedAt);
}
