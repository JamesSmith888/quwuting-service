package org.quwuting.quwutingservice.venue.change;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门店事实变更事件的发布与 Spring 托管缓存失效（2026-10-01，零依赖单测）。
 */
class VenueChangeEventTest {

    @Test
    void publisherEmitsOneEventPerBatchAndSkipsEmptyOrNullIds() {
        List<Object> events = new ArrayList<>();
        ApplicationEventPublisher capture = events::add;
        VenueChangePublisher publisher = new VenueChangePublisher(capture);

        publisher.publish(VenueFactChange.STATUS, List.of(3L, 1L, 3L));
        publisher.publish(VenueFactChange.ALIAS, List.of());
        publisher.publish(VenueFactChange.PHOTO, (Long) null);
        publisher.publish(VenueFactChange.CLAIM, java.util.Arrays.asList(null, null));

        assertEquals(1, events.size(), "空集合 / null id 不得产生事件；一批只发一次");
        VenueFactsChangedEvent event = (VenueFactsChangedEvent) events.get(0);
        assertEquals(Set.of(1L, 3L), event.venueIds());
        assertEquals(Set.of(VenueFactChange.STATUS), event.changes());
    }

    @Test
    void sharedInvalidatorEvictsChangedVenuesAndClearsGlobalCaches() {
        ConcurrentMapCacheManager manager = new ConcurrentMapCacheManager(
                CacheConfig.CACHE_VENUE, CacheConfig.CACHE_HOT_VENUE_IDS, CacheConfig.CACHE_CITY_STATS);
        manager.getCache(CacheConfig.CACHE_VENUE).put(1L, "venue-1");
        manager.getCache(CacheConfig.CACHE_VENUE).put(2L, "venue-2");
        manager.getCache(CacheConfig.CACHE_HOT_VENUE_IDS).put("k", Set.of(1L));
        manager.getCache(CacheConfig.CACHE_CITY_STATS).put("k", List.of("南通市"));

        new VenueSharedCacheInvalidator(manager).onVenueFactsChanged(
                new VenueFactsChangedEvent(Set.of(1L), Set.of(VenueFactChange.STATUS)));

        assertNull(manager.getCache(CacheConfig.CACHE_VENUE).get(1L), "变更门店的实体缓存必须逐出");
        assertNotNull(manager.getCache(CacheConfig.CACHE_VENUE).get(2L), "未变更门店的实体缓存保留");
        assertNull(manager.getCache(CacheConfig.CACHE_HOT_VENUE_IDS).get("k"), "热门集合是全局维度，整体失效");
        assertNull(manager.getCache(CacheConfig.CACHE_CITY_STATS).get("k"), "城市统计是全局维度，整体失效");
    }

    @Test
    void eventIsImmutableSnapshot() {
        java.util.Set<Long> ids = new java.util.HashSet<>(Set.of(1L));
        VenueFactsChangedEvent event = new VenueFactsChangedEvent(ids, Set.of(VenueFactChange.PROFILE));
        ids.add(2L);
        assertEquals(Set.of(1L), event.venueIds(), "事件持有快照，发布后调用方继续改集合不得影响监听器");
        assertTrue(event.changes().contains(VenueFactChange.PROFILE));
    }
}
