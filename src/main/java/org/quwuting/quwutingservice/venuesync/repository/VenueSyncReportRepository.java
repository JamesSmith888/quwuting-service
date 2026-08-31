package org.quwuting.quwutingservice.venuesync.repository;

import org.quwuting.quwutingservice.venuesync.entity.VenueSyncReport;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

public interface VenueSyncReportRepository extends JpaRepository<VenueSyncReport, Long> {

    /**
     * 幂等 upsert：同渠道同报告日至多一条（生成列部分唯一索引，
     * MySQL 语法 ON DUPLICATE KEY UPDATE，与 VenueDailyOpeningRepository 同构）。
     * created_at 由 Java 传值（时区红线：JVM 北京时间，禁 DB now()）。
     */
    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO qwt_venue_sync_reports
                (created_at, updated_at, deleted, report_date, source_id, source_label, report_url, summary, items)
            VALUES
                (:now, :now, 0, :reportDate, :sourceId, :sourceLabel, :reportUrl, :summary, :items)
            ON DUPLICATE KEY UPDATE
                updated_at = :now, source_label = :sourceLabel, report_url = :reportUrl,
                summary = :summary, items = :items
            """)
    void upsert(@Param("reportDate") LocalDate reportDate,
                @Param("sourceId") String sourceId,
                @Param("sourceLabel") String sourceLabel,
                @Param("reportUrl") String reportUrl,
                @Param("summary") String summary,
                @Param("items") String items,
                @Param("now") LocalDateTime now);

    /** 历史列表（最新报告在前；report_date 相同按 id 倒序=最新上报优先） */
    List<VenueSyncReport> findByDeletedFalseOrderByReportDateDescIdDesc(Pageable pageable);
}
