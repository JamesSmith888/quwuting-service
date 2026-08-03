package org.quwuting.quwutingservice.taginteraction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.dto.response.DimensionScoreStats;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagLikeStats;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagStatsResponse;
import org.quwuting.quwutingservice.taginteraction.dto.response.WindowScore;
import org.quwuting.quwutingservice.taginteraction.entity.TagInteraction;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagInteractionService {

    private final TagInteractionRepository tagInteractionRepository;
    private final VenueLookupService venueLookupService;
    private final ObjectMapper objectMapper;
    private final TagAggregateStatsService tagAggregateStatsService;
    private final VenueHeatService venueHeatService;

    /** 评分修改冷却时间（秒）：同一用户同一维度在此时间内不可重复改分，防止恶意刷分 */
    private static final long SCORE_COOLDOWN_SECONDS = 60;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    // ─── 点赞（toggle 语义） ────────────────────────────────────────────

    /**
     * 切换标签点赞状态。
     * 首次调用=点赞，再次调用=取消。仅允许对场所当前 tags 中存在的标签操作。
     * <p>
     * 写操作即时失效场所级聚合缓存（{@link TagAggregateStatsService}）与热度缓存
     * （{@link VenueHeatService}）：点赞数既是标签统计的直接输出，也是热度公式输入
     * （likeCount30d × 3）——两个内嵌 LoadingCache 都必须显式 invalidate，
     * 而不是等待刷新周期，保证点赞后所有用户很快看到最新值。
     *
     * @return true=当前已赞，false=当前已取消
     */
    @Transactional
    public boolean toggleLike(Long userId, Long venueId, String tag) {
        Venue venue = assertVenueExists(venueId);
        List<String> venueTags = deserializeStringList(venue.getTags());
        if (!venueTags.contains(tag)) {
            throw new BusinessException(1007, "该标签不存在或已被移除");
        }

        var existing = tagInteractionRepository.findByUserIdAndVenueIdAndTag(userId, venueId, tag);
        if (existing.isPresent()) {
            TagInteraction ti = existing.get();
            boolean result;
            if (ti.isDeleted()) {
                // 记录曾被逻辑删除（之前取消点赞且无评分），恢复并设为已赞
                ti.setDeleted(false);
                ti.setLiked(true);
                tagInteractionRepository.save(ti);
                result = true;
            } else {
                // toggle：已赞→取消，未赞→点赞
                boolean newLiked = !ti.isLiked();
                ti.setLiked(newLiked);
                // 若取消点赞且无评分，该行已无业务意义，逻辑删除
                if (!newLiked && ti.getScore() == null) {
                    ti.setDeleted(true);
                }
                tagInteractionRepository.save(ti);
                result = newLiked;
            }
            invalidateVenueAggregates(venueId);
            return result;
        }

        // 首次交互：创建新记录
        TagInteraction ti = new TagInteraction();
        ti.setUserId(userId);
        ti.setVenueId(venueId);
        ti.setTag(tag);
        ti.setLiked(true);
        try {
            tagInteractionRepository.save(ti);
        } catch (DataIntegrityViolationException e) {
            // 并发竞态：另一请求已创建（liked=true），幂等返回已赞
            log.debug("toggleLike 并发冲突，幂等返回: userId={}, venueId={}, tag={}", userId, venueId, tag);
        }
        invalidateVenueAggregates(venueId);
        return true;
    }

    // ─── 评分（upsert 语义，含防刷冷却） ─────────────────────────────────

    /**
     * 对评分维度打分或修改分数（仅保留最新分）。
     * 防刷机制：同一用户同一维度 60 秒内不可重复改分。
     * 写操作即时失效场所级聚合缓存与热度缓存，理由同 {@link #toggleLike}
     * （评价数与满意度均为热度公式输入）。
     */
    @Transactional
    public void score(Long userId, Long venueId, String tag, int score) {
        assertVenueExists(venueId);
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
            if (!ti.isLiked()) {
                // 仅打分未点赞的记录保持 liked=false，不影响点赞统计
            }
            tagInteractionRepository.save(ti);
            invalidateVenueAggregates(venueId);
            return;
        }

        // 首次评分：创建新记录（liked 默认 true 不适用于纯评分场景，设为 false）
        TagInteraction ti = new TagInteraction();
        ti.setUserId(userId);
        ti.setVenueId(venueId);
        ti.setTag(tag);
        ti.setLiked(false);
        ti.setScore(score);
        try {
            tagInteractionRepository.save(ti);
        } catch (DataIntegrityViolationException e) {
            // 并发竞态：另一请求已创建，幂等忽略
            log.debug("score 并发冲突，幂等忽略: userId={}, venueId={}, tag={}", userId, venueId, tag);
        }
        invalidateVenueAggregates(venueId);
    }

    // ─── 聚合统计 ───────────────────────────────────────────────────────

    /**
     * 获取场所的标签交互统计（点赞 + 维度评分 + 时间窗口）。
     * 软鉴权：未登录时 likedByMe=false、myScore=null，聚合数据正常返回。
     * <p>
     * 场所级聚合数据（点赞计数、评分均值）由 {@link TagAggregateStatsService} 缓存 60s；
     * 当前用户的个人交互状态（我是否已赞/我的评分）<b>不缓存，每次实时查询</b>——
     * 这是修复"点赞后返回列表再进入又消失"问题的关键：个人状态必须与操作结果强一致，
     * 缓存的应当只是与用户无关的公共聚合数据。
     */
    @Transactional(readOnly = true)
    public TagStatsResponse getTagStats(Long venueId, Long currentUserId) {
        TagAggregateStatsService.TagAggregate aggregate = tagAggregateStatsService.getAggregate(venueId);

        // ── 当前用户交互状态：点赞 + 评分 合并为 1 次往返，实时查询不缓存 ──
        Set<String> likedTagsBuilder = new HashSet<>();
        Map<String, Integer> scoresBuilder = new HashMap<>();
        if (currentUserId != null) {
            List<Object[]> userRows = tagInteractionRepository
                    .findUserInteractionsByVenue(currentUserId, venueId);
            for (Object[] row : userRows) {
                String tag = (String) row[0];
                Boolean liked = (Boolean) row[1];
                Integer score = (Integer) row[2];
                if (Boolean.TRUE.equals(liked)) {
                    likedTagsBuilder.add(tag);
                }
                if (score != null) {
                    scoresBuilder.put(tag, score);
                }
            }
        }
        final Set<String> userLikedTags = Collections.unmodifiableSet(likedTagsBuilder);
        final Map<String, Integer> userScores = Collections.unmodifiableMap(scoresBuilder);

        // ── 组装标签点赞（只返回当前存在于场所 tags 中的标签） ──
        List<TagLikeStats> tagLikes = aggregate.venueTags().stream()
                .map(tag -> new TagLikeStats(
                        tag,
                        aggregate.likeCounts().getOrDefault(tag, 0L),
                        userLikedTags.contains(tag)))
                .toList();

        // ── 组装维度评分（无数据时 count=0, avgScore=null） ──
        List<DimensionScoreStats> dimensionScores = new ArrayList<>();
        for (String dim : RatingDimensions.ALL) {
            double[] mw = aggregate.multiWindow().get(dim);

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

        return new TagStatsResponse(tagLikes, dimensionScores, RatingDimensions.ALL);
    }

    /**
     * 批量获取多个场所各标签的点赞数（列表页展示标签热度用）。
     * 不含 likedByMe（列表层不需要个人状态，见项目信息层级原则），直接实时查询、无缓存——
     * 列表页请求频率低于详情页且每次请求的场所集合不同，缓存收益低。
     *
     * @return venueId → (tag → likeCount)，无点赞记录的场所/标签不出现在结果中，调用方按需 getOrDefault
     */
    @Transactional(readOnly = true)
    public Map<Long, Map<String, Long>> batchGetTagLikeCounts(List<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<Long, Map<String, Long>> result = new HashMap<>();
        for (Object[] row : tagInteractionRepository.countLikesByVenueIdsGroupByTag(venueIds)) {
            Long venueId = (Long) row[0];
            String tag = (String) row[1];
            Long count = (Long) row[2];
            result.computeIfAbsent(venueId, k -> new HashMap<>()).put(tag, count);
        }
        return result;
    }

    // ─── 内部工具 ───────────────────────────────────────────────────────

    /**
     * 写路径聚合缓存逐出：同时失效标签聚合与热度两个内嵌 LoadingCache。
     * 任何改变 qwt_tag_interactions 的写操作完成后必须调用（见 toggleLike / score）。
     */
    private void invalidateVenueAggregates(Long venueId) {
        tagAggregateStatsService.invalidate(venueId);
        venueHeatService.invalidate(venueId);
    }

    private Venue assertVenueExists(Long venueId) {
        return venueLookupService.findById(venueId);
    }

    private List<String> deserializeStringList(String json) {
        if (!StringUtils.hasText(json)) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, STRING_LIST);
        } catch (Exception e) {
            log.warn("Failed to deserialize tags: {}", json);
            return Collections.emptyList();
        }
    }


    private static Double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
