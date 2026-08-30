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
 * 本 CacheManager 只托管<b>实体 / 集合类</b>缓存（场所实体、热门 ID 集合）。
 * 聚合统计类缓存（venueHeat / tagStats）<b>不在这里</b>——它们是多查询聚合结果，
 * 需要 refresh-ahead（预刷新）语义，已下沉为属主服务内嵌的 Caffeine {@code LoadingCache}：
 * <ul>
 *   <li>{@link org.quwuting.quwutingservice.venue.service.VenueHeatService}（venueHeat）</li>
 *   <li>{@link org.quwuting.quwutingservice.taginteraction.service.TagAggregateStatsService}（tagStats）</li>
 * </ul>
 *
 * <h3>为什么聚合缓存不走 Spring CacheManager</h3>
 * refresh-ahead 依赖 Caffeine 的 {@code refreshAfterWrite}，而该选项<b>要求缓存是
 * LoadingCache</b>（构建时提供 loader，否则抛 {@code refreshAfterWrite requires a
 * LoadingCache}）。Spring 的 {@link CaffeineCacheManager} 注册的是手动缓存（无 loader），
 * 无法表达"每个缓存各自的加载逻辑"。因此聚合缓存采用与 AuthInterceptor 用户缓存相同的
 * "服务内嵌原生 Caffeine"模式，在其属主服务中声明真正的 loader（即聚合计算方法本身），
 * 获得四项语义：
 * <ol>
 *   <li><b>预刷新</b>：条目写入 60s 后，下一次访问立即返回旧值并<b>异步</b>重载——
 *       活跃场所的用户不再周期性吃到同步冷加载（早期 expireAfterWrite(60s) 硬过期导致
 *       每 60 秒出现一个 2s+ 慢请求，这是本轮要消除的核心症状）；</li>
 *   <li><b>单飞</b>：同 key 并发回源只加载一次，详情页并发请求天然去重；</li>
 *   <li><b>刷新失败保留旧值</b>：瞬态 DB 抖动降级为数据滞后而非请求失败；</li>
 *   <li><b>硬过期兜底</b>：30 分钟无访问才驱逐。</li>
 * </ol>
 * 数据新鲜度由写路径显式调用属主服务的 {@code invalidate(venueId)} 保证
 * （替代早期跨 Bean {@code @CacheEvict}），refresh/expire 仅作兜底。
 *
 * <h3>本 CacheManager 内缓存的 sync = true 约定</h3>
 * venueCache / hotVenueIds 的 {@code @Cacheable} 声明 {@code sync = true}：
 * 同 key 并发回源时 Caffeine 单飞，消除 thundering herd。此处不使用 refreshAfterWrite
 * （单查询成本低，且 Spring CacheManager 无 loader 无法启用），到期后单飞冷加载即可。
 */
@Configuration
@EnableCaching
public class CacheConfig {

    /** 场所实体缓存名称（详情页多请求共享） */
    public static final String CACHE_VENUE = "venueCache";

    /** 热门场所 ID 集合缓存名称（列表页 isHot 标记用） */
    public static final String CACHE_HOT_VENUE_IDS = "hotVenueIds";

    /** 有场所的城市列表缓存名称（首页热门城市，5min TTL——门店新增/编辑才变化，写路径逐出） */
    public static final String CACHE_CITY_STATS = "cityStats";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        // 场所实体：60s TTL，maxSize 500（详情页多并发请求共享，sync 单飞）
        manager.registerCustomCache(CACHE_VENUE, Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(60, TimeUnit.SECONDS)
                .build());
        // 热门场所 ID：5min TTL，maxSize 1（全局唯一集合），写路径 allEntries 逐出
        manager.registerCustomCache(CACHE_HOT_VENUE_IDS, Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build());
        // 有场所的城市列表：5min TTL，maxSize 1（全局唯一集合），门店新增/编辑逐出
        manager.registerCustomCache(CACHE_CITY_STATS, Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build());
        return manager;
    }
}
