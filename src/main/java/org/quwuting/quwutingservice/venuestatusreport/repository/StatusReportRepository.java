package org.quwuting.quwutingservice.venuestatusreport.repository;

import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
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
     * 活跃 = 未删除且 expiresAt > now（TTL 唯一事实源 = expires_at 列，2026-08-11
     * 由 created_at >= since 迁移，now 由 Service 层传入）。
     */
    @Query("SELECT COUNT(r) as activeCount, MAX(r.createdAt) as latestTime " +
           "FROM VenueStatusReport r " +
           "WHERE r.venueId = :venueId AND r.deleted = false AND r.expiresAt > :now")
    ActiveReportStats countActiveAndLatestTime(@Param("venueId") Long venueId,
                                                @Param("now") LocalDateTime now);

    /**
     * 全局频率限制：统计用户在指定时间窗口内报告的不同场所数。
     * 用于防止恶意用户批量上报所有场所（滑动窗口按 created_at，非 TTL 语义）。
     */
    @Query("SELECT COUNT(DISTINCT r.venueId) FROM VenueStatusReport r " +
           "WHERE r.userId = :userId AND r.deleted = false AND r.createdAt >= :since")
    long countDistinctVenuesByUserIdSince(@Param("userId") Long userId,
                                           @Param("since") LocalDateTime since);

    /**
     * 每日上报上限：统计用户当日（自然日 0 点起）报告次数（2026-08-11 新增，
     * 与 {@link #countDistinctVenuesByUserIdSince} 的滑动窗口互补——批量刷同一批门店
     * 由本计数兜底）。
     */
    @Query("SELECT COUNT(r) FROM VenueStatusReport r " +
           "WHERE r.userId = :userId AND r.deleted = false AND r.createdAt >= :dayStart")
    long countReportsByUserSince(@Param("userId") Long userId,
                                  @Param("dayStart") LocalDateTime dayStart);

    /**
     * 当前用户的全部状态上报记录（「我的上报记录」数据源）。
     * <p>
     * 范围：仅未撤销（deleted=false）的记录——撤销是用户主动收回动作，soft delete 属内部
     * 实现细节，已撤销记录不再视为"上报记录"；包含已过期（TTL 外）记录，供前端标注
     * 「已过期」提醒用户可重新上报。active 判定不在 SQL 内完成（避免 SQL 层自行定义
     * 时间窗），由 Service 层按 {@code expires_at} 列与 now 比较（TTL 唯一事实源 = 列）。
     * <p>
     * venueId 可选（2026-08-06）：null = 跨场所全部（个人中心「我的上报」区块）；
     * 非 null = 单门店（详情页「我的上报记录」弹窗，只展示当前门店记录）——两处消费
     * 共用同一查询，与 {@code venuefeedback.listMyFeedbacks(venueId)} 的可选过滤同构。
     * <p>
     * JOIN qwt_venues 一次取回场所名称/地址，消除 N+1。不过滤 v.deleted：场所软删除后
     * 历史上报记录仍应展示原名（记录真实性不因场所下架而消失）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，见
     * {@link #findMyReportsByUserId} 注释的既定模式）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.type AS type, r.created_at AS createdat, " +
                   "       r.expires_at AS expiresat, " +
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
     * 某门店最近突发事件列表（公开读，供详情页「报告突发事件」弹层默认内容）。
     * <p>
     * 范围：TTL 窗口内（expires_at > now）全部用户的报告，按时间倒序；窗口判定
     * 由 Service 层传入 now（TTL 唯一事实源 = expires_at 列，SQL 不自行定义时间窗）。
     * 取报告者脱敏昵称需要 JOIN qwt_users（LEFT JOIN：用户被删等异常态回退匿名，
     * 不因关联缺失丢行）。仅活跃信号（deleted=false）——已采纳/已移除的处置记录
     * 不进"最近报告"明细（公告区聚合单独消费，见 {@link #findAnnouncementsByVenue}）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，见
     * {@link #findMyReportsByUserId} 注释的既定模式）。LIMIT 由 Service 层
     * {@code .limit()} 施加（列表页仅需最近 N 条，避免大结果集全量传输）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.user_id AS userid, " +
                   "       r.type AS type, r.created_at AS createdat, " +
                   "       u.nickname AS nickname " +
                   "FROM qwt_venue_status_reports r " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.venue_id = :venueId AND r.deleted = false AND r.expires_at > :now " +
                   "ORDER BY r.created_at DESC", nativeQuery = true)
    List<VenueReportRow> findRecentByVenue(@Param("venueId") Long venueId,
                                           @Param("now") LocalDateTime now);

    /** 投影接口：门店最近突发事件行（含报告者昵称，供 GET /venues/{id}/status-reports 使用） */
    interface VenueReportRow {
        Long getId();
        Long getVenueid();
        Long getUserid();
        String getType();
        LocalDateTime getCreatedat();
        String getNickname();
    }

    /**
     * 管理端活跃突发事件列表（需 ADMIN，跨场所全量）。
     * <p>
     * 范围：TTL 窗口内（expires_at > now）全部未处置（deleted=false）报告，按时间
     * 倒序分页——管理端「上报管理 → 突发事件」tab 数据源。窗口判定由 Service 层传入
     * now（TTL 唯一事实源 = expires_at 列，SQL 不自行定义时间窗）。
     * <p>
     * 与公开列表 {@link #findRecentByVenue} 的差异：① 管理端上下文**不做昵称脱敏**
     * （返回真实昵称 + userId，管理员需识别上报者）；② 返回 {@code note}（补充说明，
     * 审核安全约定"note 仅管理端可见"，见 AGENTS.md，公开响应禁止携带）；
     * ③ JOIN qwt_venues 取场所名（管理端逐条核对场所）；④ 返回 type（类型筛选 +
     * 聚簇显示）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，见
     * {@link #findMyReportsByUserId} 注释的既定模式）；countQuery 与主查询同谓词，
     * 供 Spring Data 分页取总数（一次往返内完成 count + content）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.user_id AS userid, " +
                   "       r.type AS type, r.note AS note, r.occurred_at AS occurredat, r.created_at AS createdat, " +
                   "       v.name AS venuename, u.nickname AS nickname " +
                   "FROM qwt_venue_status_reports r " +
                   "JOIN qwt_venues v ON v.id = r.venue_id " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.deleted = false AND r.expires_at > :now " +
                   "  AND (:type IS NULL OR r.type = :type) " +
                   "ORDER BY r.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM qwt_venue_status_reports r " +
                        "WHERE r.deleted = false AND r.expires_at > :now " +
                        "  AND (:type IS NULL OR r.type = :type)",
           nativeQuery = true)
    Page<AdminReportRow> findActiveReports(@Param("now") LocalDateTime now,
                                           @Param("type") String type,
                                           Pageable pageable);

    /**
     * 管理端活跃突发事件计数（需 ADMIN，跨场所全量）。
     * 活跃 = 未删除且 expiresAt > now（TTL 唯一事实源 = 列）——FAB「上报管理」
     * 红点聚合数据源之一（与 venuefeedback PENDING 计数合并为管理端上报待办总数）。
     */
    @Query("SELECT COUNT(r) FROM VenueStatusReport r " +
           "WHERE r.deleted = false AND r.expiresAt > :now")
    long countActiveReports(@Param("now") LocalDateTime now);

    /** 投影接口：管理端活跃突发事件行（含上报者身份 + 场所名 + note，供 /admin/status-reports 使用） */
    interface AdminReportRow {
        Long getId();
        Long getVenueid();
        Long getUserid();
        String getType();
        String getNote();
        LocalDateTime getOccurredat();
        LocalDateTime getCreatedat();
        String getVenuename();
        String getNickname();
    }

    /**
     * 管理端处置：软删 + 记录处置标记（需 ADMIN，幂等）。
     * <p>
     * 处置 = 平台对突发事件信号的管理动作，分两类（2026-08-11 泛化，AdminAction 区分）：
     * 采纳（ADOPTED）= 信号属实 → 公告区保留展示至 TTL 过期并带"已核实"标记；
     * 移除（REMOVED）= 清理虚假/失效信号 → 公开视图即时消失（无需等 TTL 过期）。
     * 条件带 {@code deleted = false}：已处置/不存在影响行数为 0，幂等静默成功。
     * 调用方（Service）需在处置后失效 venueHeat 缓存（活跃计数是热度输出之一）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE VenueStatusReport r SET r.deleted = true, r.adminAction = :action " +
           "WHERE r.id = :id AND r.deleted = false")
    int disposeById(@Param("id") Long id, @Param("action") AdminAction action);

    /**
     * 续期 + 换类型：upsert 覆盖路径直写 created_at / expires_at / type / note / occurred_at。
     * <p>
     * <b>根因（2026-08-10 修复，2026-08-11 扩展）</b>：{@code BaseEntity.createdAt} 标注
     * {@code @CreationTimestamp}，Hibernate 将其视为<b>不可变属性</b>——实体 setter
     * 在 UPDATE 时被静默忽略（WARN HHH000502）。原实现意图"刷新 createdAt 续期 4h TTL"
     * 从未生效。2026-08-11 TTL 迁移到 expires_at 列后，续期 = 同时刷新 createdAt（报告
     * 行为时间）与 expiresAt（过期时刻 = now + 类型 TTL，Java 侧算好传入）；type 由
     * 实体 setter 更新（非不可变属性），本 JPQL 只负责两个时间列。
     * <p>
     * 幂等：只更新目标记录（id 定位），无并发冲突风险（与 upsert 的单行语义一致）。
     * 调用方必须在 upsert 实体保存之后调用（保证行存在）。
     * <p>
     * <b>flushAutomatically = true 是正确性前提</b>：调用方在 bulk 更新前已对实体
     * setDeleted/setType 等做脏修改（save 挂起），若不先 flush，clearAutomatically
     * 会把未落库的实体修改一并清掉；flush 先行保证实体更新先落库、再直写时间列。
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE VenueStatusReport r SET r.createdAt = :createdAt, r.expiresAt = :expiresAt " +
           "WHERE r.id = :id")
    int renewReport(@Param("id") Long id,
                    @Param("createdAt") LocalDateTime createdAt,
                    @Param("expiresAt") LocalDateTime expiresAt);

    /**
     * 同类型聚簇计数（管理端「N人报」显示，2026-08-11 新增）。
     * <p>
     * 管理端队列按 (venue_id, type) 聚簇显示：同店同类型多条活跃信号 = 众报置信度，
     * 管理员处置一条时看到"已有多人报同一事件"。仅统计活跃（未删除 + expires_at >
     * now）信号，与主列表同一活跃窗口（now 由 Service 层统一传入）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，既定模式）。
     */
    @Query(value = "SELECT r.venue_id AS venueid, r.type AS type, COUNT(*) AS cnt " +
                   "FROM qwt_venue_status_reports r " +
                   "WHERE r.deleted = false AND r.expires_at > :now " +
                   "GROUP BY r.venue_id, r.type", nativeQuery = true)
    List<TypeClusterRow> countClustersByVenueAndType(@Param("now") LocalDateTime now);

    /** 投影接口：同类型聚簇计数行（管理端「N人报」显示） */
    interface TypeClusterRow {
        Long getVenueid();
        String getType();
        Long getCnt();
    }

    /**
     * 详情页紧急公告区聚合（2026-08-11 新增，公开读）。
     * <p>
     * 公告区展示 = 活跃信号（deleted=false）+ 已采纳信号（deleted=true 且
     * admin_action='ADOPTED'，公告保留展示至 TTL 过期并带"已核实"标记）——移除的
     * 信号（REMOVED）不展示。范围均限 TTL 窗口（expires_at > now）。
     * <p>
     * 按 (venue_id, type) 聚簇返回，Service 层聚合为每类型一条摘要（count /
     * adopted / latestAt）。不返回 note（审核安全约定"note 仅管理端可见"）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，既定模式）。
     */
    @Query(value = "SELECT r.type AS type, " +
                   "       COUNT(*) AS cnt, " +
                   "       COUNT(*) FILTER (WHERE r.admin_action = 'ADOPTED') AS adoptedcnt, " +
                   "       MAX(r.created_at) AS latestat " +
                   "FROM qwt_venue_status_reports r " +
                   "WHERE r.venue_id = :venueId AND r.expires_at > :now " +
                   "  AND (r.deleted = false OR r.admin_action = 'ADOPTED') " +
                   "GROUP BY r.type", nativeQuery = true)
    List<AnnouncementRow> findAnnouncementsByVenue(@Param("venueId") Long venueId,
                                                   @Param("now") LocalDateTime now);

    /** 投影接口：公告区聚合行（每类型一条：计数 / 已采纳数 / 最新时间） */
    interface AnnouncementRow {
        String getType();
        Long getCnt();
        Long getAdoptedcnt();
        LocalDateTime getLatestat();
    }

    /** 投影接口：我的上报记录行（含场所信息，供 GET /status-reports/mine 使用） */
    interface MyReportRow {
        Long getId();
        Long getVenueid();
        String getType();
        LocalDateTime getCreatedat();
        LocalDateTime getExpiresat();
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
