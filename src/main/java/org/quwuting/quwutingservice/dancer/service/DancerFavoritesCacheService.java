package org.quwuting.quwutingservice.dancer.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.dto.response.DancerSummaryResponse;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * 舞伴收藏列表「用户级」聚合缓存（2026-08-30 性能根因修复，舞伴列表页收藏 Tab）。
 * <p>
 * <b>根因</b>：DancerService#listFavorites 原本每次请求顺序执行 ~10 次 DB 往返
 * （收藏行查询 + 7 次用户无关 enrichments + 个人态 3 次），且<b>完全无后端缓存</b>——
 * 收藏列表是「个性化数据」，被排除在 DancerListCacheService（用户无关）之外后，
 * 前端只能用 30s TTL 缓存把慢请求摊薄到每 30s 一次，实测 1.5~2.9s 慢加载。
 * <p>
 * <b>方案</b>：个性化数据≠不可缓存——按 userId 隔离的短 TTL 缓存（对齐前端
 * service 层 30s TTL 契约，见 quwuting/services/dancer.ts dancerFavoritesCache）：
 * <ul>
 *   <li>30s 绝对过期 + 500 用户上限（低流量小程序覆盖活跃用户足够）；</li>
 *   <li>缓存整份组装后的 {@link DancerSummaryResponse}（含个人态 chips 活跃态）——
 *       个人态对「同一用户」恒定，不跨用户泄漏（键 = userId 隔离），与前端 30s
 *       TTL 缓存完全同语义；</li>
 *   <li>新鲜度主保障 = 写路径 {@link #invalidate(Long)}（收藏 add·remove 后失效该
 *       用户缓存，返回列表必然重拉最新）——与 DancerService 的
 *       invalidateListCache 双失效约定同点调用；</li>
 *   <li>认可 toggle 不失效本缓存（与前端 dancerFavoritesCache 同契约：认可只改
 *       chips 活跃态，≤30s 陈旧可接受，卡片本地乐观更新已覆盖即时反馈）。</li>
 * </ul>
 * 效果：30s 窗口内重复进收藏 Tab，DB 往返从 ~10 次降到 0 次（纯内存命中）。
 */
@Service
@RequiredArgsConstructor
public class DancerFavoritesCacheService {

    /** 缓存 TTL：30s（对齐前端 dancerFavoritesCache 契约——语义一致，避免前后端缓存打架） */
    private static final long CACHE_TTL_SECONDS = 30;

    /** 容量上限：活跃用户数（低流量小程序，500 足够覆盖日活入口；FIFO 驱逐） */
    private static final int MAX_CACHE_SIZE = 500;

    /** userId → 收藏摘要列表（整份组装结果，键 = userId 天然隔离个人态） */
    private final Cache<Long, List<DancerSummaryResponse>> cache = Caffeine.newBuilder()
            .maximumSize(MAX_CACHE_SIZE)
            .expireAfterWrite(CACHE_TTL_SECONDS, TimeUnit.SECONDS)
            .build();

    /**
     * 读取收藏列表（命中 = 纯内存；miss = 执行 loader 组装并回填，Caffeine 单飞防击穿）。
     * loader 由 DancerService 注入（组装逻辑单一权威在 DancerService#buildSummaries，
     * 本服务不持有任何仓储依赖——避免循环依赖与组装逻辑分裂）。
     *
     * @param userId 当前用户（收藏列表天然按用户隔离）
     * @param loader 缓存 miss 时的组装函数（恒走 DancerService 组装链路）
     */
    public List<DancerSummaryResponse> get(Long userId, Function<Long, List<DancerSummaryResponse>> loader) {
        return cache.get(userId, loader);
    }

    /**
     * 写路径显式失效（唯一入口）：收藏 add·remove 后调用——该用户收藏集合变化，
     * 缓存内容不再准确，下次读取必然回源重算。其余写路径（认可/浏览等）不失效
     * （≤30s TTL 自然过期，与前端契约一致）。
     */
    public void invalidate(Long userId) {
        cache.invalidate(userId);
    }
}
