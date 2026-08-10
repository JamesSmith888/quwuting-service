package org.quwuting.quwutingservice.venuestatusreport.repository;

import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface StatusReportRepository extends JpaRepository<VenueStatusReport, Long> {

    /** 查找用户对某场所的活跃报告（未逻辑删除） */
    Optional<VenueStatusReport> findByUserIdAndVenueIdAndDeletedFalse(Long userId, Long venueId);

    /**
     * 查找用户对某场所的报告（含逻辑删除的记录）。
     * 用于 upsert 恢复逻辑：撤销（soft delete）后再次上报时，
     * 需找到已软删的记录并恢复，而非 INSERT 新行（UNIQUE 约束会冲突）。
     * 与 FavoriteService.findByUserIdAndVenueId 同模式。
     */
    Optional<VenueStatusReport> findByUserIdAndVenueId(Long userId, Long venueId);

    /**
     * 活跃报告聚合：合并 COUNT + MAX(createdAt) 为 1 次往返。
     * 活跃 = 未删除且 createdAt >= since（TTL 窗口，由 Service 层计算）。
     */
    @Query("SELECT COUNT(r) as activeCount, MAX(r.createdAt) as latestTime " +
           "FROM VenueStatusReport r " +
           "WHERE r.venueId = :venueId AND r.deleted = false AND r.createdAt >= :since")
    ActiveReportStats countActiveAndLatestTime(@Param("venueId") Long venueId,
                                                @Param("since") LocalDateTime since);

    /**
     * 全局频率限制：统计用户在指定时间窗口内报告的不同场所数。
     * 用于防止恶意用户批量上报所有场所。
     */
    @Query("SELECT COUNT(DISTINCT r.venueId) FROM VenueStatusReport r " +
           "WHERE r.userId = :userId AND r.deleted = false AND r.createdAt >= :since")
    long countDistinctVenuesByUserIdSince(@Param("userId") Long userId,
                                           @Param("since") LocalDateTime since);

    /**
     * 当前用户的全部状态上报记录（「我的上报记录」数据源）。
     * <p>
     * 范围：仅未撤销（deleted=false）的记录——撤销是用户主动收回动作，soft delete 属内部
     * 实现细节，已撤销记录不再视为"上报记录"；包含已过期（TTL 外）记录，供前端标注
     * 「已过期」提醒用户可重新上报。active 判定不在 SQL 内完成（避免 SQL 层自行定义时间窗），
     * 由 Service 层按 {@code ACTIVE_REPORT_TTL_HOURS} 常量统一计算（TTL 唯一权威源）。
     * <p>
     * venueId 可选（2026-08-06）：null = 跨场所全部（个人中心「我的上报」区块）；
     * 非 null = 单门店（详情页「我的上报记录」弹窗，只展示当前门店记录）——两处消费
     * 共用同一查询，与 {@code venuefeedback.listMyFeedbacks(venueId)} 的可选过滤同构。
     * <p>
     * JOIN qwt_venues 一次取回场所名称/地址，消除 N+1（与 /admin/reports 的
     * findByIdInAndDeletedFalse 批量回填同思路，此处 JOIN 形态更直接）。
     * 不过滤 v.deleted：场所软删除后历史上报记录仍应展示原名（记录真实性不因场所下架而消失）。
     * <p>
     * 原生 SQL + 投影接口：跨表 JOIN + 排序形态 JPQL 可表达，但投影别名映射在 JPQL
     * constructor 表达式中需手写全字段，原生 SQL 更直观；getter 类型遵循
     * 「投影接口 getter 类型」约定（TIMESTAMP 列必须 LocalDateTime）。
     * 别名必须全小写（PG 将未引用标识符折叠为小写，`AS venueId` → venueid 会与
     * getVenueId 失配；全小写别名 + 全小写 getter 是 countHeatCounters 的既定模式）。
     * venueId 过滤条件 `:venueId IS NULL OR r.venue_id = :venueId` 参数化传值，
     * 不拼接 SQL（防注入分层约定见 TextSanitizer javadoc）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.created_at AS createdat, " +
                   "       v.name AS venuename, v.city AS venuecity, " +
                   "       v.district AS venuedistrict, v.address AS venueaddress " +
                   "FROM qwt_venue_status_reports r " +
                   "JOIN qwt_venues v ON v.id = r.venue_id " +
                   "WHERE r.user_id = :userId AND r.deleted = false " +
                   "  AND (:venueId IS NULL OR r.venue_id = :venueId) " +
                   "ORDER BY r.created_at DESC", nativeQuery = true)
    List<MyReportRow> findMyReportsByUserId(@Param("userId") Long userId,
                                            @Param("venueId") Long venueId);

    /**
     * 某门店最近暂停报列表（公开读，供详情页「报告暂停营业」弹层默认内容）。
     * <p>
     * 范围：TTL 窗口内（活跃）全部用户的报告，按时间倒序；窗口起点由 Service 层按
     * {@code ACTIVE_REPORT_TTL_HOURS} 常量计算传入（TTL 唯一权威源，SQL 不自行定义时间窗）。
     * 取报告者脱敏昵称需要 JOIN qwt_users（LEFT JOIN：用户被删等异常态回退匿名，
     * 不因关联缺失丢行——与 {@link #findMyReportsByUserId} 的 JOIN 策略一致，差异仅在
     * 本查询以 venue 为维度、不限定 user_id）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，见
     * {@link #findMyReportsByUserId} 注释的既定模式）。LIMIT 由 Service 层
     * {@code .limit()} 施加（列表页仅需最近 N 条，避免大结果集全量传输）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.user_id AS userid, " +
                   "       r.reason AS reason, r.created_at AS createdat, " +
                   "       u.nickname AS nickname " +
                   "FROM qwt_venue_status_reports r " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.venue_id = :venueId AND r.deleted = false AND r.created_at >= :since " +
                   "ORDER BY r.created_at DESC", nativeQuery = true)
    List<VenueReportRow> findRecentByVenue(@Param("venueId") Long venueId,
                                           @Param("since") LocalDateTime since);

    /** 投影接口：门店最近暂停报行（含报告者昵称，供 GET /venues/{id}/status-reports 使用） */
    interface VenueReportRow {
        Long getId();
        Long getVenueid();
        Long getUserid();
        String getReason();
        LocalDateTime getCreatedat();
        String getNickname();
    }

    /**
     * 管理端活跃暂停报列表（需 ADMIN，跨场所全量）。
     * <p>
     * 范围：TTL 窗口内（活跃）全部未删除报告，按时间倒序分页——管理端「上报管理 →
     * 暂停营业」tab 数据源（2026-08-10 新增，落实 AGENTS.md「场所状态上报」L693
     * "管理员可在管理后台查看活跃报告"的后续约定）。窗口起点由 Service 层按
     * {@code ACTIVE_REPORT_TTL_HOURS} 常量计算传入（TTL 唯一权威源，SQL 不自行定义时间窗）。
     * <p>
     * 与公开列表 {@link #findRecentByVenue} 的差异：① 管理端上下文**不做昵称脱敏**
     * （返回真实昵称 + userId，管理员需识别上报者）；② 返回 {@code note}（补充说明，
     * 审核安全约定"note 仅管理端可见"，见 AGENTS.md L1897，公开响应禁止携带）；
     * ③ JOIN qwt_venues 取场所名（管理端逐条核对场所，与 /admin/reports 同思路）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，见
     * {@link #findMyReportsByUserId} 注释的既定模式）；countQuery 与主查询同谓词，
     * 供 Spring Data 分页取总数（一次往返内完成 count + content）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.user_id AS userid, " +
                   "       r.reason AS reason, r.note AS note, r.occurred_at AS occurredat, r.created_at AS createdat, " +
                   "       v.name AS venuename, u.nickname AS nickname " +
                   "FROM qwt_venue_status_reports r " +
                   "JOIN qwt_venues v ON v.id = r.venue_id " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.deleted = false AND r.created_at >= :since " +
                   "ORDER BY r.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM qwt_venue_status_reports r " +
                        "WHERE r.deleted = false AND r.created_at >= :since",
           nativeQuery = true)
    Page<AdminReportRow> findActiveReports(@Param("since") LocalDateTime since, Pageable pageable);

    /**
     * 管理端活跃暂停报计数（需 ADMIN，跨场所全量）。
     * 活跃 = 未删除且 createdAt >= since（TTL 窗口，由 Service 层计算）——FAB「上报管理」
     * 红点聚合数据源之一（与 venuefeedback PENDING 计数合并为管理端上报待办总数）。
     */
    @Query("SELECT COUNT(r) FROM VenueStatusReport r " +
           "WHERE r.deleted = false AND r.createdAt >= :since")
    long countActiveReports(@Param("since") LocalDateTime since);

    /** 投影接口：管理端活跃暂停报行（含上报者身份 + 场所名 + note，供 /admin/status-reports 使用） */
    interface AdminReportRow {
        Long getId();
        Long getVenueid();
        Long getUserid();
        String getReason();
        String getNote();
        LocalDateTime getOccurredat();
        LocalDateTime getCreatedat();
        String getVenuename();
        String getNickname();
    }

    /**
     * 管理端移除暂停报：软删除指定报告（需 ADMIN，幂等）。
     * <p>
     * 移除 = 平台清理虚假/失效信号：deleted 置 true 后，所有"活跃"查询（热度计数/
     * 公开列表/管理端列表）立即过滤掉该报告——公开视图即时消失，无需等 TTL 过期。
     * 条件带 {@code deleted = false}：已移除/不存在影响行数为 0，幂等静默成功
     * （与用户自撤 cancelReport 同软删语义，差异仅在于操作者是管理员而非上报者本人）。
     * 调用方（Service）需在移除后失效 venueHeat 缓存（活跃计数是热度输出之一）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VenueStatusReport r SET r.deleted = true WHERE r.id = :id AND r.deleted = false")
    int softDeleteById(@Param("id") Long id);

    /**
     * 续期 TTL：更新已有报告的 created_at（upsert 覆盖/软删恢复路径专用）。
     * <p>
     * <b>根因（2026-08-10 修复）</b>：{@code BaseEntity.createdAt} 标注
     * {@code @CreationTimestamp}，Hibernate 将其视为<b>不可变属性</b>——实体
     * setter（{@code report.setCreatedAt(now)}）在 UPDATE 时被静默忽略（WARN
     * HHH000502），生成的 UPDATE 语句不含 {@code created_at} 列。原实现意图
     * "刷新 createdAt 续期 4h TTL" 从未生效：旧 {@code created_at} 超出 TTL 窗口后，
     * 详情页 {@code hasMyStatusReport}（EXISTS 带 TTL 过滤）为 false、公开列表
     * （TTL 过滤）查不到 → 用户"刚报告的记录消失"。此为框架不可变属性与"续期"
     * 语义冲突的典型案例：<b>@CreationTimestamp 字段禁止用实体 setter 改，
     * 只能经 JPQL 批量更新直写列</b>（批量更新不走实体生命周期，不受不可变约束）。
     * <p>
     * 幂等：只更新目标记录（id 定位），无并发冲突风险（与 upsert 的单行语义一致）。
     * 调用方必须在 upsert 实体保存之后调用（保证行存在）。
     * <p>
     * <b>flushAutomatically = true 是正确性前提</b>：调用方在 bulk 更新前已对实体
     * setDeleted/setReason 等做脏修改（save 挂起），若不先 flush，clearAutomatically
     * 会把未落库的实体修改一并清掉（deleted 恢复丢失）；flush 先行保证实体更新先落库、
     * 再直写 created_at，顺序确定。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE VenueStatusReport r SET r.createdAt = :createdAt WHERE r.id = :id")
    int renewCreatedAt(@Param("id") Long id, @Param("createdAt") LocalDateTime createdAt);

    /** 投影接口：我的上报记录行（含场所信息，供 GET /status-reports/mine 使用） */
    interface MyReportRow {
        Long getId();
        Long getVenueid();
        LocalDateTime getCreatedat();
        String getVenuename();
        String getVenuecity();
        String getVenuedistrict();
        String getVenueaddress();
    }

    /** 投影接口：活跃报告聚合结果 */
    interface ActiveReportStats {
        Long getActiveCount();
        LocalDateTime getLatestTime();
    }
}
