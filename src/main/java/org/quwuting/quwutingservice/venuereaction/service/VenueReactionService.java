package org.quwuting.quwutingservice.venuereaction.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.db.DbConstraintViolations;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStat;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStatsResponse;
import org.quwuting.quwutingservice.venuereaction.entity.VenueReaction;
import org.quwuting.quwutingservice.venuereaction.repository.VenueReactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 场所 Reaction 服务：toggle 参与 + 个人状态实时查询 + 列表页徽标编排。
 * <p>
 * 2026-08 迁移为"每日一记"模型（根因见 AGENTS.md「Reaction 快速反馈系统」）：
 * 每次点击 = 插入今日记录，取消 = 物理删除今日记录（同日唯一约束 (userId, venueId, code, date)）。
 * 取代旧"toggle 软删 hold 模型"——旧模型下取消可能作用于 createdAt 超窗的旧记录，
 * 使近7天/近30天窗口计数无法本地精确推导，迫使前端发明"展示 countAll、排序 count30d"的双计数 hack；
 * 每日一记模型下取消只作用于当日记录，四个窗口计数本地 ±1 全部精确，hack 消失。
 * <p>
 * 替代原"标签点赞"，见 AGENTS.md「Reaction 快速反馈系统」章节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueReactionService {

    /**
     * 列表卡片最多展示的 Top Reaction 数（只展示 emoji，过多会挤占卡片空间）。
     * 当前用户已参与的 code 不受此限制——"点击即知是否已参与"是产品契约，
     * 参与项必须恒在徽标内，见 {@link #buildTopBadgesFromCounts} javadoc（2026-08-08 根因修复）。
     */
    private static final int LIST_BADGE_LIMIT = 4;

    private final VenueReactionRepository venueReactionRepository;
    private final VenueReactionAggregateService aggregateService;
    private final VenueLookupService venueLookupService;
    private final VenueHeatService venueHeatService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 切换 Reaction 参与状态（toggle：今日未参与→参与，今日已参与→取消）。
     * <p>
     * 每日一记模型：
     * <ul>
     *   <li>未命中今日记录 → 插入（reactionDate = 今天），贡献 +1（所有窗口）；</li>
     *   <li>命中今日记录 → 物理删除（取消当日 Reaction），贡献 -1（所有窗口）；</li>
     *   <li>同日并发重复插入 → 唯一约束冲突，幂等视为已参与（防连点/多端竞态）。</li>
     * </ul>
     *
     * @return true=当前已参与（今日有记录），false=当前已取消（今日无记录）
     */
    @Transactional
    public boolean toggle(Long userId, Long venueId, String code) {
        if (!ReactionCode.isValid(code)) {
            throw new BusinessException(1007, "无效的 Reaction 类型");
        }
        venueLookupService.findById(venueId); // 存在性校验（缓存命中时 <1ms）

        LocalDate today = LocalDate.now();
        var existing = venueReactionRepository
                .findByUserIdAndVenueIdAndReactionCodeAndReactionDate(userId, venueId, code, today);
        boolean reacted;
        if (existing.isPresent()) {
            // 取消当日 Reaction：物理删除（"取消当天 Reaction"语义 = 当日贡献移除）
            venueReactionRepository.delete(existing.get());
            reacted = false;
        } else {
            VenueReaction reaction = new VenueReaction();
            reaction.setUserId(userId);
            reaction.setVenueId(venueId);
            reaction.setReactionCode(code);
            reaction.setReactionDate(today);
            try {
                venueReactionRepository.save(reaction);
                reacted = true;
            } catch (DataIntegrityViolationException e) {
                if (!DbConstraintViolations.isUniqueViolation(e)) {
                    // 非唯一键冲突（NOT NULL/列约束/外键）不能当并发竞态吞掉——
                    // 项目统一约定（见 AGENTS.md「并发与幂等」）：只允许吞 SQLState 23505
                    throw e;
                }
                // 并发竞态：另一请求已创建今日记录，幂等视为已参与
                log.debug("toggle Reaction 并发冲突，幂等忽略: userId={}, venueId={}, code={}", userId, venueId, code);
                entityManager.clear();
                reacted = true;
            }
        }
        // 聚合/热度缓存失效必须延后到事务提交后（2026-08-08 根因修复）：
        // 事务提交前失效存在竞态窗口——另一线程读到 cache miss → 回源重算 → 读不到本事务
        // 未提交数据 → 缓存陈旧值（refreshAfterWrite 60s 内持续返回）。afterCommit 注册的
        // 回调在提交完成后执行，此时任何回源都能读到已提交数据。
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                aggregateService.invalidate(venueId);
                venueHeatService.invalidate(venueId); // Reaction 总量是热度公式输入之一
            }
        });
        return reacted;
    }

    /**
     * 详情页完整 Reaction 统计：字典内全部代码，按声明顺序返回（无数据时计数为 0）。
     * 聚合计数走缓存共享；个人参与状态（今日已参与）单独实时查询，不与聚合数据混存。
     */
    @Transactional(readOnly = true)
    public ReactionStatsResponse getStats(Long venueId, Long currentUserId) {
        Map<String, long[]> aggregate = aggregateService.getAggregate(venueId);
        Set<String> myCodes = currentUserId != null
                ? new HashSet<>(venueReactionRepository.findTodayCodesByUserAndVenue(
                        currentUserId, venueId, LocalDate.now()))
                : Collections.emptySet();

        List<ReactionStat> stats = new ArrayList<>();
        for (ReactionCode rc : ReactionCode.values()) {
            long[] counts = aggregate.get(rc.name());
            long countAll = counts != null ? counts[0] : 0L;
            long countToday = counts != null ? counts[1] : 0L;
            long count7d = counts != null ? counts[2] : 0L;
            long count30d = counts != null ? counts[3] : 0L;
            stats.add(new ReactionStat(
                    rc.name(), rc.getEmoji(), rc.getLabel(),
                    countToday, count7d, count30d, countAll,
                    myCodes.contains(rc.name())));
        }
        return new ReactionStatsResponse(stats);
    }

    /**
     * 单场所的 Top Reaction 徽标（详情基础响应用），按所选窗口计数排序，count=0 的不展示。
     * 复用聚合缓存的窗口分量，个人状态单独实时查询（成本为一次按 userId+venueId+date 的索引查询）。
     *
     * @param window 徽标排序/筛选窗口（null → 默认近7天）
     */
    @Transactional(readOnly = true)
    public List<ReactionBadge> getBadges(Long venueId, Long currentUserId, ReactionWindow window) {
        Map<String, long[]> aggregate = aggregateService.getAggregate(venueId);
        Set<String> myCodes = currentUserId != null
                ? new HashSet<>(venueReactionRepository.findTodayCodesByUserAndVenue(
                        currentUserId, venueId, LocalDate.now()))
                : Collections.emptySet();
        return buildTopBadges(aggregate, myCodes, window);
    }

    /**
     * 批量场所的 Top Reaction 徽标（列表页用），一次 IN 查询覆盖整页场所的
     * countAll + count7d + count30d（单条 SQL，见 {@link VenueReactionRepository#countByVenueIdsGroupByCode}）
     * + 一次 IN 查询覆盖个人状态。不缓存——列表页请求的场所集合每次不同（翻页/筛选变化），
     * 复用单场所聚合缓存收益低，与既有 batchGetTagLikeCounts 的"批量查询不缓存"约定一致。
     * <p>
     * 个人状态例外说明：列表层通常不携带个人状态，但 Reaction 列表卡片明确要求"点击即知是否已参与"
     * （产品规则），故额外做一次批量个人状态查询——仅登录用户触发，成本为一次 IN 查询。
     *
     * @param window 徽标排序/筛选窗口（null → 默认近7天）
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ReactionBadge>> batchGetBadges(List<Long> venueIds, Long currentUserId, ReactionWindow window) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LocalDate today = LocalDate.now();
        LocalDateTime since7d = LocalDateTime.now().minusDays(7);
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);

        // 单条 SQL 同时聚合 countAll/count7d/count30d：排序/筛选按所选窗口，展示同窗口计数
        Map<Long, Map<String, Long>> countAllByVenue = new HashMap<>();
        Map<Long, Map<String, Long>> count7dByVenue = new HashMap<>();
        Map<Long, Map<String, Long>> count30dByVenue = new HashMap<>();
        for (Object[] row : venueReactionRepository.countByVenueIdsGroupByCode(venueIds, since7d, since30d)) {
            Long venueId = (Long) row[0];
            String code = (String) row[1];
            Long countAll = row[2] != null ? ((Number) row[2]).longValue() : 0L;
            Long count7d = row[3] != null ? ((Number) row[3]).longValue() : 0L;
            Long count30d = row[4] != null ? ((Number) row[4]).longValue() : 0L;
            countAllByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, countAll);
            count7dByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, count7d);
            count30dByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, count30d);
        }

        Map<Long, Set<String>> myCodesByVenue = new HashMap<>();
        if (currentUserId != null) {
            for (Object[] row : venueReactionRepository.findTodayCodesByUserAndVenueIds(currentUserId, venueIds, today)) {
                Long venueId = (Long) row[0];
                String code = (String) row[1];
                myCodesByVenue.computeIfAbsent(venueId, k -> new HashSet<>()).add(code);
            }
        }

        Map<Long, List<ReactionBadge>> result = new HashMap<>();
        for (Long venueId : venueIds) {
            Map<String, Long> countAlls = countAllByVenue.getOrDefault(venueId, Collections.emptyMap());
            Map<String, Long> count7ds = count7dByVenue.getOrDefault(venueId, Collections.emptyMap());
            Map<String, Long> count30ds = count30dByVenue.getOrDefault(venueId, Collections.emptyMap());
            Set<String> myCodes = myCodesByVenue.getOrDefault(venueId, Collections.emptySet());
            result.put(venueId, buildTopBadgesFromCounts(countAlls, count7ds, count30ds, myCodes, window));
        }
        return result;
    }

    private List<ReactionBadge> buildTopBadges(Map<String, long[]> aggregate, Set<String> myCodes,
                                               ReactionWindow window) {
        // aggregate value: long[]{countAll, countToday, count7d, count30d}
        Map<String, Long> countAllByCode = new HashMap<>();
        Map<String, Long> count7dByCode = new HashMap<>();
        Map<String, Long> count30dByCode = new HashMap<>();
        for (Map.Entry<String, long[]> entry : aggregate.entrySet()) {
            long[] v = entry.getValue();
            countAllByCode.put(entry.getKey(), v[0]);
            count7dByCode.put(entry.getKey(), v[2]);
            count30dByCode.put(entry.getKey(), v[3]);
        }
        return buildTopBadgesFromCounts(countAllByCode, count7dByCode, count30dByCode, myCodes, window);
    }

    /**
     * 从三窗口计数 Map 构建 Top N 徽标（Top {@value #LIST_BADGE_LIMIT} 个 + 当前用户已参与的 code）。
     * 排序/筛选以所选窗口（{@link ReactionWindow}）的计数为准，徽标内同时携带三个窗口计数
     * 供前端按窗口展示 + 乐观更新本地 ±1（每日一记模型下全部窗口均精确）——
     * count=0 的条目不展示：Reaction 只在有人参与后才出现，创建新 Reaction 的入口是
     * 前端 Picker 表情选择器（长按卡片 / 点击"+"触发），参见 AGENTS.md「Reaction 快速反馈系统」。
     * <p>
     * 2026-08-08 根因修复：**当前用户已参与的 code 不受 Top N 截断**。用户刚参与的
     * 新 code（窗口计数常为 1）可能排在截断线以下，若被截断，列表数据重取（收藏 Tab
     * onShow 刷新 / 下拉刷新 / Skyline 列表回收重派）后卡片上"我参与的 chip"会凭空消失，
     * 而详情页（/reactions/stats 全量）仍在——两页不一致，根因见 AGENTS.md「跨页一致性同步」。
     * 参与即贡献 ≥1（今日记录计入全部窗口，含任意排序窗口），追加项天然满足
     * "count>0 才展示"不变量；已参与 code 若已在 Top N 内则不重复追加。
     * 追加位置在 Top N 之后（前端按窗口计数重排展示，顺序无需在此维护）。
     */
    private List<ReactionBadge> buildTopBadgesFromCounts(Map<String, Long> countAllByCode,
                                                         Map<String, Long> count7dByCode,
                                                         Map<String, Long> count30dByCode,
                                                         Set<String> myCodes,
                                                         ReactionWindow window) {
        Map<String, Long> sortKeyByCode = window == ReactionWindow.DAYS_30
                ? count30dByCode
                : (window == ReactionWindow.ALL ? countAllByCode : count7dByCode);
        List<Map.Entry<String, Long>> ranked = sortKeyByCode.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0)
                // 2026-08-08 防御：枚举外的残留 code（历史数据/seed 旧值/未来误写）跳过不崩——
                // 枚举删除/改名后，库中旧 code 仍可能被聚合查询返回（如 V3 迁移前的
                // YOUNG_PARTNER/OLD_PARTNER）。裸 valueOf 会抛 IllegalArgumentException
                // 让整个详情/列表接口 500。与 getStats（ReactionCode.values() 遍历 + filter）
                // 和 VenueHeatService（values() 流）的"枚举外 code 优雅忽略"行为对齐。
                .filter(e -> ReactionCode.isValid(e.getKey()))
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toList());
        // 先取 Top N（按所选窗口计数降序）
        List<Map.Entry<String, Long>> selected =
                new ArrayList<>(ranked.stream().limit(LIST_BADGE_LIMIT).toList());
        // 用户已参与的 code 不受 Top N 截断（2026-08-08 根因修复，见方法 javadoc）：
        // myCodes 中的 code 必在 ranked 内（参与 → 今日记录计入全部窗口 → 各窗口计数 ≥1），
        // 仅在截断线以下时追加，已在 Top N 内则不重复。
        for (Map.Entry<String, Long> e : ranked) {
            if (myCodes.contains(e.getKey())
                    && selected.stream().noneMatch(s -> s.getKey().equals(e.getKey()))) {
                selected.add(e);
            }
        }
        List<ReactionBadge> badges = new ArrayList<>();
        for (Map.Entry<String, Long> e : selected) {
            ReactionCode rc = ReactionCode.valueOf(e.getKey());
            badges.add(new ReactionBadge(rc.name(), rc.getEmoji(), rc.getLabel(),
                    countAllByCode.getOrDefault(e.getKey(), 0L),
                    count7dByCode.getOrDefault(e.getKey(), 0L),
                    count30dByCode.getOrDefault(e.getKey(), 0L),
                    myCodes.contains(rc.name())));
        }
        return badges;
    }
}
