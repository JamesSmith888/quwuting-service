package org.quwuting.quwutingservice.dancer.repository;

import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 联系方式需求记录仓库（2026-08-24 风控留痕，qwt_demand_records）。
 * 只写不删（锚点记录，与 PointsUnlock 同模式）；查询维度 = 用户（异常解锁频次）/
 * 舞伴（骚扰投诉排查）——本期落库留痕，风控查询面在管理端按需扩展。
 * <p>
 * 2026-08-26 新增用户视角查询：个人中心「我的邀约」列表（findByUserIdOrderByIdDesc，
 * 走已建 idx_qwt_demand_records_user 索引）+ 详情（findByUserIdAndId，归属校验——
 * 邀约是用户级资源，越权查询后端直接 1001）。
 * <p>
 * 2026-08-26 邀约中转（V50，docs/agents/22）：status 状态机——
 * 管理端待办（findPendingByDancerIdIn / findPendingPage）+ 客人侧 PENDING 去重
 * （findPendingByUserIdAndDancerId，防重复骚扰舞伴）+ 定时降级
 * （findPendingExpiredBy，24h 无回复）+ 发放/拒绝条件更新（updateStatusIfPending，
 * WHERE status='PENDING' 天然幂等）。
 */
public interface DemandRecordRepository extends JpaRepository<DemandRecord, Long> {

    /** 我的邀约（分页倒序，新记录在前；个人中心「我的邀约」数据源） */
    @Query("SELECT d FROM DemandRecord d WHERE d.userId = :userId ORDER BY d.id DESC")
    Page<DemandRecord> findByUserIdOrderByIdDesc(@Param("userId") Long userId, Pageable pageable);

    /**
     * 单用户需求单明细（2026-08-28 管理端用户详情下钻，docs/agents/23）：id 倒序——
     * 「需求单 N 条」/按状态分布统计点击查看每条明细的数据源。status 可空 = 全部；
     * 存量 NULL 状态等价 APPROVED（COALESCE，与 22 号文档状态机一致），
     * status=APPROVED 时 NULL 存量行一并命中。
     */
    @Query("SELECT d FROM DemandRecord d WHERE d.userId = :userId " +
            "AND (:status IS NULL OR COALESCE(d.status, 'APPROVED') = :status) " +
            "ORDER BY d.id DESC")
    List<DemandRecord> findByUserIdForAdminDetail(@Param("userId") Long userId,
                                                  @Param("status") String status);

    /** 我的单条邀约（详情；userId + id 双重条件 = 归属校验，越权查不到） */
    Optional<DemandRecord> findByUserIdAndId(Long userId, Long id);

    /** 客人最近一条 PENDING 邀约（同 user×dancer，防重复骚扰舞伴；无则空） */
    @Query("SELECT d FROM DemandRecord d WHERE d.userId = :userId AND d.dancerId = :dancerId " +
            "AND d.status = 'PENDING' ORDER BY d.id DESC")
    Optional<DemandRecord> findPendingByUserIdAndDancerId(@Param("userId") Long userId,
                                                          @Param("dancerId") Long dancerId);

    /** 客人最近一条已获批邀约（APPROVED/AUTO_RELEASED；2026-08-26 中转舞伴的
     *  「已发放」幂等依据——<b>基于邀约状态而非 PointsUnlock</b>：开启 contact_relay
     *  前解锁的历史记录（PointsUnlock 存在但无获批邀约）不算获批，重走流程 */
    @Query("SELECT d FROM DemandRecord d WHERE d.userId = :userId AND d.dancerId = :dancerId " +
            "AND d.status IN ('APPROVED', 'AUTO_RELEASED') ORDER BY d.id DESC")
    Optional<DemandRecord> findApprovedByUserIdAndDancerId(@Param("userId") Long userId,
                                                           @Param("dancerId") Long dancerId);

    /** 管理端待办（指定舞伴集合内 PENDING 分页倒序，新邀约在前；走 pending 索引） */
    @Query("SELECT d FROM DemandRecord d WHERE d.dancerId IN :dancerIds AND d.status = 'PENDING' " +
            "ORDER BY d.id DESC")
    Page<DemandRecord> findPendingByDancerIds(@Param("dancerIds") Iterable<Long> dancerIds, Pageable pageable);

    /** 管理端待办计数（2026-08-26：me 页「邀约工作台」入口红点数据源，轻量 COUNT，
     *  与 GET /admin/reports/pending-count 同模式——红点只提示"有待办"，数量随
     *  管理端发放/拒绝动作自然归零；无独立已读态） */
    @Query("SELECT COUNT(d) FROM DemandRecord d WHERE d.dancerId IN :dancerIds AND d.status = 'PENDING'")
    long countPendingByDancerIds(@Param("dancerIds") Iterable<Long> dancerIds);

    /** 管理端邀约列表（按状态集合过滤；processed 终态视图——APPROVED/REJECTED/
     *  AUTO_RELEASED/EXPIRED；dancerIds = 中转舞伴集合，分页倒序）。与 findPendingByDancerIds
     *  同口径，仅把固定 PENDING 改为入参状态集合——scope 是列表查询的正交维度，
     *  与状态机解耦（2026-08-26 工作台历史视图：三视图共用一套映射）。 */
    @Query("SELECT d FROM DemandRecord d WHERE d.dancerId IN :dancerIds AND d.status IN :statuses ORDER BY d.id DESC")
    Page<DemandRecord> findByDancerIdsAndStatuses(@Param("dancerIds") Iterable<Long> dancerIds,
                                                  @Param("statuses") List<String> statuses, Pageable pageable);

    /** 管理端邀约列表（全部中转记录，不限状态；dancerIds = 中转舞伴集合，分页倒序）。
     *  "全部"视图用——含 legacy NULL 状态记录（存量中转舞伴历史），与状态机解耦。 */
    @Query("SELECT d FROM DemandRecord d WHERE d.dancerId IN :dancerIds ORDER BY d.id DESC")
    Page<DemandRecord> findByDancerIds(@Param("dancerIds") Iterable<Long> dancerIds, Pageable pageable);

    /** 定时降级扫描：超时仍 PENDING 的邀约（24h 无回复） */
    @Query("SELECT d FROM DemandRecord d WHERE d.status = 'PENDING' AND d.createdAt <= :deadline")
    java.util.List<DemandRecord> findPendingOlderThan(@Param("deadline") LocalDateTime deadline);

    /** 发放/拒绝/降级条件更新（PENDING → 目标态；非 PENDING 不更新 = 天然幂等） */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DemandRecord d SET d.status = :to WHERE d.id = :id AND d.status = 'PENDING'")
    int updateStatusIfPending(@Param("id") Long id, @Param("to") String to);

    /**
     * 拒绝条件更新（PENDING → REJECTED + 落拒绝原因，2026-08-27，V55，
     * docs/agents/24「P0 拒绝原因闭环」）：非 PENDING 不更新 = 天然幂等（重复
     * 拒绝/并发无竞态，同 updateStatusIfPending 范式）。rejectReason 可空
     * （旧客户端/未选原因，客人侧回退通用状态文案）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DemandRecord d SET d.status = 'REJECTED', d.rejectReason = :reason " +
            "WHERE d.id = :id AND d.status = 'PENDING'")
    int updateRejectIfPending(@Param("id") Long id, @Param("reason") String reason);

    /**
     * 客人请求替代条件更新（2026-08-27，V55，docs/agents/24「换乘站」）：
     * 仅 REJECTED/EXPIRED 且未请求过时置 rescue_requested_at（WHERE 双条件 =
     * 天然幂等，重复请求/并发只成功一次）。返回 0 = 已请求过（幂等成功）或
     * 状态不可请求（调用方已前置校验状态）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DemandRecord d SET d.rescueRequestedAt = :at " +
            "WHERE d.id = :id AND d.status IN ('REJECTED', 'EXPIRED') AND d.rescueRequestedAt IS NULL")
    int updateRescueRequestedIf(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** 该原邀约是否已有替代邀约（2026-08-27：部分唯一索引
     *  idx_qwt_demand_records_rescue_origin 兜底，一次救援只产出一条替代邀约） */
    boolean existsByOriginDemandId(Long originDemandId);

    /**
     * 邀约被查看条件更新（2026-08-27，V56，docs/agents/25「分享闭环自动化」）：
     * 舞伴打开邀约落地页（demand-invite，分享卡片携带 demandId）时置
     * share_opened_at（WHERE share_opened_at IS NULL = 幂等只置第一次）——
     * 平台自动感知"分享生效"，客人侧「TA 已查看你的邀约」零操作可见。
     * 返回 0 = 已置过（幂等成功）或邀约不存在。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DemandRecord d SET d.shareOpenedAt = :at " +
            "WHERE d.id = :id AND d.shareOpenedAt IS NULL")
    int updateShareOpenedIf(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * 客人反馈条件更新（2026-08-27，V56，docs/agents/25「反馈闭环」）：客人对
     * 已解锁邀约提交「没加上 TA？」反馈时置 guest_feedback + feedback_requested_at
     * （WHERE feedback_requested_at IS NULL = 幂等只置一次——防重复提交重复返还
     * 积分；返还幂等键 (user, UNLOCK_REFUND, demandId) 双保险）。返回 0 =
     * 已反馈过（幂等成功）或邀约不存在。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DemandRecord d SET d.guestFeedback = :feedback, d.feedbackRequestedAt = :at " +
            "WHERE d.id = :id AND d.feedbackRequestedAt IS NULL")
    int updateFeedbackIf(@Param("id") Long id, @Param("feedback") String feedback, @Param("at") LocalDateTime at);

    /**
     * 管理端反馈待办（2026-08-28，V58，docs/agents/25「反馈闭环 · 管理端可见性
     * 修复」）：已反馈且未核实的邀约（<b>全舞伴范围</b>——客人反馈只发生在
     * 非中转舞伴的已发放/存量邀约上，若仍按中转舞伴集合过滤将零可见，见 V58 迁移
     * 注释；按 feedback_requested_at 倒序 = 最新反馈浮顶，走
     * idx_qwt_demand_records_feedback_pending 部分索引）。
     */
    @Query("SELECT d FROM DemandRecord d WHERE d.feedbackRequestedAt IS NOT NULL " +
            "AND d.guestFeedbackHandledAt IS NULL ORDER BY d.feedbackRequestedAt DESC")
    Page<DemandRecord> findPendingFeedback(Pageable pageable);

    /** 管理端反馈待办计数（2026-08-28，V58：计入邀约工作台待办红点，与
     *  countPendingByDancerIds 相加 = me 页「邀约工作台」入口红点总量） */
    @Query("SELECT COUNT(d) FROM DemandRecord d WHERE d.feedbackRequestedAt IS NOT NULL " +
            "AND d.guestFeedbackHandledAt IS NULL")
    long countPendingFeedback();

    /**
     * 反馈已核实条件更新（2026-08-28，V58，docs/agents/25「反馈闭环 · 管理端
     * 可见性修复」）：管理员微信侧核实（联系舞伴/客人）完成后置位
     * guest_feedback_handled_at（WHERE 双条件 = 天然幂等，重复核实/并发只成功
     * 一次）。返回 0 = 已核实过（幂等成功）或该邀约无反馈（调用方前置校验）。
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE DemandRecord d SET d.guestFeedbackHandledAt = :at " +
            "WHERE d.id = :id AND d.feedbackRequestedAt IS NOT NULL AND d.guestFeedbackHandledAt IS NULL")
    int updateFeedbackHandledIf(@Param("id") Long id, @Param("at") LocalDateTime at);

    /**
     * 该 user×dancer 最近一条邀约（2026-08-27，V56，docs/agents/25「进行中邀约」：
     * 舞伴详情页「进行中邀约」卡数据源——客人返回详情页时不再"邀约单消失"，
     * 恒可见最近一次邀约的时间/状态/被查看/履约入口）。走
     * idx_qwt_demand_records_user_dancer 索引，一条轻量查询。
     */
    Optional<DemandRecord> findTopByUserIdAndDancerIdOrderByIdDesc(Long userId, Long dancerId);

    /**
     * 该客人与该舞伴的履约确认数（「与 TA 已合作 N 次」，2026-08-27，V54，
     * docs/agents/23「P1 履约闭环」）：fulfilled_at 非空 = 客人已确认履约。
     * 走 idx_qwt_demand_records_user_dancer 索引；计数含本次已确认的邀约。
     */
    @Query("SELECT COUNT(d) FROM DemandRecord d WHERE d.userId = :userId " +
            "AND d.dancerId = :dancerId AND d.fulfilledAt IS NOT NULL")
    long countConfirmedByUserAndDancer(@Param("userId") Long userId, @Param("dancerId") Long dancerId);

    /**
     * 批量履约确认数（2026-08-27，V55，docs/agents/24：邀约工作台列表行拼转发
     * 话术信任信号用——「该客人 × 该舞伴已合作 N 次」，批量 GROUP BY 防 N+1，
     * 与单条 {@link #countConfirmedByUserAndDancer} 同口径）。返回行 =
     * [userId, dancerId, count]（Object[]）；范围 = 指定用户集合 × 指定舞伴集合
     * 内 fulfilled_at 非空组合（可能含不在本页邀约组合中的 user×dancer 对，结果集
     * 用户数级别，内存按 "userId:dancerId" 组合键合并无压力）。
     */
    @Query("SELECT d.userId, d.dancerId, COUNT(d) FROM DemandRecord d " +
            "WHERE d.userId IN :userIds AND d.dancerId IN :dancerIds AND d.fulfilledAt IS NOT NULL " +
            "GROUP BY d.userId, d.dancerId")
    List<Object[]> countConfirmedGroupByUserIdsAndDancerIds(
            @Param("userIds") Iterable<Long> userIds, @Param("dancerIds") Iterable<Long> dancerIds);

    /**
     * 批量统计：指定用户集的需求单总数（2026-08-27 用户管理增强——列表行
     * 「需求 N」+ 详情需求概览）。返回 Object[]{userId, count}。
     */
    @Query("SELECT d.userId, COUNT(d) FROM DemandRecord d " +
            "WHERE d.userId IN :userIds GROUP BY d.userId")
    List<Object[]> countGroupByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 批量统计：指定用户集的履约确认数（2026-08-27 用户管理增强——列表行
     * 「履约 N」+ 详情需求概览）。fulfilled_at 非空 = 客人已确认履约（V54）。
     * 返回 Object[]{userId, count}；无确认用户不出现在结果。
     */
    @Query("SELECT d.userId, COUNT(d) FROM DemandRecord d " +
            "WHERE d.userId IN :userIds AND d.fulfilledAt IS NOT NULL GROUP BY d.userId")
    List<Object[]> countFulfilledGroupByUserIds(@Param("userIds") Collection<Long> userIds);

    /**
     * 批量统计：指定用户集 × 邀约状态的需求单分布（2026-08-27 用户管理增强——
     * 详情页需求概览按状态分布）。返回 Object[]{userId, status, count}；
     * <b>存量 NULL 状态 = APPROVED</b>（COALESCE，V42 前无状态语义、历史客人
     * 当时已拿到微信，与 22 号文档状态机语义一致）。
     */
    @Query("SELECT d.userId, COALESCE(d.status, 'APPROVED'), COUNT(d) FROM DemandRecord d " +
            "WHERE d.userId IN :userIds GROUP BY d.userId, COALESCE(d.status, 'APPROVED')")
    List<Object[]> countByUserAndStatusGroup(@Param("userIds") Collection<Long> userIds);

    /**
     * 批量统计：指定用户集的最近邀约时间（2026-08-27 用户管理增强——「最近活跃」
     * 四源之一：邀约 = 主动寻找舞伴的高信号行为）。返回 Object[]{userId, MAX(createdAt)}。
     */
    @Query("SELECT d.userId, MAX(d.createdAt) FROM DemandRecord d " +
            "WHERE d.userId IN :userIds GROUP BY d.userId")
    List<Object[]> findLatestGroupByUserIds(@Param("userIds") Collection<Long> userIds);
}
