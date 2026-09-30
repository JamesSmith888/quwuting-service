package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.repository.RegionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 行政区划名录供给方（2026-09-30，MySQL V35；前端权威文档 =
 * quwuting/docs/agents/35-venue-search.md「零结果空态：语义分诊」）。
 * <p>
 * <b>职责边界：只回答「有哪些地名」，不回答「这个词的意图是什么」。</b>
 * 判定留在消费方（前端）本地做（精确等值 + 去后缀等值），本服务只下发全集——
 * 判据一旦有两处（服务端判一次、前端判一次）就会漂移，且给搜索路径加一次往返。
 * <p>
 * <b>失败语义（本轮最要紧的一条）</b>：名录拿不到时返回<b>空列表</b>，而不是报错或猜。
 * 消费方拿到空列表 ⇒ 回退到「形态判据」（只认带后缀的词）= <b>本次改动前的行为</b>。
 * 即降级方向 = 退回旧行为，<b>不会把「我们不知道」说成「我们没有」</b>——
 * 若这里改成"返回几个大城市凑数"，用户搜一个我们没收录的小城就会被断言成"不是城市"。
 * <p>
 * <b>缓存</b>：静态基础数据，60min TTL + 单飞（与 {@code CityCentroidService} 同族语义；
 * 名录只在行政区划调整时变化，靠 TTL 兜底即可，无需写路径失效）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RegionService {

    /** 名录缓存 TTL（分钟）：静态基础数据，取比城市质心（10min）更长的 60min */
    private static final long REGION_TTL_MINUTES = 60;

    private final RegionRepository regionRepository;

    private LoadingCache<String, List<String>> regionCache;

    @PostConstruct
    void initCache() {
        regionCache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(REGION_TTL_MINUTES, TimeUnit.MINUTES)
                .build(key -> loadRegionNames());
    }

    /**
     * 标准行政区划名全集（规范名，含后缀，如「合肥市」「内蒙古自治区」）。
     *
     * @return 名录；<b>取不到时为空列表</b>（消费方据此回退形态判据，见类注释）
     */
    public List<String> regionNames() {
        try {
            return regionCache.get("regions");
        } catch (RuntimeException e) {
            // 缓存 loader 抛异常时 Caffeine 会原样抛出 —— 降级为空列表，绝不让接口 5xx
            // （搜索零结果的分诊是加分项，拿不到名录就按旧形态判，不打扰用户）
            log.warn("行政区划名录不可用，本次按形态判据降级：{}", e.getMessage());
            return new ArrayList<>();
        }
    }

    /** 缓存 loader（经 regionCache 调用，勿直接调用） */
    private List<String> loadRegionNames() {
        List<String> names = new ArrayList<>();
        regionRepository.findAllByOrderByIdAsc().forEach(r -> {
            if (r.getName() != null && !r.getName().isBlank()) {
                names.add(r.getName());
            }
        });
        log.debug("行政区划名录：{} 条", names.size());
        return names;
    }
}
