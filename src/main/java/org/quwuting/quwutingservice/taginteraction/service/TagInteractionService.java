package org.quwuting.quwutingservice.taginteraction.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.taginteraction.RatingDimensions;
import org.quwuting.quwutingservice.taginteraction.dto.response.DimensionScoreStats;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagLikeStats;
import org.quwuting.quwutingservice.taginteraction.dto.response.TagStatsResponse;
import org.quwuting.quwutingservice.taginteraction.dto.response.WindowScore;
import org.quwuting.quwutingservice.taginteraction.entity.TagInteraction;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.cache.annotation.Cacheable;
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
    private final VenueRepository venueRepository;
    private final ObjectMapper objectMapper;

    /** 评分修改冷却时间（秒）：同一用户同一维度在此时间内不可重复改分，防止恶意刷分 */
    private static final long SCORE_COOLDOWN_SECONDS = 60;

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};

    // ─── 点赞（toggle 语义） ────────────────────────────────────────────

    /**
     * 切换标签点赞状态。
     * 首次调用=点赞，再次调用=取消。仅允许对场所当前 tags 中存在的标签操作。
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
            if (ti.isDeleted()) {
                // 记录曾被逻辑删除（之前取消点赞且无评分），恢复并设为已赞
                ti.setDeleted(false);
                ti.setLiked(true);
                tagInteractionRepository.save(ti);
                return true;
            }
            // toggle：已赞→取消，未赞→点赞
            boolean newLiked = !ti.isLiked();
            ti.setLiked(newLiked);
            // 若取消点赞且无评分，该行已无业务意义，逻辑删除
            if (!newLiked && ti.getScore() == null) {
                ti.setDeleted(true);
            }
            tagInteractionRepository.save(ti);
            return newLiked;
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
        return true;
    }

    // ─── 评分（upsert 语义，含防刷冷却） ─────────────────────────────────

    /**
     * 对评分维度打分或修改分数（仅保留最新分）。
     * 防刷机制：同一用户同一维度 60 秒内不可重复改分。
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
    }

    // ─── 聚合统计 ───────────────────────────────────────────────────────

    /**
     * 获取场所的标签交互统计（点赞 + 维度评分 + 时间窗口）。
     * 软鉴权：未登录时 likedByMe=false、myScore=null，聚合数据正常返回。
     * <p>
     * 性能优化：通过合并查询将原 7 次串行 DB 往返合并为 4 次
     * （venue + likes + multiWindowScores + userState），并加 60s TTL 缓存。
     */
    @Cacheable(value = CacheConfig.CACHE_TAG_STATS, key = "{#venueId, #currentUserId}")
    @Transactional(readOnly = true)
    public TagStatsResponse getTagStats(Long venueId, Long currentUserId) {
        Venue venue = assertVenueExists(venueId);
        List<String> venueTags = deserializeStringList(venue.getTags());

        // ── 标签点赞统计（1 次往返） ──
        List<Object[]> likeRows = tagInteractionRepository.countLikesByVenueGroupByTag(venueId);
        Map<String, Long> likeCountMap = new HashMap<>();
        for (Object[] row : likeRows) {
            likeCountMap.put((String) row[0], (Long) row[1]);
        }

        // ── 维度评分统计：全量 + 30天 + 7天 合并为 1 次往返 ──
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> multiWindowRows = tagInteractionRepository.aggregateScoresMultiWindow(
                venueId, now.minusDays(30), now.minusDays(7));

        // tag → [avgAll, countAll, avg30d, count30d, avg7d, count7d]
        Map<String, double[]> multiWindowMap = new HashMap<>();
        for (Object[] row : multiWindowRows) {
            String dimTag = (String) row[0];
            if (RatingDimensions.isValid(dimTag)) {
                double avgAll = row[1] != null ? ((Number) row[1]).doubleValue() : 0;
                long countAll = ((Number) row[2]).longValue();
                double avg30d = row[3] != null ? ((Number) row[3]).doubleValue() : 0;
                long count30d = row[4] != null ? ((Number) row[4]).longValue() : 0;
                double avg7d = row[5] != null ? ((Number) row[5]).doubleValue() : 0;
                long count7d = row[6] != null ? ((Number) row[6]).longValue() : 0;
                multiWindowMap.put(dimTag, new double[]{avgAll, countAll, avg30d, count30d, avg7d, count7d});
            }
        }

        // ── 用户交互状态：点赞 + 评分 合并为 1 次往返 ──
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
        List<TagLikeStats> tagLikes = venueTags.stream()
                .map(tag -> new TagLikeStats(
                        tag,
                        likeCountMap.getOrDefault(tag, 0L),
                        userLikedTags.contains(tag)))
                .toList();

        // ── 组装维度评分（无数据时 count=0, avgScore=null） ──
        List<DimensionScoreStats> dimensionScores = new ArrayList<>();
        for (String dim : RatingDimensions.ALL) {
            double[] mw = multiWindowMap.get(dim);

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

    // ─── 内部工具 ───────────────────────────────────────────────────────

    private Venue assertVenueExists(Long venueId) {
        return venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
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
