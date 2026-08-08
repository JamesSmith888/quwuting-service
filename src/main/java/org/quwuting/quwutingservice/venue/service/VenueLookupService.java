package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.config.VenueHotProperties;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.Set;

/**
 * 场所查找的缓存层（独立 Bean，避免 Spring AOP 自调用陷阱）。
 * <p>
 * 根因：详情页加载时前端并发发起 3 个 API 请求（详情/标签统计/热度），
 * 各自独立查询同一场所实体——跨洲 DB 往返 300-500ms × 3 = 900-1500ms 冗余开销。
 * 通过 60s TTL 缓存，第一个请求回源查库后，后续请求命中缓存（<1ms），
 * 消除详情页的重复场所查询。
 * <p>
 * 独立为单独 Bean 的原因：{@code @Cacheable} 基于动态代理实现，
 * 同一类内通过 {@code this} 自调用会绕开代理导致注解静默失效。
 * 被缓存的方法必须从另一个 Bean 上被调用——此约束与
 * {@link org.quwuting.quwutingservice.taginteraction.service.TagAggregateStatsService} 同模式。
 * <p>
 * <b>写路径不使用本缓存</b>：{@link VenueService#updateVenue} 和 {@link VenueService#createVenue}
 * 直接调用 {@link VenueRepository}，并通过 {@code @CacheEvict} 即时失效本缓存。
 */
@Service
@RequiredArgsConstructor
public class VenueLookupService {

    private final VenueRepository venueRepository;
    private final VenueHotProperties venueHotProperties;

    /**
     * 按 ID 查询场所（含缓存）。
     * 场所不存在时抛 {@link BusinessException}（1001），不缓存异常结果——
     * 后续场所创建后可正常命中缓存。
     * <p>
     * 缓存的 Venue 为 detached entity（脱离持久化上下文），仅用于读取字段，
     * 写操作（更新/保存）应直接使用 {@link VenueRepository}。
     */
    @Cacheable(value = CacheConfig.CACHE_VENUE, key = "#id", sync = true)
    @Transactional(readOnly = true)
    public Venue findById(Long id) {
        return venueRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
    }

    /**
     * 获取热门场所 ID 集合（含缓存）。
     * 底层为窗口函数全表查询，结果变化频率极低（收藏/动态增减才影响排序），
     * 5min TTL 可大幅减少列表接口的 DB 往返次数。
     * <p>
     * 排序口径为行为热度镜像公式（见 {@link VenueRepository#findHotVenueIds}），
     * 需要正向 Reaction code 列表（唯一事实源 = ReactionCode）。
     * <p>
     * 热门判定 = 城市内 top 20% <b>且</b> 行为热度（完整热度分扣除运营权重 sortWeight，
     * 即 SQL 内 {@code heat_score - sort_weight}）≥ {@code venue.hot.min-heat-score}
     * （绝对门槛，见 {@link VenueHotProperties}）——排除"小池塘里最不冷"的伪热门；
     * 门槛参数经本方法注入 SQL（配置唯一事实源，禁止在 SQL/调用方硬编码）。
     * sortWeight 仍参与城市内排名与列表排序（运营推广提升曝光），但不得伪造热门
     * 资格（2026-08-08 用户反馈根因修复：运营加权门店若行为热度不足门槛，不得标记
     * 热门——保证与详情页热度指数口径一致，见 AGENTS.md「热门场所标记」）。
     */
    @Cacheable(value = CacheConfig.CACHE_HOT_VENUE_IDS, sync = true)
    @Transactional(readOnly = true)
    public Set<Long> getHotVenueIds() {
        return new HashSet<>(venueRepository.findHotVenueIds(
                ReactionCode.positiveCodeNames(), venueHotProperties.minHeatScore()));
    }
}
