package org.quwuting.quwutingservice.venuefeedback.repository;

import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackField;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface VenueFeedbackRepository extends JpaRepository<VenueFeedback, Long>,
        JpaSpecificationExecutor<VenueFeedback> {

    /**
     * 按状态计数（2026-08-10 首页 FAB「上报管理」红点数据源）。
     * 轻量 COUNT：管理端未读徽标只关心待处理量，不拉列表——与 message 模块
     * unread-count 同模式；status 谓词由 V2 部分唯一索引（WHERE status='PENDING'）
     * 覆盖，避免全表扫描。
     */
    long countByStatus(ReportStatus status);

    /**
     * 按场所查询某状态下报（管理端按场所维度使用）。
     * 平台级列表走 {@link #findAll(org.springframework.data.jpa.domain.Specification, org.springframework.data.domain.Pageable)}
     * 组合筛选（状态/类型可选），不在此派生。
     */
    List<VenueFeedback> findByVenueIdAndStatusOrderByCreatedAtDesc(Long venueId, ReportStatus status);

    /** 按场所查询全部上报（管理端按场所维度使用） */
    List<VenueFeedback> findByVenueIdOrderByCreatedAtDesc(Long venueId);

    /** 当前用户的全部上报（「我的上报记录」个人中心数据源，倒序） */
    List<VenueFeedback> findByUserIdOrderByCreatedAtDesc(Long userId);

    /**
     * 单用户上报明细（2026-08-28 管理端用户详情下钻，docs/agents/23）：未软删行，
     * 时间倒序——「上报 N 条」统计点击查看每条明细的数据源。status 可空 = 全部。
     */
    @Query("SELECT f FROM VenueFeedback f WHERE f.userId = :userId AND f.deleted = false " +
            "AND (:status IS NULL OR f.status = :status) ORDER BY f.createdAt DESC, f.id DESC")
    List<VenueFeedback> findByUserIdForAdminDetail(@Param("userId") Long userId,
                                                   @Param("status") ReportStatus status);

    /** 当前用户对某场所的上报（「我的上报记录」详情页弹窗数据源，倒序） */
    List<VenueFeedback> findByUserIdAndVenueIdOrderByCreatedAtDesc(Long userId, Long venueId);

    /**
     * 查找用户对某场所某类型的指定状态记录（2026-08-07 防刷幂等兜底用）：
     * createFeedback 撞 PENDING 部分唯一索引（V2 迁移）后，回查已有待处理记录
     * 幂等返回。命中走 (user_id, venue_id, type, status) 过滤，由既有索引覆盖。
     * 仅用于非纠错场景（field = null 的行，去重单位 = type，见 V8 迁移）。
     */
    Optional<VenueFeedback> findByUserIdAndVenueIdAndTypeAndStatus(
            Long userId, Long venueId, FeedbackType type, ReportStatus status);

    /**
     * 按纠错字段回查指定状态记录（2026-08-10，V8 拆分唯一索引后新增）：
     * 纠错场景（field IS NOT NULL）的去重单位升级为 (user_id, venue_id, type,
     * field)（见 V8 迁移）——撞唯一索引后按同一字段回查已有 PENDING 记录幂等返回，
     * 避免把"同场所同类型但不同字段"的纠错误当成重复上报。
     */
    Optional<VenueFeedback> findByUserIdAndVenueIdAndTypeAndFieldAndStatus(
            Long userId, Long venueId, FeedbackType type, FeedbackField field, ReportStatus status);

    /**
     * 登录用户 PENDING 反馈的<b>确定性原子写入</b>（纠错场景，field IS NOT NULL，
     * 2026-08-20 根因修复：替代「save + catch 23505 + 同事务回查」的不可靠并发幂等）。
     * <p>
     * 根因（与 {@link #upsertPendingWithoutField} 同源，详见 15-governance 错误表）：
     * PostgreSQL 中语句失败（SQLState 23505）后整个事务进入 aborted 状态（25P02），
     * Hibernate {@code save()} 立即执行 INSERT（IDENTITY 生成策略），catch 内
     * {@code entityManager.clear()} 只清理 session、无法恢复已中止的 DB 事务——catch
     * 后同一事务内的回查必然抛「current transaction is aborted」→ JpaSystemException
     * → HTTP 500（2026-08-20 线上实证：报告恢复营业连点报 500）。
     * <p>
     * 本写法恒 1 次 DB 往返、零异常：命中 V8 部分唯一索引
     * {@code qwt_uk_feedbacks_user_venue_type_field_pending}（同一用户对同一场所
     * 同一类型同一字段的 PENDING 记录）时 DO NOTHING，调用方随后按去重单位回查
     * 幂等返回胜出行（新插入或已存在，PENDING 唯一索引保证至多一行）。
     * <p>
     * 冲突目标：列清单 + 完整索引谓词（部分唯一索引推断要求推断谓词与索引谓词
     * 逻辑一致，禁止省略——否则计划期报「no unique or exclusion constraint matching」）。
     * <p>
     * <b>enum 参数必须传 name() 字符串（2026-08-20 根因修复）</b>：Hibernate 对原生
     * SQL（native query）参数无 JPA 映射元数据，enum 参数默认按
     * {@code EnumType.ORDINAL} 绑定（见 {@code EnumJavaType.sqlType}：enumeratedType
     * 为 null 时回退 ORDINAL）——直接传 {@code FeedbackType}/{@code FeedbackField}
     * 枚举会把 ordinal 数字落库（type 列存 "2" 而非 "RESUMED"），而实体派生查询
     * 按 {@code @Enumerated(STRING)} 的 name() 匹配，回查必然 0 条 → IllegalStateException
     * → HTTP 500（2026-08-20 线上实证「确认已恢复营业 500」）。调用方传
     * {@code enum.name()}（如 {@code FeedbackType.RESUMED.name()}）即绑定正确字符串。
     *
     * @return 受影响行数（1 = 新插入；0 = 已存在 PENDING 记录，幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_feedbacks " +
                   "(venue_id, user_id, type, note, field, corrected_value, status, handled, deleted, created_at, updated_at) " +
                   "VALUES (:venueId, :userId, :type, :note, :field, :correctedValue, 'PENDING', false, false, :now, :now) " +
                   "ON CONFLICT (user_id, venue_id, type, field) " +
                   "WHERE user_id IS NOT NULL AND status = 'PENDING' AND field IS NOT NULL DO NOTHING",
           nativeQuery = true)
    int upsertPendingWithField(@Param("venueId") Long venueId,
                               @Param("userId") Long userId,
                               @Param("type") String type,
                               @Param("field") String field,
                               @Param("note") String note,
                               @Param("correctedValue") String correctedValue,
                               @Param("now") LocalDateTime now);

    /**
     * 登录用户 PENDING 反馈的<b>确定性原子写入</b>（非纠错场景，field IS NULL，
     * 2026-08-20 根因修复，语义与 {@link #upsertPendingWithField} 完全对称）。
     * <p>
     * 命中 V2 部分唯一索引 {@code qwt_uk_feedbacks_user_venue_type_pending}
     * （同一用户对同一场所同一类型的 PENDING 记录，V8 拆分后仅覆盖 field IS NULL
     * 行）时 DO NOTHING，调用方随后按去重单位回查幂等返回胜出行。
     * <p>
     * 非纠错场景 field/corrected_value 恒 NULL（后端对非 INACCURATE 类型不落库，
     * 见 VenueFeedbackService 约定），故 SQL 内直接写 NULL，无需传参。
     * <p>
     * <b>enum 参数必须传 name() 字符串（2026-08-20 根因修复）</b>：与
     * {@link #upsertPendingWithField} 同源——原生 SQL 绑定 enum 默认 ORDINAL，
     * 传 {@code FeedbackType} 枚举会把序号落库、回查（name() 匹配）必然 0 条报 500。
     * 调用方传 {@code request.type().name()}。
     *
     * @return 受影响行数（1 = 新插入；0 = 已存在 PENDING 记录，幂等跳过）
     */
    @Modifying
    @Query(value = "INSERT INTO qwt_venue_feedbacks " +
                   "(venue_id, user_id, type, note, field, corrected_value, status, handled, deleted, created_at, updated_at) " +
                   "VALUES (:venueId, :userId, :type, :note, NULL, NULL, 'PENDING', false, false, :now, :now) " +
                   "ON CONFLICT (user_id, venue_id, type) " +
                   "WHERE user_id IS NOT NULL AND status = 'PENDING' AND field IS NULL DO NOTHING",
           nativeQuery = true)
    int upsertPendingWithoutField(@Param("venueId") Long venueId,
                                  @Param("userId") Long userId,
                                  @Param("type") String type,
                                  @Param("note") String note,
                                  @Param("now") LocalDateTime now);

    /**
     * 批量统计：指定用户集的信息上报总数（2026-08-27 用户管理增强——详情页
     * 上报概览；未软删且 user_id 非空——匿名上报无法归属，不计入）。
     * 返回 Object[]{userId, count}。
     */
    @Query("SELECT f.userId, COUNT(f) FROM VenueFeedback f " +
            "WHERE f.userId IN :userIds AND f.deleted = false GROUP BY f.userId")
    List<Object[]> countGroupByUserIds(@Param("userIds") java.util.Collection<Long> userIds);

    /**
     * 批量统计：指定用户集某状态的信息上报数（2026-08-27 用户管理增强——详情页
     * 上报概览「待处理」= ReportStatus.PENDING）。返回 Object[]{userId, count}。
     */
    @Query("SELECT f.userId, COUNT(f) FROM VenueFeedback f " +
            "WHERE f.userId IN :userIds AND f.deleted = false AND f.status = :status GROUP BY f.userId")
    List<Object[]> countGroupByUserIdsAndStatus(@Param("userIds") java.util.Collection<Long> userIds,
                                                @Param("status") ReportStatus status);
}
