package org.quwuting.quwutingservice.venue.change;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Spring CacheManager 托管的门店相关缓存（{@link CacheConfig}）在门店事实变更后的失效
 * （2026-10-01）。
 * <ul>
 *   <li>{@link CacheConfig#CACHE_VENUE}：门店实体（逐店逐出）——详情/热度重算都经它取实体，
 *       不逐出就会用旧实体重算出旧状态（舞讯批量写库曾因此「列表已更新、详情仍旧」）；</li>
 *   <li>{@link CacheConfig#CACHE_HOT_VENUE_IDS}：热门门店集合（全局）；</li>
 *   <li>{@link CacheConfig#CACHE_CITY_STATS}：有门店的城市列表（全局）。</li>
 * </ul>
 * 属主内嵌的 Caffeine 缓存（详情公共部分、列表、分面、热度）由各属主自己订阅同一事件。
 */
@Component
@RequiredArgsConstructor
public class VenueSharedCacheInvalidator {

    private final CacheManager cacheManager;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onVenueFactsChanged(VenueFactsChangedEvent event) {
        Cache venueCache = cacheManager.getCache(CacheConfig.CACHE_VENUE);
        if (venueCache != null) {
            event.venueIds().forEach(venueCache::evict);
        }
        clear(CacheConfig.CACHE_HOT_VENUE_IDS);
        clear(CacheConfig.CACHE_CITY_STATS);
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
        }
    }
}
