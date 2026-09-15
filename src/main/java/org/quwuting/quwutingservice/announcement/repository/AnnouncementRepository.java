package org.quwuting.quwutingservice.announcement.repository;

import org.quwuting.quwutingservice.announcement.entity.Announcement;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementStatus;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementTouchLevel;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface AnnouncementRepository extends JpaRepository<Announcement, Long> {

    Optional<Announcement> findByIdAndDeletedFalse(Long id);

    /**
     * 用户端可见列表（分页倒序，pinned 优先）：PUBLISHED + 已生效
     * （publishAt 为空 = 立即生效）且未软删。<b>公告/快讯两侧共用</b>，靠
     * category / excludeCategory 两参数隔离（docs/agents/47 互斥契约）：
     * <ul>
     *   <li>公告侧：category=null + excludeCategory=FLASH（排除快讯）；
     *       快讯侧：category=FLASH + excludeCategory=null（只取快讯）。</li>
     * </ul>
     * <p>
     * pinned 过滤（2026-09-05 首页公告栏收口）：
     * <ul>
     *   <li>null = 不过滤（公告中心全量，置顶仅参与排序加权）；</li>
     *   <li>true = 仅置顶（首页公告栏数据源——置顶 = 首页强触达，非置顶只在公告中心出现）；
     *       快讯侧恒传 null（无置顶语义，纯时间流）。</li>
     * </ul>
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND (:category IS NULL OR a.category = :category)
              AND (:excludeCategory IS NULL OR a.category <> :excludeCategory)
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (:pinned IS NULL OR a.pinned = :pinned)
            ORDER BY a.pinned DESC, a.publishAt DESC, a.id DESC
            """)
    Page<Announcement> findVisiblePage(@Param("category") AnnouncementCategory category,
                                       @Param("excludeCategory") AnnouncementCategory excludeCategory,
                                       @Param("status") AnnouncementStatus status,
                                       @Param("now") LocalDateTime now,
                                       @Param("pinned") Boolean pinned,
                                       Pageable pageable);

    /**
     * 快讯信息流页（2026-09-11，时间<b>正序</b>：旧 → 新，最新一条在底部——聊天式
     * 信息流）。与 {@link #findVisiblePage}（公告/快讯共用、倒序新在前）刻意不同：
     * <b>公告域契约冻结</b>，快讯要倒排不能改公共查询，故本方法为快讯独有、只服务
     * 行业快讯信息流。
     * <p>
     * 判据（2026-09-11 用户拍板）：快讯是<b>按发布时间一根时间轴的流</b>——从最早
     * 发的开始顺着读、最新一条钉在底部（对齐群聊消息排序），与公告"新发布的置顶
     * 强触达"语义相反。publishAt 同刻用 id 兜底（确定性，防 null 抖动）。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND a.category = :category
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
            ORDER BY a.publishAt ASC, a.id ASC
            """)
    Page<Announcement> findBulletinFeedPage(@Param("category") AnnouncementCategory category,
                                            @Param("status") AnnouncementStatus status,
                                            @Param("now") LocalDateTime now,
                                            Pageable pageable);

    /**
     * 快讯信息流·游标窗口（2026-09-14「尾部优先」改造，docs/agents/47 §4.1）：
     * 取<b>严格早于</b>游标 {@code (cursorAt, cursorId)} 的<b>最后</b> {@code size} 条
     * ——按时间<b>倒序</b>取前 size 条，由调用方反转为正序返回。
     * <p>
     * 游标排序键与 {@link #findBulletinFeedPage} 完全同源（{@code publishAt, id}，
     * 同刻用 id 兜底）——判据：<b>游标必须用与排序相同的键</b>，否则窗口边界会随
     * 回填历史 publishAt 的条目漂移（id 顺序 ≠ 时间顺序）。{@code hasCursor=false}
     * 表示无上界（= 首屏「最近一屏」：最新的 size 条）。
     * <p>
     * 返回 List（非 Page）：keyset 分页的 hasMore 由调用方按「返回条数 &lt; size」判定，
     * 不需要 count 查询。⚠️ 游标条件假定 publishAt 非空（快讯发布路径恒写入；
     * publishAt 为 NULL 的理论残留在 SQL 三值逻辑下不命中任一比较，不参与游标窗口，
     * 只影响防御性分支，可接受）。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND a.category = :category
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (:hasCursor = false OR a.publishAt < :cursorAt
                   OR (a.publishAt = :cursorAt AND a.id < :cursorId))
            ORDER BY a.publishAt DESC, a.id DESC
            """)
    List<Announcement> findBulletinFeedBefore(@Param("category") AnnouncementCategory category,
                                              @Param("status") AnnouncementStatus status,
                                              @Param("now") LocalDateTime now,
                                              @Param("hasCursor") boolean hasCursor,
                                              @Param("cursorAt") LocalDateTime cursorAt,
                                              @Param("cursorId") Long cursorId,
                                              Pageable pageable);

    /**
     * 快讯信息流·游标窗口（从锚点向新，2026-09-14）：取<b>不早于</b>锚点
     * {@code (anchorAt, anchorId)} 的前 {@code size} 条，时间正序。两个消费方：
     * 分享落地（锚点 = 目标条目，使其出现在窗口首位）与静默收敛/触底（锚点 =
     * 当前末条，增量拉取新内容，末条重复由前端按 id 去重）。
     * <p>
     * 与 {@link #findBulletinFeedBefore} 同一套排序键与 List 返回口径；publishAt 为
     * NULL 的理论残留同样不参与窗口（见上）。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND a.category = :category
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND (a.publishAt > :anchorAt OR (a.publishAt = :anchorAt AND a.id >= :anchorId))
            ORDER BY a.publishAt ASC, a.id ASC
            """)
    List<Announcement> findBulletinFeedFrom(@Param("category") AnnouncementCategory category,
                                            @Param("status") AnnouncementStatus status,
                                            @Param("now") LocalDateTime now,
                                            @Param("anchorAt") LocalDateTime anchorAt,
                                            @Param("anchorId") Long anchorId,
                                            Pageable pageable);

    /**
     * 未读公告数：可见公告 − 该用户已读（NOT EXISTS 派生，对齐站内信 unread-count 模式）。
     * <p>
     * <b>口径（2026-09-15 收敛）</b>：只统计 {@code touchLevel = ALERT} 的公告——SILENT
     * （数据更新 / 每日舞讯等流水内容）恒不计入未读，否则日更公告会让徽标只增不减。
     * 判据见 {@link AnnouncementTouchLevel}；调用方一律传 {@code ALERT}，禁另写口径。
     * <p>
     * excludeCategory 由公告侧传 FLASH（快讯无已读回执，<b>绝不能计入公告未读数</b>——
     * 否则红点永不收敛，docs/agents/47）；快讯侧不使用本查询。
     * <p>
     * ⚠️ <b>同源声明</b>：本查询与
     * {@code AnnouncementReadRepository#markVisibleReads}（「全部已读」的批量写入）
     * 共享同一套未读判据（可见性谓词 + ALERT + 无回执）。<b>改此处条件必须同改那一处</b>，
     * 否则会出现"点了全部已读但徽标不清零"的幽灵数字（docs/agents/34「未读口径」）。
     */
    @Query("""
            SELECT COUNT(a) FROM Announcement a
            WHERE a.deleted = false AND a.status = :status
              AND (:excludeCategory IS NULL OR a.category <> :excludeCategory)
              AND a.touchLevel = :touchLevel
              AND (a.publishAt IS NULL OR a.publishAt <= :now)
              AND NOT EXISTS (
                  SELECT 1 FROM AnnouncementRead r
                  WHERE r.announcementId = a.id AND r.userId = :userId)
            """)
    long countUnread(@Param("excludeCategory") AnnouncementCategory excludeCategory,
                     @Param("status") AnnouncementStatus status,
                     @Param("touchLevel") AnnouncementTouchLevel touchLevel,
                     @Param("now") LocalDateTime now,
                     @Param("userId") Long userId);

    /**
     * 管理端列表（状态/分类/来源三重筛选，全部可空 = 不限制；id 倒序最新在前）。
     * <b>公告/快讯两侧共用</b>，同 {@link #findVisiblePage} 的隔离口径：
     * <ul>
     *   <li>公告侧：category=null + excludeCategory=FLASH（快讯不进公告管理列表）；</li>
     *   <li>快讯侧：category=FLASH + excludeCategory=null（只取快讯）。</li>
     * </ul>
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false
              AND (:status IS NULL OR a.status = :status)
              AND (:category IS NULL OR a.category = :category)
              AND (:excludeCategory IS NULL OR a.category <> :excludeCategory)
              AND (:source IS NULL OR a.source = :source)
            ORDER BY a.id DESC
            """)
    Page<Announcement> findPageByFilters(@Param("status") AnnouncementStatus status,
                                         @Param("category") AnnouncementCategory category,
                                         @Param("excludeCategory") AnnouncementCategory excludeCategory,
                                         @Param("source") AnnouncementSource source,
                                         Pageable pageable);

    /**
     * Agent 幂等查询：按 dedup_key 取未软删快讯（同键重跑返回已存在条目，
     * 与 V18 生成列唯一索引同口径——查询前置 + 唯一索引兜底并发）。
     */
    Optional<Announcement> findFirstByDedupKeyAndDeletedFalseOrderByIdDesc(String dedupKey);

    /**
     * DATA_UPDATE 同日防重：查询某天已存在的 SYSTEM 数据更新公告。
     * 日期口径 = publishAt 的日期（SYSTEM 公告创建即置 publishAt=now；
     * 兜底 COALESCE 到 createdAt）。与 V7 生成列唯一键同口径，查询防重 +
     * 唯一索引兜底防并发。
     */
    @Query("""
            SELECT a FROM Announcement a
            WHERE a.deleted = false
              AND a.source = :source AND a.category = :category
              AND DATE(COALESCE(a.publishAt, a.createdAt)) = :day
            ORDER BY a.id DESC
            """)
    List<Announcement> findForDay(@Param("source") AnnouncementSource source,
                                  @Param("category") AnnouncementCategory category,
                                  @Param("day") LocalDate day);

    /** 定时发布强转：DRAFT + 计划时间已到 → PUBLISHED（@Scheduled 调用，状态权威） */
    @Modifying
    @Query("""
            UPDATE Announcement a SET a.status = :target, a.publishedAt = :now
            WHERE a.deleted = false AND a.status = :draft
              AND a.publishAt IS NOT NULL AND a.publishAt <= :now
            """)
    int publishDue(@Param("draft") AnnouncementStatus draft,
                   @Param("target") AnnouncementStatus target,
                   @Param("now") LocalDateTime now);

    /** 定时下线强转：PUBLISHED + 计划下线时间已到 → OFFLINE */
    @Modifying
    @Query("""
            UPDATE Announcement a SET a.status = :target, a.offlinedAt = :now
            WHERE a.deleted = false AND a.status = :published
              AND a.offlineAt IS NOT NULL AND a.offlineAt <= :now
            """)
    int offlineDue(@Param("published") AnnouncementStatus published,
                   @Param("target") AnnouncementStatus target,
                   @Param("now") LocalDateTime now);

    /** 已读公告 ID 集合（列表页批量派生 read 布尔，一次 IN 查询） */
    @Query("""
            SELECT r.announcementId FROM AnnouncementRead r
            WHERE r.userId = :userId AND r.announcementId IN :ids
            """)
    Set<Long> findReadAnnouncementIds(@Param("userId") Long userId,
                                      @Param("ids") Collection<Long> ids);
}
