package org.quwuting.quwutingservice.taginteraction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.dto.response.DimensionScoreStats;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagStatsResponse;
import org.quwuting.quwutingservice.taginteraction.dto.response.WindowScore;
import org.quwuting.quwutingservice.taginteraction.entity.TagInteraction;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分交互服务：维度评分（upsert + 防刷冷却）。
 * <p>
 * 原"标签点赞"（toggleLike）功能已被 Reaction 快速反馈系统替代，见
 * {@link org.quwuting.quwutingservice.venuereaction.service.VenueReactionService}
 * 与 AGENTS.md「Reaction 快速反馈系统」章节。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TagInteractionService {

    private final TagInteractionRepository tagInteractionRepository;
    private final VenueLookupService venueLookupService;
    private final TagAggregateStatsService tagAggregateStatsService;
    private final VenueHeatService venueHeatService;

    /** 评分修改冷却时间（秒）：同一用户同一维度在此时间内不可重复改分，防止恶意刷分 */
    private static final long SCORE_COOLDOWN_SECONDS = 60;

    // ─── 评分（upsert 语义，含防刷冷却） ─────────────────────────────────

    /**
     * 对评分维度打分或修改分数（仅保留最新分）。
     * 防刷机制：同一用户同一维度 60 秒内不可重复改分。
     * 写操作即时失效场所级聚合缓存与热度缓存，理由同其余交互写路径
     * （评价数与满意度均为热度公式输入）。
     */
    @Transactional
    public void score(Long userId, Long venueId, String tag, int score) {
        venueLookupService.findById(venueId); // 存在性校验（缓存命中时 <1ms）
        if (!RatingDimensions.isValid(tag)) {
            throw new BusinessException(1007, "无效的评分维度");
        }

        var existing = tagInteractionRepository.findByUserIdAndVenueIdAndTag(userId, venueId, tag);
        if (existing.isPresent()) {
            TagInteraction ti = existing.get();
            // 防刷：检查冷却期（基于 updatedAt，改分后自动刷新）
            if (!ti.isDeleted() && ti.getUpdatedAt() != null) {
                long elapsed = Duration.between(ti.getUpdatedAt(), LocalDateTime.now()).getSeconds();
                if (elapsed < SCORE_COOLDOWN_SECONDS) {
                    long remaining = SCORE_COOLDOWN_SECONDS - elapsed;
                    throw new BusinessException(1006, "操作过于频繁，请" + remaining + "秒后再试");
                }
            }
            // 恢复或更新
            ti.setDeleted(false);
            ti.setScore(score);
            tagInteractionRepository.save(ti);
            invalidateVenueAggregates(venueId);
            return;
        }

        // 首次评分：创建新记录（确定性原子 upsert，2026-08-20 根因修复——替代
        // 「save + catch 23505 + 幂等忽略」：PG 语句失败后事务中止（25P02），旧 catch
        // 分支依赖 commit-on-aborted 静默回滚的 JPA 不可靠行为；ON CONFLICT DO NOTHING
        // 恒 1 次往返零异常，冲突 = 并发竞态已有行，幂等跳过，见 15-governance 错误表）
        tagInteractionRepository.upsertScore(userId, venueId, tag, score, LocalDateTime.now());
        invalidateVenueAggregates(venueId);
    }

    // ─── 聚合统计 ───────────────────────────────────────────────────────

    /**
     * 获取场所的评分统计（维度评分 + 时间窗口）。
     * 软鉴权：未登录时 myScore=null，聚合数据正常返回。
     * <p>
     * 场所级聚合数据（评分均值）由 {@link TagAggregateStatsService} 缓存 60s；
     * 当前用户的个人评分状态不缓存，每次实时查询——与 Reaction/Favorite 等模块的
     * "个人状态永远实时查询"约定一致。
     */
    @Transactional(readOnly = true)
    public TagStatsResponse getTagStats(Long venueId, Long currentUserId) {
        Map<String, double[]> aggregate = tagAggregateStatsService.getAggregate(venueId);

        Map<String, Integer> userScores = new HashMap<>();
        if (currentUserId != null) {
            for (Object[] row : tagInteractionRepository.findUserScoresByVenue(currentUserId, venueId)) {
                userScores.put((String) row[0], (Integer) row[1]);
            }
        }

        // ── 组装维度评分（无数据时 count=0, avgScore=null） ──
        List<DimensionScoreStats> dimensionScores = new ArrayList<>();
        for (String dim : RatingDimensions.ALL) {
            double[] mw = aggregate.get(dim);

            Double avg = mw != null && mw[1] > 0 ? roundToOneDecimal(mw[0]) : null;
            long count = mw != null ? (long) mw[1] : 0L;

            WindowScore w30 = new WindowScore(
                    mw != null && mw[3] > 0 ? roundToOneDecimal(mw[2]) : null,
                    mw != null ? (long) mw[3] : 0L);

            WindowScore w7 = new WindowScore(
                    mw != null && mw[5] > 0 ? roundToOneDecimal(mw[4]) : null,
                    mw != null ? (long) mw[5] : 0L);

            dimensionScores.add(new DimensionScoreStats(
                    dim, avg, count, userScores.get(dim), w30, w7));
        }

        return new TagStatsResponse(dimensionScores, RatingDimensions.ALL);
    }

    // ─── 内部工具 ───────────────────────────────────────────────────────

    /**
     * 写路径聚合缓存逐出：同时失效评分聚合与热度两个内嵌 LoadingCache。
     * 任何改变 qwt_tag_interactions 的写操作完成后必须调用（见 score）。
     */
    private void invalidateVenueAggregates(Long venueId) {
        tagAggregateStatsService.invalidate(venueId);
        venueHeatService.invalidate(venueId);
    }

    private static Double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
