package org.quwuting.quwutingservice.geo.tencent;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.tx.TransactionBoundaryGuard;
import org.quwuting.quwutingservice.config.GeocodeProperties;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.exception.ExternalServiceUnavailableException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 腾讯位置服务 WebService（geocoder/v1）唯一客户端（2026-10-01，自 GeocodeService 抽出）。
 *
 * <h2>为什么独立成客户端</h2>
 * 地理编码（地址→坐标，管理端批量补齐）与逆地理编码（坐标→城市，用户端定位）共用同一把
 * key 与同一份<b>日配额</b>。旧实现两处各自拼 URL、各自把非 0 状态码包成
 * {@code IllegalStateException}，配额耗尽（status=121）因此表现为「持续 HTTP 500」：
 * 每次请求都真实外呼一次（配额已尽仍在打）、每次打一条 ERROR 堆栈、客户端再把 500 重试一遍。
 *
 * <h2>状态码语义（腾讯位置服务公开约定）</h2>
 * <ul>
 *   <li>{@code 0}：成功；</li>
 *   <li>{@code 121}：key 日配额耗尽 ⇒ <b>熔断到次日 0 点</b>（配额按自然日重置），期间不再外呼，
 *       直接返回 {@link ExternalServiceUnavailableException}（Retry-After = 距 0 点秒数）；</li>
 *   <li>{@code 120}：每秒请求超限 ⇒ 短暂不可用（Retry-After 1s）；</li>
 *   <li>其余 {@code 1xx}：key / 签名 / 来源 / 功能未授权 = <b>配置错误</b> ⇒ 不可用并 ERROR 告警；</li>
 *   <li>{@code 3xx}：请求参数错误 ⇒ {@link BusinessException}(1001)，属调用方问题；</li>
 *   <li>其余：上游内部错误 ⇒ 短暂不可用。</li>
 * </ul>
 */
@Slf4j
@Component
public class TencentLbsClient {

    static final String DEPENDENCY = "腾讯位置服务";
    private static final String GEOCODER_URL = "https://apis.map.qq.com/ws/geocoder/v1/";
    private static final Duration TIMEOUT = Duration.ofSeconds(8);
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");

    private static final int STATUS_OK = 0;
    private static final int STATUS_RATE_LIMITED = 120;
    private static final int STATUS_DAILY_QUOTA_EXHAUSTED = 121;

    /** 短暂不可用（限流 / 上游错误 / 网络失败）的建议重试等待 */
    private static final long TRANSIENT_RETRY_AFTER_SECONDS = 1;
    /** 配置错误的熔断时长：避免每个请求都去撞一个必然失败的上游，同时给修复后的自愈留窗口 */
    private static final long MISCONFIGURED_RETRY_AFTER_SECONDS = 600;

    private static final String USER_MESSAGE = "定位服务暂时不可用，请手动选择城市";

    private final GeocodeProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final HttpClient httpClient;

    /** 熔断截止时刻（epoch ms；0 = 未熔断）。配额耗尽 / 配置错误时置位，到点自动解除。 */
    private final AtomicLong unavailableUntilMillis = new AtomicLong(0);

    @Autowired
    public TencentLbsClient(GeocodeProperties properties, ObjectMapper objectMapper) {
        this(properties, objectMapper, Clock.system(QUOTA_ZONE),
                HttpClient.newBuilder().connectTimeout(TIMEOUT).build());
    }

    /** 测试注入点：时钟（熔断到期判定）与 HTTP 传输 */
    TencentLbsClient(GeocodeProperties properties, ObjectMapper objectMapper, Clock clock, HttpClient httpClient) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.httpClient = httpClient;
    }

    public boolean isConfigured() {
        return properties.isConfigured();
    }

    /** 地址 → 坐标 [lat, lng]（gcj02） */
    public double[] geocode(String address) {
        JsonNode location = call("address=" + URLEncoder.encode(address, StandardCharsets.UTF_8), "geocode")
                .path("result").path("location");
        if (location.path("lat").isMissingNode() || location.path("lng").isMissingNode()) {
            throw new BusinessException(1001, "地址无法解析为坐标");
        }
        return new double[]{location.path("lat").asDouble(), location.path("lng").asDouble()};
    }

    /** 坐标 → 城市名（标准行政区划名，如「深圳市」）；无城市时返回 null */
    public String reverseCity(double lat, double lng) {
        String city = call("location=" + lat + "," + lng, "reverse")
                .path("result").path("address_component").path("city").asText(null);
        return city == null || city.isBlank() ? null : city;
    }

    private JsonNode call(String query, String operation) {
        if (!properties.isConfigured()) {
            throw new BusinessException(1004, "未配置地理编码 key（app.geocode.key / QQMAP_KEY）");
        }
        long now = clock.millis();
        long until = unavailableUntilMillis.get();
        if (now < until) {
            throw unavailable(secondsBetween(now, until), "熔断中（至 " + until + "）", null);
        }
        TransactionBoundaryGuard.warnIfInTransaction("tencent-lbs." + operation, TencentLbsClient.class);
        JsonNode root;
        try {
            String url = GEOCODER_URL + "?" + query
                    + "&key=" + URLEncoder.encode(properties.key(), StandardCharsets.UTF_8);
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "quwuting-service/1.0")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            root = objectMapper.readTree(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw unavailable(TRANSIENT_RETRY_AFTER_SECONDS, "请求被中断", e);
        } catch (Exception e) {
            throw unavailable(TRANSIENT_RETRY_AFTER_SECONDS, "网络/解析失败：" + e.getMessage(), e);
        }
        int status = root.path("status").asInt(-1);
        if (status == STATUS_OK) {
            return root;
        }
        String message = root.path("message").asText("");
        if (status == STATUS_DAILY_QUOTA_EXHAUSTED) {
            long resetAt = nextQuotaResetMillis();
            unavailableUntilMillis.set(resetAt);
            log.warn("[tencent-lbs] 日配额耗尽（status=121），熔断至次日 0 点（{}s 后自动恢复）",
                    secondsBetween(clock.millis(), resetAt));
            throw unavailable(secondsBetween(clock.millis(), resetAt), "日配额耗尽", null);
        }
        if (status == STATUS_RATE_LIMITED) {
            throw unavailable(TRANSIENT_RETRY_AFTER_SECONDS, "每秒请求超限", null);
        }
        if (status >= 100 && status < 200) {
            unavailableUntilMillis.set(clock.millis() + MISCONFIGURED_RETRY_AFTER_SECONDS * 1000);
            log.error("[tencent-lbs] key 配置/授权错误 status={} message={}，熔断 {}s", status, message,
                    MISCONFIGURED_RETRY_AFTER_SECONDS);
            throw unavailable(MISCONFIGURED_RETRY_AFTER_SECONDS, "配置错误 status=" + status, null);
        }
        if (status >= 300 && status < 400) {
            throw new BusinessException(1001, "地理编码参数无效");
        }
        throw unavailable(TRANSIENT_RETRY_AFTER_SECONDS, "上游错误 status=" + status + " " + message, null);
    }

    private long nextQuotaResetMillis() {
        ZonedDateTime now = ZonedDateTime.now(clock.withZone(QUOTA_ZONE));
        LocalDate tomorrow = now.toLocalDate().plusDays(1);
        return tomorrow.atStartOfDay(QUOTA_ZONE).toInstant().toEpochMilli();
    }

    private static long secondsBetween(long fromMillis, long toMillis) {
        return Math.max(1, (toMillis - fromMillis + 999) / 1000);
    }

    private static ExternalServiceUnavailableException unavailable(long retryAfterSeconds, String detail,
                                                                   Throwable cause) {
        return new ExternalServiceUnavailableException(DEPENDENCY, retryAfterSeconds, USER_MESSAGE, detail, cause);
    }
}
