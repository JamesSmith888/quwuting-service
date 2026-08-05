package org.quwuting.quwutingservice.venuereaction.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStat;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStatsResponse;
import org.quwuting.quwutingservice.venuereaction.entity.VenueReaction;
import org.quwuting.quwutingservice.venuereaction.repository.VenueReactionRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 场所 Reaction 服务：toggle 参与 + 个人状态实时查询 + 列表页徽标编排。
 * <p>
 * 替代原"标签点赞"，见 AGENTS.md「Reaction 快速反馈系统」章节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueReactionService {

    /** 列表卡片最多展示的 Top Reaction 数（只展示 emoji，过多会挤占卡片空间） */
    private static final int LIST_BADGE_LIMIT = 4;

    private final VenueReactionRepository venueReactionRepository;
    private final VenueReactionAggregateService aggregateService;
    private final VenueLookupService venueLookupService;
    private final VenueHeatService venueHeatService;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * 切换 Reaction 参与状态（toggle：未参与→参与，已参与→取消）。
     * <p>
     * Upsert 软删恢复模式：与 FavoriteService/StatusReportService 一致——查找时不限 deleted，
     * 软删记录仍占用 UNIQUE(userId, venueId, reactionCode) 槽位。恢复时刷新 createdAt，
     * 使其重新计入"今日/7天/30天"窗口（时效性设计的核心机制，见 AGENTS.md）。
     *
     * @return true=当前已参与，false=当前已取消
     */
    @Transactional
    public boolean toggle(Long userId, Long venueId, String code) {
        if (!ReactionCode.isValid(code)) {
            throw new BusinessException(1007, "无效的 Reaction 类型");
        }
        venueLookupService.findById(venueId); // 存在性校验（缓存命中时 <1ms）

        var existing = venueReactionRepository.findByUserIdAndVenueIdAndReactionCode(userId, venueId, code);
        boolean reacted;
        if (existing.isPresent()) {
            VenueReaction reaction = existing.get();
            if (reaction.isDeleted()) {
                reaction.setDeleted(false);
                reaction.setCreatedAt(LocalDateTime.now()); // 刷新以计入近期窗口
                venueReactionRepository.save(reaction);
                reacted = true;
            } else {
                reaction.setDeleted(true);
                venueReactionRepository.save(reaction);
                reacted = false;
            }
        } else {
            VenueReaction reaction = new VenueReaction();
            reaction.setUserId(userId);
            reaction.setVenueId(venueId);
            reaction.setReactionCode(code);
            try {
                venueReactionRepository.save(reaction);
                reacted = true;
            } catch (DataIntegrityViolationException e) {
                // 并发竞态：另一请求已创建，幂等视为已参与
                log.debug("toggle Reaction 并发冲突，幂等忽略: userId={}, venueId={}, code={}", userId, venueId, code);
                entityManager.clear();
                reacted = true;
            }
        }
        aggregateService.invalidate(venueId);
        venueHeatService.invalidate(venueId); // Reaction 总量是热度公式输入之一
        return reacted;
    }

    /**
     * 详情页完整 Reaction 统计：字典内全部代码，按声明顺序返回（无数据时计数为 0）。
     * 聚合计数走缓存共享；个人参与状态单独实时查询，不与聚合数据混存。
     */
    @Transactional(readOnly = true)
    public ReactionStatsResponse getStats(Long venueId, Long currentUserId) {
        Map<String, long[]> aggregate = aggregateService.getAggregate(venueId);
        Set<String> myCodes = currentUserId != null
                ? new HashSet<>(venueReactionRepository.findActiveCodesByUserAndVenue(currentUserId, venueId))
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
     * 单场所的 Top Reaction 徽标（列表/详情基础响应用），按近30天计数排序，count=0 的不展示。
     * 复用聚合缓存的 30d 分量，个人状态单独实时查询（成本为一次按 userId+venueId 的索引查询）。
     */
    @Transactional(readOnly = true)
    public List<ReactionBadge> getBadges(Long venueId, Long currentUserId) {
        Map<String, long[]> aggregate = aggregateService.getAggregate(venueId);
        Set<String> myCodes = currentUserId != null
                ? new HashSet<>(venueReactionRepository.findActiveCodesByUserAndVenue(currentUserId, venueId))
                : Collections.emptySet();
        return buildTopBadges(aggregate, myCodes);
    }

    /**
     * 批量场所的 Top Reaction 徽标（列表页用），一次 IN 查询覆盖整页场所 + 一次 IN 查询覆盖个人状态。
     * 不缓存——列表页请求的场所集合每次不同（翻页/筛选变化），复用单场所聚合缓存收益低，
     * 与既有 batchGetTagLikeCounts 的"批量查询不缓存"约定一致。
     * <p>
     * 个人状态例外说明：列表层通常不携带个人状态，但 Reaction 列表卡片明确要求"点击即知是否已参与"
     * （产品规则），故额外做一次批量个人状态查询——仅登录用户触发，成本为一次 IN 查询。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<ReactionBadge>> batchGetBadges(List<Long> venueIds, Long currentUserId) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Collections.emptyMap();
        }
        LocalDateTime since30d = LocalDateTime.now().minusDays(30);

        Map<Long, Map<String, Long>> countsByVenue = new HashMap<>();
        for (Object[] row : venueReactionRepository.countRecentByVenueIdsGroupByCode(venueIds, since30d)) {
            Long venueId = (Long) row[0];
            String code = (String) row[1];
            Long count = (Long) row[2];
            countsByVenue.computeIfAbsent(venueId, k -> new HashMap<>()).put(code, count);
        }

        Map<Long, Set<String>> myCodesByVenue = new HashMap<>();
        if (currentUserId != null) {
            for (Object[] row : venueReactionRepository.findActiveCodesByUserAndVenueIds(currentUserId, venueIds)) {
                Long venueId = (Long) row[0];
                String code = (String) row[1];
                myCodesByVenue.computeIfAbsent(venueId, k -> new HashSet<>()).add(code);
            }
        }

        Map<Long, List<ReactionBadge>> result = new HashMap<>();
        for (Long venueId : venueIds) {
            Map<String, Long> counts = countsByVenue.getOrDefault(venueId, Collections.emptyMap());
            Set<String> myCodes = myCodesByVenue.getOrDefault(venueId, Collections.emptySet());
            result.put(venueId, buildTopBadgesFromCount30d(counts, myCodes));
        }
        return result;
    }

    private List<ReactionBadge> buildTopBadges(Map<String, long[]> aggregate, Set<String> myCodes) {
        Map<String, Long> count30dByCode = new HashMap<>();
        for (Map.Entry<String, long[]> entry : aggregate.entrySet()) {
            count30dByCode.put(entry.getKey(), entry.getValue()[3]);
        }
        return buildTopBadgesFromCount30d(count30dByCode, myCodes);
    }

    /**
     * 从 30 天计数 Map 构建 Top N 徽标（最多 {@value #LIST_BADGE_LIMIT} 个）。
     * <p>
     * 包含 count=0 的 ReactionCode——避免冷启动死锁（无数据时 UI 不渲染入口，
     * 导致无法创建第一条 Reaction，见 AGENTS.md「Reaction 快速反馈系统」章节）。
     * 按 count 降序排列，count=0 的条目按 {@link ReactionCode} 枚举声明顺序作为稳定平局规则。
     */
    private List<ReactionBadge> buildTopBadgesFromCount30d(Map<String, Long> count30dByCode, Set<String> myCodes) {
        // 构建完整字典（缺失的 ReactionCode 补 0），保证 count=0 时 UI 入口仍然可交互
        Map<String, Long> complete = new LinkedHashMap<>();
        for (ReactionCode rc : ReactionCode.values()) {
            complete.put(rc.name(), count30dByCode.getOrDefault(rc.name(), 0L));
        }
        return complete.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(LIST_BADGE_LIMIT)
                .map(e -> {
                    ReactionCode rc = ReactionCode.valueOf(e.getKey());
                    return new ReactionBadge(rc.name(), rc.getEmoji(), rc.getLabel(), e.getValue(), myCodes.contains(rc.name()));
                })
                .collect(Collectors.toList());
    }
}
