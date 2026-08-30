package org.quwuting.quwutingservice.venuestatusreport.repository;

import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
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

    /**
     * 查找用户对某场所的<b>活跃报告</b>（未逻辑删除、<b>未处置（admin_action IS
     * NULL）</b>且未过期，最新一条）。
     * 2026-08-20 追加式模型（V34）：同一用户同一门店同时至多一条活跃报告（并发
     * 约束由应用层 pg_advisory_xact_lock 保证）；本查询供「补充详情」定位（有活跃 =
     * 更新该行，不产生新记录）与「撤销」定位。
     * 2026-08-20 修正：活跃判定排除已处置记录（admin_action IS NULL）——被采纳
     * （ADOPTED）的记录保留展示但不再是活跃报告，用户侧重置「待报告」可再次上报
     * （新记录），补充/撤销也不得作用于已采纳记录。
     */
    Optional<VenueStatusReport> findFirstByUserIdAndVenueIdAndDeletedFalseAndAdminActionIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, Long venueId, LocalDateTime now);

    /**
     * 新上报的<b>确定性写入</b>（2026-08-20 追加式模型 V34）。
     * <p>
     * 2026-08-20 演进：初版基于全量 UNIQUE(user_id, venue_id) 的 upsert 语义（ON
     * CONFLICT 推断），后计划升级为「活跃记录」维度的部分唯一索引
     * （WHERE deleted = false AND expires_at > now()）——<b>被 PG 拒绝</b>：部分索引
     * 谓词必须 IMMUTABLE，now() 是 STABLE（"functions in index predicate must be
     * marked IMMUTABLE"）。最终方案：并发首报由 <b>应用层 pg_advisory_xact_lock
     * (user_id, venue_id) 串行化</b>保证（2026-08-19 定则方案②，见
     * {@code StatusReportService.submitReport}）——本方法为<b>普通 INSERT</b>
     * （无唯一约束可冲突，无 ON CONFLICT 子句）。
     * <p>
     * <b>enum 参数必须传 name() 字符串（2026-08-20 根因修复）</b>：原生 SQL 绑定
     * enum 无 JPA 元数据 → 默认 {@code EnumType.ORDINAL} 落库序号而非类型名，实体
     * 派生查询按 {@code @Enumerated(STRING)} name() 匹配 → 两侧不一致（同
     * {@code VenueFeedbackRepository.upsertPending*}，详见 15-governance 错误表）。
     * 调用方传 {@code type.name()}。
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_status_reports " +
                   "(venue_id, user_id, type, occurred_at, note, expires_at, admin_action, created_at, updated_at, deleted) " +
                   "VALUES (:venueId, :userId, :type, :occurredAt, :note, :expiresAt, NULL, :now, :now, false)",
           nativeQuery = true)
    int insertReport(@Param("venueId") Long venueId,
                     @Param("userId") Long userId,
                     @Param("type") String type,
                     @Param("occurredAt") LocalDateTime occurredAt,
                     @Param("note") String note,
                     @Param("expiresAt") LocalDateTime expiresAt,
                     @Param("now") LocalDateTime now);

    /**
     * 事务级咨询锁：按 (userId, venueId) 维度串行化「新上报」check-then-act
     * （2026-08-20 追加式模型引入，2026-08-19 定则方案②）。
     * <p>
     * 背景：V34 去掉全量 UNIQUE(user_id, venue_id) 后无 DB 唯一约束兜底并发首报；
     * 「活跃记录」维度的部分唯一索引被 PG 拒绝（谓词含 now() 非 IMMUTABLE）。
     * 本锁保证同一用户对同一门店的并发上报串行执行：锁内重查「我的活跃报告」，
     * 已存在 → 走补充更新（不产生新记录）；不存在 → INSERT 新行。锁随事务提交/
     * 回滚自动释放，无残留。
     * <p>
     * 2026-08-30 MySQL 迁移：pg_advisory_xact_lock → SELECT ... FOR UPDATE 行锁
     * （锁「同用户同门店的活跃报告行」：命中锁行、未命中锁间隙——InnoDB 间隙锁
     * 阻止并发首报双插；无唯一约束可兜底（V34 追加式模型），锁为唯一防线）。
     * 返回 List 仅为让 Spring Data 走 getResultList 执行（void 返回会走
     * executeUpdate，对 SELECT 依赖驱动行为，不够稳）。
     */
    @Query(value = "SELECT id FROM qwt_venue_status_reports " +
                   "WHERE user_id = :userId AND venue_id = :venueId " +
                   "  AND deleted = false AND admin_action IS NULL " +
                   "ORDER BY created_at DESC, id DESC LIMIT 1 FOR UPDATE", nativeQuery = true)
    List<Long> lockUserVenue(@Param("userId") Long userId, @Param("venueId") Long venueId);

    /**
     * 活跃报告聚合：合并 COUNT + MAX(createdAt) 为 1 次往返。
     * 活跃 = 未删除、<b>未处置（admin_action IS NULL）</b>且 expiresAt > now
     * （TTL 唯一事实源 = expires_at 列，2026-08-11 由 created_at >= since 迁移，
     * now 由 Service 层传入；2026-08-20 排除已处置——被采纳记录不再是活跃信号）。
     */
    @Query("SELECT COUNT(r) as activeCount, MAX(r.createdAt) as latestTime " +
           "FROM VenueStatusReport r " +
           "WHERE r.venueId = :venueId AND r.deleted = false AND r.adminAction IS NULL " +
           "  AND r.expiresAt > :now")
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
     * 某门店突发事件历史明细列表（公开读，公告页「最近的突发事件」数据源）。
     * <p>
     * 范围：未撤销（deleted=false）的全部用户报告，按时间倒序——<b>无时间窗口</b>，
     * 含已过期（TTL 外）与超出任何展示窗口的历史记录。TTL 过期只代表信号失效
     * （不计入活跃计数/当前公告区），不代表报告事实消失——过期标注由 Service 层按
     * {@code expires_at} 列判定（TTL 唯一事实源 = 列），本查询投影该列供其消费。
     * <p>
     * 根因（2026-08-20 修复，承接 2026-08-12）：旧实现曾先后用「活跃判定
     * {@code expires_at > :now}」与「展示窗口 {@code created_at >= now - recentHistoryHours}」
     * 裁剪本列表——前者让 TTL 过期即消失，后者让超出窗口的旧记录不可见，均与
     * "公告页 = 报告事实历史视图"的语义冲突（用户回看社区历史时只见空列表，无法
     * 区分「从未有人报」与「报过但已过期」）。2026-08-12 修复了前者但保留了窗口
     * （半成品）；2026-08-20 移除窗口：<b>历史视图只裁剪「非事实」</b>（撤销/处置），
     * 时间维度由 Service 层逐行标注 expired，行数上限由 Service 层
     * {@code .limit()} 施加（防无限增长，见 {@code StatusReportService#RECENT_REPORT_LIST_LIMIT}）。
     * 详见 AGENTS.md「门店突发事件列表」。
     * <p>
     * 已撤销（deleted=true）与已移除（REMOVED，deleted=true）记录不进历史明细；
     * <b>已采纳（ADOPTED）记录保留展示</b>（2026-08-20 修正：采纳 = 处置标记而非
     * 删除行，记录作为"已核实"事实继续可见，Service 层逐行标注 adopted）。
     * 取报告者脱敏昵称需要 JOIN qwt_users（LEFT JOIN：用户被删等异常态回退匿名，
     * 不因关联缺失丢行）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，见
     * {@link #findMyReportsByUserId} 注释的既定模式）。LIMIT 由 Service 层
     * {@code .limit()} 施加（避免大结果集全量传输）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.user_id AS userid, " +
                   "       r.type AS type, r.created_at AS createdat, r.expires_at AS expiresat, " +
                   "       r.admin_action AS adminaction, u.nickname AS nickname " +
                   "FROM qwt_venue_status_reports r " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.venue_id = :venueId AND r.deleted = false " +
                   "ORDER BY r.created_at DESC", nativeQuery = true)
    List<VenueReportRow> findRecentByVenue(@Param("venueId") Long venueId);

    /** 投影接口：门店最近突发事件行（含报告者昵称、过期时刻与处置标记，供 GET /venues/{id}/status-reports 使用） */
    interface VenueReportRow {
        Long getId();
        Long getVenueid();
        Long getUserid();
        String getType();
        LocalDateTime getCreatedat();
        LocalDateTime getExpiresat();
        String getAdminaction();
        String getNickname();
    }

    /**
     * 管理端活跃突发事件列表（需 ADMIN，跨场所全量）。
     * <p>
     * 范围：TTL 窗口内（expires_at > now）全部<b>未处置</b>（deleted=false 且
     * admin_action IS NULL）报告，按时间倒序分页——管理端「上报管理 → 突发事件」
     * tab 数据源（处置后记录移出待办队列，2026-08-20 修正：ADOPTED 仅打标记不软删，
     * 但不再算"待处置活跃"）。窗口判定由 Service 层传入 now（TTL 唯一事实源 =
     * expires_at 列，SQL 不自行定义时间窗）。
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
                   "       v.name AS venuename, u.nickname AS nickname, r.admin_action AS adminaction " +
                   "FROM qwt_venue_status_reports r " +
                   "JOIN qwt_venues v ON v.id = r.venue_id " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.deleted = false AND r.admin_action IS NULL AND r.expires_at > :now " +
                   "  AND (:type IS NULL OR r.type = :type) " +
                   "ORDER BY r.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM qwt_venue_status_reports r " +
                        "WHERE r.deleted = false AND r.admin_action IS NULL AND r.expires_at > :now " +
                        "  AND (:type IS NULL OR r.type = :type)",
           nativeQuery = true)
    Page<AdminReportRow> findActiveReports(@Param("now") LocalDateTime now,
                                           @Param("type") String type,
                                           Pageable pageable);

    /**
     * 管理端<b>已处理</b>突发事件列表（2026-08-28 新增，仅 ADMIN）。
     * <p>
     * 已处理 = 管理端已处置（admin_action IS NOT NULL）——已采纳 ADOPTED（未软删）、
     * 已保留 KEPT（未软删）、已移除 REMOVED（soft delete）三态统一收口，按时间倒序
     * 分页，跨场所。语义：管理员可复盘历史处置（审计 + 识别恶意上报用户模式），
     * 「移除后记录直接消失」的旧问题由此解决——公开视图消失（恶意信号不得误导），
     * 但管理端历史保留（处置留痕）。
     * <p>
     * <b>不按 TTL 过滤</b>：已处置信号无论是否过公示期都是历史事实（处置结果不因
     * 时间流逝而失效）；软删的 REMOVED 记录在本视图内可见（与待处理视图的
     * deleted=false 谓词差异是设计使然）。
     * <p>
     * 与 {@link #findActiveReports} 同投影/同 JOIN（无 type 筛选——已处理视图按
     * 处置状态而非类型浏览，类型信息由行内 typeDisplay 展示；前端另有类型筛选
     * 维度仅作用于待处理视图）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，既定模式）。
     */
    @Query(value = "SELECT r.id AS id, r.venue_id AS venueid, r.user_id AS userid, " +
                   "       r.type AS type, r.note AS note, r.occurred_at AS occurredat, r.created_at AS createdat, " +
                   "       v.name AS venuename, u.nickname AS nickname, r.admin_action AS adminaction " +
                   "FROM qwt_venue_status_reports r " +
                   "JOIN qwt_venues v ON v.id = r.venue_id " +
                   "LEFT JOIN qwt_users u ON u.id = r.user_id " +
                   "WHERE r.admin_action IS NOT NULL " +
                   "ORDER BY r.created_at DESC",
           countQuery = "SELECT COUNT(*) FROM qwt_venue_status_reports r " +
                        "WHERE r.admin_action IS NOT NULL",
           nativeQuery = true)
    Page<AdminReportRow> findHandledReports(Pageable pageable);

    /**
     * 管理端活跃突发事件计数（需 ADMIN，跨场所全量）。
     * 活跃 = 未删除、<b>未处置（admin_action IS NULL）</b>且 expiresAt > now（TTL
     * 唯一事实源 = 列）——FAB「上报管理」红点聚合数据源之一（与 venuefeedback
     * PENDING 计数合并为管理端上报待办总数）。
     */
    @Query("SELECT COUNT(r) FROM VenueStatusReport r " +
           "WHERE r.deleted = false AND r.adminAction IS NULL AND r.expiresAt > :now")
    long countActiveReports(@Param("now") LocalDateTime now);

    /** 投影接口：管理端突发事件行（含上报者身份 + 场所名 + note + 处置标记，供 /admin/status-reports 使用） */
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
        /** 管理端处置标记（null = 待处理；ADOPTED/KEPT/REMOVED = 已处置，2026-08-28 新增） */
        String getAdminaction();
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
     * 续期 + 换类型：补充/更新路径直写 created_at / expires_at / type / note / occurred_at。
     * <p>
     * <b>根因（2026-08-10 修复，2026-08-11 扩展）</b>：{@code BaseEntity.createdAt} 标注
     * {@code @CreationTimestamp}，Hibernate 将其视为<b>不可变属性</b>——实体 setter
     * 在 UPDATE 时被静默忽略（WARN HHH000502）。原实现意图"刷新 createdAt 续期 4h TTL"
     * 从未生效。2026-08-11 TTL 迁移到 expires_at 列后，续期 = 同时刷新 createdAt（报告
     * 行为时间）与 expiresAt（过期时刻 = now + 统一公示期 2 天，Java 侧算好传入）；type 由
     * 实体 setter 更新（非不可变属性），本 JPQL 只负责两个时间列。
     * <p>
     * 幂等：只更新目标记录（id 定位），无并发冲突风险（补充路径在 Service 层已先取
     * 活跃记录、串行更新同一行）。续期后该行仍在活跃唯一索引内（deleted=false 且新
     * expiresAt > now），不触发唯一性冲突。
     * <p>
     * 调用方必须在实体 save 之后调用（保证行存在）。<b>flushAutomatically = true 是
     * 正确性前提</b>：调用方在 bulk 更新前已对实体 setType 等做脏修改（save 挂起），
     * 若不先 flush，clearAutomatically 会把未落库的实体修改一并清掉；flush 先行保证
     * 实体更新先落库、再直写时间列。
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
     * 管理员处置一条时看到"已有多人报同一事件"。仅统计活跃（未删除 + <b>未处置
     * （admin_action IS NULL）</b> + expires_at > now）信号，与主列表同一活跃窗口
     * （now 由 Service 层统一传入）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，既定模式）。
     */
    @Query(value = "SELECT r.venue_id AS venueid, r.type AS type, COUNT(*) AS cnt " +
                   "FROM qwt_venue_status_reports r " +
                   "WHERE r.deleted = false AND r.admin_action IS NULL AND r.expires_at > :now " +
                   "GROUP BY r.venue_id, r.type", nativeQuery = true)
    List<TypeClusterRow> countClustersByVenueAndType(@Param("now") LocalDateTime now);

    /** 投影接口：同类型聚簇计数行（管理端「N人报」显示） */
    interface TypeClusterRow {
        Long getVenueid();
        String getType();
        Long getCnt();
    }

    /**
     * 门店紧急公告区聚合（2026-08-11 新增，公开读）。
     * <p>
     * 公告区展示 = 活跃信号（deleted=false）+ 已采纳信号（deleted=true 且
     * admin_action='ADOPTED'，带"已核实"标记）按类型聚簇——移除的信号（REMOVED）
     * 不展示。时间窗口由 {@code includeExpired} 参数化（2026-08-20 新增，双消费方
     * 分窗语义，见 AGENTS.md「紧急公告区」）：
     * <ul>
     *   <li>{@code includeExpired=false}（默认）= <b>活跃视图</b>：仅 TTL 窗口内
     *       （expires_at > now）信号——详情页单行公告条消费（"当前紧急信号"语义，
     *       过时信号不得误导为当前紧急）；</li>
     *   <li>{@code includeExpired=true} = <b>历史视图</b>：全部未撤销 + 已采纳记录
     *       （含已过期）——公告专属页「紧急公告」列表消费（"历史事实摘要"语义，
     *       用户回看社区历史需可见，时效由 latestAt 相对时间传达）。</li>
     * </ul>
     * 按 (venue_id, type) 聚簇返回，Service 层聚合为每类型一条摘要（count /
     * adopted / latestAt）。不返回 note（审核安全约定"note 仅管理端可见"）。
     * <p>
     * 原生 SQL + 投影接口，别名必须全小写（PG 折叠未引用标识符，既定模式）。
     */
    @Query(value = "SELECT r.type AS type, " +
                   "       COUNT(*) AS cnt, " +
                   "       SUM(CASE WHEN r.admin_action = 'ADOPTED' THEN 1 ELSE 0 END) AS adoptedcnt, " +
                   "       MAX(r.created_at) AS latestat " +
                   "FROM qwt_venue_status_reports r " +
                   "WHERE r.venue_id = :venueId " +
                   "  AND (:includeExpired = true OR r.expires_at > :now) " +
                   "  AND (r.deleted = false OR r.admin_action = 'ADOPTED') " +
                   "GROUP BY r.type", nativeQuery = true)
    List<AnnouncementRow> findAnnouncementsByVenue(@Param("venueId") Long venueId,
                                                   @Param("now") LocalDateTime now,
                                                   @Param("includeExpired") boolean includeExpired);

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

    /**
     * 批量统计：指定用户集的暂停营业报告总数（2026-08-27 用户管理增强——详情页
     * 上报概览合并口径之一；未软删且 user_id 非空）。返回 Object[]{userId, count}。
     */
    @Query("SELECT r.userId, COUNT(r) FROM VenueStatusReport r " +
            "WHERE r.userId IN :userIds AND r.deleted = false GROUP BY r.userId")
    List<Object[]> countGroupByUserIds(@Param("userIds") java.util.Collection<Long> userIds);

    /**
     * 批量统计：指定用户集的待处理报告数（2026-08-27 用户管理增强——详情页上报
     * 概览「待处理」：admin_action IS NULL = 管理员未处置；已处置（采纳/忽略）与
     * 未处置语义见 AdminAction 枚举）。返回 Object[]{userId, count}。
     */
    @Query("SELECT r.userId, COUNT(r) FROM VenueStatusReport r " +
            "WHERE r.userId IN :userIds AND r.deleted = false AND r.adminAction IS NULL GROUP BY r.userId")
    List<Object[]> countPendingGroupByUserIds(@Param("userIds") java.util.Collection<Long> userIds);

    /**
     * 单用户暂停营业报告明细（2026-08-28 管理端用户详情下钻，docs/agents/23）：未软删
     * 行，时间倒序——「上报 N 条」统计点击查看每条明细中状态报告部分的数据源。
     */
    @Query("SELECT r FROM VenueStatusReport r WHERE r.userId = :userId AND r.deleted = false " +
            "ORDER BY r.createdAt DESC, r.id DESC")
    List<VenueStatusReport> findByUserIdForAdminDetail(@Param("userId") Long userId);
}
