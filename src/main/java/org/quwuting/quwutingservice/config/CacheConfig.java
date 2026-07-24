package org.quwuting.quwutingservice.config;

import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

/**
 * 本地缓存配置（Caffeine）。
 * <p>
 * 用于聚合统计类接口（热度、标签统计），这些数据变化频率低、计算成本高（多次 DB 聚合），
 * 短 TTL 缓存可显著减少远程数据库往返次数，且对用户感知无影响（60 秒内的数据偏差可接受）。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 热度缓存名称 */
    public static final String CACHE_VENUE_HEAT = "venueHeat";

    /** 标签统计缓存名称 */
    public static final String CACHE_TAG_STATS = "tagStats";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(60, TimeUnit.SECONDS));
        return manager;
    }
}
