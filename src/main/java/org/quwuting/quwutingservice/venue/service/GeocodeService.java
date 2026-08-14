package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.config.GeocodeProperties;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 批量地理编码服务（2026-08-11 新增，管理端「一键补齐坐标」）。
 * <p>
 * 数据源：腾讯位置服务 WebService 地理编码 API（输出 gcj02，与前端全链路坐标约定
 * 一致——wx.chooseLocation 采集 / wx.openLocation 展示均为 gcj02，见
 * quwuting/miniprogram/utils/geo.ts）。key 只放后端配置（app.geocode.key），不落前端。
 * <p>
 * 幂等：只处理 latitude/longitude 为空的场所，重复执行不重复处理已补齐项；
 * 失败项（网络错误 / API 报错 / 坐标越界）跳过并计入报告，可反复重试。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GeocodeService {

    /** 腾讯位置服务地理编码 WebService 端点 */
    private static final String GEOCODER_URL = "https://apis.map.qq.com/ws/geocoder/v1/";

    /** 中国境内 gcj02 坐标粗校验区间 */
    private static final double LAT_MIN = 18.0, LAT_MAX = 54.0, LNG_MIN = 73.0, LNG_MAX = 135.0;

    private final VenueRepository venueRepository;
    private final GeocodeProperties geocodeProperties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

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

    /** 批量补全缺坐标场所。
     * <p>
     * 串行调用（个人版配额约 5QPS，逐条间隔 250ms 避免触发限流）；每个成功项
     * 立即保存（save 各自提交，不持有跨批大事务——连接池仅 5 连接，且 Supabase
     * 抖动是已知外部条件，见 AGENTS.md「连接池与数据库抖动韧性」），失败项记入
     * 报告不影响其他项。幂等：已补齐场所不在查询结果内，可反复重试。
     */
    public GeocodeReport backfillAll() {
        String key = geocodeProperties.key();
        if (!geocodeProperties.isConfigured()) {
            throw new org.quwuting.quwutingservice.exception.BusinessException(
                    1004, "未配置地理编码 key（app.geocode.key / QQMAP_KEY）");
        }

        List<Venue> missing = venueRepository.findMissingCoordinates();
        int updated = 0, failed = 0, skipped = 0;
        List<String> failures = new ArrayList<>();

        for (Venue venue : missing) {
            String address = buildFullAddress(venue);
            if (address == null || address.isBlank()) {
                skipped++;
                continue;
            }
            try {
                double[] latLng = geocode(address, key);
                if (!isValidCnCoord(latLng[0], latLng[1])) {
                    failed++;
                    failures.add("id=" + venue.getId() + " " + venue.getName() + "：坐标越界 ("
                            + latLng[0] + "," + latLng[1] + ")");
                    continue;
                }
                venue.setLatitude(latLng[0]);
                venue.setLongitude(latLng[1]);
                venueRepository.save(venue);
                updated++;
                Thread.sleep(250); // 限速：个人版约 5QPS
            } catch (Exception e) {
                log.warn("[geocode] venue {} ({}) failed: {}", venue.getId(), venue.getName(), e.getMessage());
                failed++;
                failures.add("id=" + venue.getId() + " " + venue.getName() + "：" + e.getMessage());
            }
        }
        log.info("[geocode] backfill done: total={} updated={} failed={} skipped={}",
                missing.size(), updated, failed, skipped);
        return new GeocodeReport(missing.size(), updated, failed, skipped, failures);
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

    /** 调用腾讯位置服务地理编码，返回 [lat, lng]。 */
    private double[] geocode(String address, String key) throws Exception {
        String url = GEOCODER_URL + "?address="
                + URLEncoder.encode(address, StandardCharsets.UTF_8)
                + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(8))
                .header("User-Agent", "quwuting-service/1.0")
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(response.body());
        int status = root.path("status").asInt(-1);
        if (status != 0) {
            throw new IllegalStateException("geocode status=" + status + " msg=" + root.path("message").asText(""));
        }
        JsonNode location = root.path("result").path("location");
        if (location.isMissingNode() || location.path("lat").isMissingNode() || location.path("lng").isMissingNode()) {
            throw new IllegalStateException("geocode result missing location");
        }
        return new double[]{location.path("lat").asDouble(), location.path("lng").asDouble()};
    }

    /**
     * 逆地理编码：坐标 → 城市名（2026-08-14 新增，舞伴表单"默认定位当前城市"）。
     * <p>
     * 复用腾讯位置服务 WebService 逆地理编码 API（同 key / HttpClient）：
     * 返回 result.address_component.city（标准行政区划名，如"深圳市"——与
     * picker mode="region" 词表、列表筛选共用词表，精确匹配）。
     * <p>
     * 失败语义（调用方前端静默降级——拿不到城市留空让用户手动选择）：
     * 未配置 key → 1004；坐标越界（境外/非法值）→ 1001；腾讯 API 失败 → IllegalStateException
     * （Controller 层转 5000 未预期错误）。城市名是粗粒度低敏信息，公开接口不设频控
     * （与 GET /venues/cities 同策略）。
     *
     * @return 城市名（如"深圳市"）
     */
    public String reverseGeocode(double lat, double lng) {
        String key = geocodeProperties.key();
        if (!geocodeProperties.isConfigured()) {
            throw new org.quwuting.quwutingservice.exception.BusinessException(
                    1004, "未配置地理编码 key（app.geocode.key / QQMAP_KEY）");
        }
        if (!isValidCnCoord(lat, lng)) {
            throw new org.quwuting.quwutingservice.exception.BusinessException(1001, "坐标超出中国境内范围");
        }
        try {
            String url = GEOCODER_URL + "?location=" + lat + "," + lng
                    + "&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "quwuting-service/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode root = objectMapper.readTree(response.body());
            int status = root.path("status").asInt(-1);
            if (status != 0) {
                throw new IllegalStateException("reverse geocode status=" + status
                        + " msg=" + root.path("message").asText(""));
            }
            JsonNode component = root.path("result").path("address_component");
            String city = component.path("city").asText(null);
            if (city == null || city.isBlank()) {
                throw new IllegalStateException("reverse geocode result missing city");
            }
            return city;
        } catch (org.quwuting.quwutingservice.exception.BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("reverse geocode failed: " + e.getMessage(), e);
        }
    }

    /** 中国境内经纬度粗校验（gcj02）。 */
    private boolean isValidCnCoord(double lat, double lng) {
        return lat >= LAT_MIN && lat <= LAT_MAX && lng >= LNG_MIN && lng <= LNG_MAX;
    }
}
