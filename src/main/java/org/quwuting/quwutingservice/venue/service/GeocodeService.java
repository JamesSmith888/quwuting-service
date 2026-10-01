package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.exception.ExternalServiceUnavailableException;
import org.quwuting.quwutingservice.geo.tencent.TencentLbsClient;
import org.quwuting.quwutingservice.venue.change.VenueChangePublisher;
import org.quwuting.quwutingservice.venue.change.VenueFactChange;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 地理编码服务（2026-08-11 批量补齐坐标；2026-08-14 逆地理；2026-10-01 外部依赖治理）。
 * <p>
 * 数据源：腾讯位置服务 WebService（输出 gcj02，与前端全链路坐标约定一致——wx.chooseLocation
 * 采集 / wx.openLocation 展示均为 gcj02，见 quwuting/miniprogram/utils/geo.ts）。
 * 网络调用、状态码语义与配额熔断统一在 {@link TencentLbsClient}；本类只负责业务编排。
 * key 只放后端配置（app.geocode.key），不落前端。
 */
@Slf4j
@Service
public class GeocodeService {

    /** 中国境内 gcj02 坐标粗校验区间 */
    private static final double LAT_MIN = 18.0, LAT_MAX = 54.0, LNG_MIN = 73.0, LNG_MAX = 135.0;

    /**
     * 逆地理缓存网格：坐标保留 2 位小数（≈1.1km）。答案是「城市」，城市边界不会因此移动；
     * 代价是边界两侧约 1km 带内可能拿到相邻城市（用户可手动改），换来同一片区域（家/常去舞厅）
     * 的重复定位零外呼——这条接口曾把日配额打满（status=121），根因之一就是每次定位都真实外呼。
     */
    private static final String REVERSE_CACHE_KEY_FORMAT = "%.2f,%.2f";
    /** 城市归属几乎不变：长 TTL + 容量上限即可（单条约百字节） */
    private static final Duration REVERSE_CACHE_TTL = Duration.ofDays(30);
    private static final long REVERSE_CACHE_MAX_SIZE = 20_000;

    /** 批量补齐的节流间隔：个人版约 5QPS */
    private static final long BACKFILL_INTERVAL_MILLIS = 250;

    private final VenueRepository venueRepository;
    private final TencentLbsClient tencentLbsClient;
    private final VenueChangePublisher venueChangePublisher;
    private final Cache<String, String> reverseCityCache = Caffeine.newBuilder()
            .maximumSize(REVERSE_CACHE_MAX_SIZE)
            .expireAfterWrite(REVERSE_CACHE_TTL)
            .build();

    public GeocodeService(VenueRepository venueRepository, TencentLbsClient tencentLbsClient,
                          VenueChangePublisher venueChangePublisher) {
        this.venueRepository = venueRepository;
        this.tencentLbsClient = tencentLbsClient;
        this.venueChangePublisher = venueChangePublisher;
    }

    /** 批量补全结果报告（Controller 直接下发前端展示） */
    public record GeocodeReport(int total, int updated, int failed, int skipped,
                                List<String> failures) {
        public GeocodeReport {
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    /** 缺坐标待补数量（管理端按钮展示 / 首页红点备用）。 */
    public long countMissing() {
        return venueRepository.countMissingCoordinates();
    }

    /**
     * 批量补全缺坐标场所。
     * <p>
     * 串行调用 + 节流；每个成功项立即保存（save 各自提交，<b>不挂外层事务</b>——外呼期间不持有
     * 连接，见 13-code-standards「事务边界」），失败项记入报告不影响其他项。
     * 外部依赖不可用（配额耗尽 / 配置错误）⇒ 立即停止剩余条目：继续循环只会把同一个失败重复
     * 几百遍。幂等：已补齐场所不在查询结果内，可反复重试。
     */
    public GeocodeReport backfillAll() {
        if (!tencentLbsClient.isConfigured()) {
            throw new BusinessException(1004, "未配置地理编码 key（app.geocode.key / QQMAP_KEY）");
        }
        List<Venue> missing = venueRepository.findMissingCoordinates();
        int updated = 0, failed = 0, skipped = 0;
        List<String> failures = new ArrayList<>();
        List<Long> updatedIds = new ArrayList<>();

        for (Venue venue : missing) {
            String address = buildFullAddress(venue);
            if (address.isBlank()) {
                skipped++;
                continue;
            }
            try {
                double[] latLng = tencentLbsClient.geocode(address);
                if (!isValidCnCoord(latLng[0], latLng[1])) {
                    failed++;
                    failures.add("id=" + venue.getId() + " " + venue.getName() + "：坐标越界 ("
                            + latLng[0] + "," + latLng[1] + ")");
                    continue;
                }
                venue.setLatitude(latLng[0]);
                venue.setLongitude(latLng[1]);
                venueRepository.save(venue);
                updatedIds.add(venue.getId());
                updated++;
                Thread.sleep(BACKFILL_INTERVAL_MILLIS);
            } catch (ExternalServiceUnavailableException e) {
                failures.add("地理编码服务不可用，已停止（" + e.getMessage() + "）");
                failed += missing.size() - updated - skipped - failed;
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (BusinessException e) {
                failed++;
                failures.add("id=" + venue.getId() + " " + venue.getName() + "：" + e.getMessage());
            } catch (RuntimeException e) {
                // 单条落库等失败：记入报告、继续后续条目（逐条独立提交的意义所在）
                log.warn("[geocode] venue {} ({}) failed: {}", venue.getId(), venue.getName(), e.getMessage());
                failed++;
                failures.add("id=" + venue.getId() + " " + venue.getName() + "：" + e.getMessage());
            }
        }
        // 逐条已独立提交 ⇒ 监听器立即执行；坐标影响「附近」半径筛选与距离展示
        venueChangePublisher.publish(VenueFactChange.LOCATION, updatedIds);
        log.info("[geocode] backfill done: total={} updated={} failed={} skipped={}",
                missing.size(), updated, failed, skipped);
        return new GeocodeReport(missing.size(), updated, failed, skipped, failures);
    }

    /**
     * 逆地理编码：坐标 → 城市名（用户端「默认定位当前城市」）。
     * <p>
     * 失败语义：未配置 key → 1004；坐标越界 → 1001；上游不可用（配额/限流/网络）→
     * {@link ExternalServiceUnavailableException}（HTTP 503 + Retry-After，前端按等待时长决定
     * 是否重试，见 12-api-conventions「重试契约」）；上游返回无城市 → 1001。
     */
    public String reverseGeocode(double lat, double lng) {
        if (!isValidCnCoord(lat, lng)) {
            throw new BusinessException(1001, "坐标超出中国境内范围");
        }
        String cacheKey = String.format(Locale.ROOT, REVERSE_CACHE_KEY_FORMAT, lat, lng);
        String cached = reverseCityCache.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        String city = tencentLbsClient.reverseCity(lat, lng);
        if (city == null) {
            throw new BusinessException(1001, "该位置无法识别所在城市");
        }
        reverseCityCache.put(cacheKey, city);
        return city;
    }

    /** 拼接完整地址：优先用 address（可能已含省市区），否则 city+district+address。 */
    private String buildFullAddress(Venue venue) {
        String city = venue.getCity() == null ? "" : venue.getCity().trim();
        String address = venue.getAddress() == null ? "" : venue.getAddress().trim();
        if (address.isEmpty()) {
            return "";
        }
        // address 可能已含省市（如 "江苏省南通市崇川区钟秀中路98号"），避免重复拼接
        if (!city.isEmpty() && address.contains(city)) {
            return address;
        }
        String district = venue.getDistrict() == null ? "" : venue.getDistrict().trim();
        return (city + district + address).trim();
    }

    /** 中国境内经纬度粗校验（gcj02）。 */
    private boolean isValidCnCoord(double lat, double lng) {
        return lat >= LAT_MIN && lat <= LAT_MAX && lng >= LNG_MIN && lng <= LNG_MAX;
    }
}
