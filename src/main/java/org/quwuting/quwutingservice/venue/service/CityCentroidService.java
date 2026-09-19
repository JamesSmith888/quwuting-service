package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 城市质心与「城市级门店可见性范围」供给方（2026-09-19，歌友会可见性改为「该城市附近 300km」）。
 * <p>
 * <b>要解决的问题</b>：城市级地址门店（当前仅歌友会，{@code VenueType.SONG_CLUB}）按设计
 * <b>不落精确地址与坐标</b>（写路径主动清空，见 {@code VenueService.applyCityOnlyAddressPolicy}
 * ——「存了再藏」永远不如「根本不存」）。但「不存坐标」的代价是：{@code RADIUS_PREDICATE} 里
 * 基于 {@code DISTANCE_KM} 的 300km 可达圈对无坐标门店恒为假（距离表达式为 NULL），整批歌友会
 * 被静默过滤。2026-09-13 的临时解法是「城市级类型无条件放行」，结果是<b>无锡的歌友会出现于
 * 全国任意用户的默认列表</b>（实测该店热度登顶后落到全国首位）。
 * <p>
 * <b>本次解法</b>：城市是这类门店<b>唯一成立的精度</b>——用「门店所在城市是否在参考点 300km 内」
 * 替代「门店坐标是否在半径内」。参考点 = 请求坐标（有定位）或用户显式选择的城市（无定位）；
 * 城市坐标从<b>已有数据派生</b>（同城已有坐标门店的均值，见
 * {@link VenueRepository#findCityCentroids}），不引入外部地理数据集、不改表结构、
 * 不往歌友会身上塞坐标。
 * <p>
 * <b>2026-09-19 二次修正：无参考点 ⇒ 不限制（而不是"一律隐藏"）</b>。
 * 初版把「既无坐标也无城市」处理成「只剩哨兵 ⇒ 歌友会不进列表」，理由写的是"无法证明可达
 * 就不显示，优于全国显示"。上线即暴露反例（用户实测：<b>切到「站内热度」歌友会整类消失</b>）：
 * <b>站内热度 / 最新收录是「全网探索排序」，前端刻意不传坐标也不传半径</b>
 * （{@code scopeFree}，见 {@code index.ts} 的 effectiveLocation 注释——为了让这类公共查询命中
 * 后端无坐标视图缓存）——于是这两类<b>正常功能态</b>下参考点恒为 null，歌友会被整体隐藏。
 * 而同一请求里普通门店是按「全国」展示的：<b>同一个列表里只藏一个品类 = 标签撒谎</b>
 * （列表说"全部城市"却悄悄少一类店），与「标签恒等于结果」的既有纪律冲突
 * （2026-09-01 v2 可达圈模型的核心约定）。
 * <p>
 * 修正后的判据（{@link #cityScope}）——<b>可见性只在"有参照物"时才收缩</b>：
 * <ol>
 *   <li>有请求坐标 ⇒ <b>收缩</b>：取距其 300km 内的城市（默认「附近 300km」场景，即用户指令的主体）；</li>
 *   <li>无坐标但用户<b>显式选了城市</b> ⇒ <b>收缩</b>：该城市无条件入集合（"就在你选的城市里"
 *       必须可见），并叠加以该城市质心为参考点的 300km 邻城；该城市若无带坐标门店（新导入城市
 *       未补坐标）则收缩到"仅该城市"——不放大为全国，也不吞掉整类门店；</li>
 *   <li>两者皆无（未授权定位 / <b>全网探索排序</b> / 附近空降级） ⇒ <b>不收缩</b>：
 *       普通门店此时本就是全国视角，城市级门店同口径可见——一致性优先。</li>
 * </ol>
 * <p>
 * <b>恒非空契约</b>：{@code cities} 恒含哨兵 {@link #SENTINEL_CITY}（空串——城市名不可能为空串），
 * 因为消费方把它直接拼进 {@code v.city IN :nearbyCities}，空集合会渲染成 {@code IN ()}
 * 造成 SQL 语法错误。调用方无需判空。
 * <p>
 * <b>缓存</b>：城市质心 10min TTL + 单飞（门店坐标是低频变更的存量事实，且每次列表请求都要用）。
 * 本服务不做写路径失效——沿用 {@code hotVenueIds}（5min）同族语义：靠 TTL 兜底，门店新增/编辑
 * 导致的质心漂移在 10min 内收敛，对"跨不跨城"的判断无实质影响。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CityCentroidService {

    /**
     * 邻近城市集合的哨兵（空串）。保证 {@code v.city IN :nearbyCities} 恒非空——见类注释
     * 「恒非空契约」。城市名来自行政区划词表，不可能为空串。
     */
    private static final String SENTINEL_CITY = "";

    /** 城市质心缓存 TTL（沿用 hotVenueIds 5min 同族语义，取更长的 10min：坐标变化更慢） */
    private static final long CENTROID_TTL_MINUTES = 10;

    /** 地球平均半径（km，与 SQL 侧 {@code DISTANCE_KM} 的 6371.0 保持同值） */
    private static final double EARTH_RADIUS_KM = 6371.0;

    /**
     * 城市级门店可见性半径（km）。
     * <p>
     * <b>与前端 {@code DEFAULT_RADIUS_KM = 300} 同值、语义同源</b>：列表默认口径就是
     * 「可达圈 = 附近 300km」，歌友会是同一可达圈的成员，只是判据从「门店坐标」降级为
     * 「门店所在城市」。两者<b>同值是有意的</b>（用户 2026-09-19 拍板「歌友会改为该城市
     * 附近 300km」），但<b>不是一个常量</b>：前端那个是"用户可调的范围筛选"，本值是
     * "城市级门店的可见性边界"，含义不同故不共享定义——将来改其中一个必须回头确认另一个。
     */
    public static final double CITY_ONLY_NEARBY_RADIUS_KM = 300.0;

    private final VenueRepository venueRepository;

    private LoadingCache<String, Map<String, double[]>> centroidCache;

    @PostConstruct
    void initCache() {
        centroidCache = Caffeine.newBuilder()
                .maximumSize(1)
                .expireAfterWrite(CENTROID_TTL_MINUTES, TimeUnit.MINUTES)
                .build(key -> loadCityCentroids());
    }

    /**
     * 城市级门店的可见性范围（见类注释三个判据）。
     *
     * @param limited 是否收缩：true = 有空间参考点，只允许 {@code cities} 内的城市；
     *                false = 无任何参考点，<b>不限制</b>（消费方据此短路谓词，
     *                城市级门店与普通门店同口径可见）
     * @param cities  允许的城市集合（恒含哨兵空串；{@code limited=false} 时其内容不被消费，
     *                但仍须传递以满足 SQL 的 {@code IN} 形态）
     */
    public record CityScope(boolean limited, Set<String> cities) {}

    /**
     * 计算城市级门店的可见性范围。参考点优先级：请求坐标 &gt; 用户显式选择的城市的质心。
     *
     * @param latitude  请求坐标纬度，可空（无定位 / 全网探索排序刻意不传坐标）
     * @param longitude 请求坐标经度，同上
     * @param city      用户显式选择的城市，可空（未选城市）
     */
    public CityScope cityScope(Double latitude, Double longitude, String city) {
        boolean hasCity = city != null && !city.isBlank();
        Set<String> cities = new LinkedHashSet<>();
        cities.add(SENTINEL_CITY);
        // 显式选择的城市恒入集合（判据 ② 的前半）
        if (hasCity) {
            cities.add(city.trim());
        }
        boolean hasCoords = latitude != null && longitude != null;
        if (!hasCoords && !hasCity) {
            // 判据 ③：无任何空间参考点 ⇒ 不收缩（与同请求里普通门店的"全国"口径一致）
            return new CityScope(false, cities);
        }

        Double refLat = hasCoords ? latitude : null;
        Double refLng = hasCoords ? longitude : null;
        if (refLat == null) {
            Map<String, double[]> centroids = centroidCache.get("centroids");
            double[] centroid = centroids.get(city.trim());
            if (centroid == null) {
                // 有城市但该城市尚无带坐标门店（新导入城市未补坐标）：只有"所选城市"这一条
                // 可判定的事实——收缩到该城市（既不放大为全国，也不吞掉整类门店）
                return new CityScope(true, cities);
            }
            refLat = centroid[0];
            refLng = centroid[1];
        }
        for (Map.Entry<String, double[]> entry : centroidCache.get("centroids").entrySet()) {
            double[] centroid = entry.getValue();
            if (distanceKm(refLat, refLng, centroid[0], centroid[1]) <= CITY_ONLY_NEARBY_RADIUS_KM) {
                cities.add(entry.getKey());
            }
        }
        return new CityScope(true, cities);
    }

    /** 缓存 loader：city → [lat, lng]；异常不致命（退化为空表 = 只有"显式城市"可判定）。 */
    private Map<String, double[]> loadCityCentroids() {
        Map<String, double[]> map = new HashMap<>();
        try {
            List<VenueRepository.CityCentroid> rows = venueRepository.findCityCentroids();
            for (VenueRepository.CityCentroid row : rows) {
                if (row.getCity() == null || row.getLatitude() == null || row.getLongitude() == null) continue;
                map.put(row.getCity(), new double[]{row.getLatitude(), row.getLongitude()});
            }
            log.debug("城市质心：{} 个城市（具备带坐标门店者；其余城市靠显式城市选择兜底）", map.size());
        } catch (RuntimeException e) {
            log.warn("城市质心查询失败，本次仅按显式城市判定歌友会可见性", e);
        }
        return map;
    }

    /**
     * Haversine 球面距离（km）——与 SQL 侧 {@code VenueRepository.DISTANCE_KM} 同一公式，
     * 常量取同值 6371.0。此处需在 Java 侧算（城市质心不在 SQL 中，无表可 JOIN）。
     */
    private static double distanceKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(a)));
    }
}
