package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
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
     */
    @Cacheable(value = CacheConfig.CACHE_HOT_VENUE_IDS, sync = true)
    @Transactional(readOnly = true)
    public Set<Long> getHotVenueIds() {
        return new HashSet<>(venueRepository.findHotVenueIds());
    }
}
