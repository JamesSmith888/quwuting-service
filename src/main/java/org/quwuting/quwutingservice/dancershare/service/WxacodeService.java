package org.quwuting.quwutingservice.dancershare.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 舞伴小程序码生成（2026-08-26，21-demand-detail-card P1：解锁结果卡「生成图片发TA」
 * 图片底部合成小程序码——舞伴长按识别进详情页，把「图片」与「小程序卡片」桥接成闭环）。
 * <p>
 * 微信开放能力：
 * <ul>
 *   <li>access_token：GET /cgi-bin/token（client_credential），单例内存缓存 + 提前
 *       5 分钟过期刷新（7200s 有效期；并发下 synchronized 只取一次）；</li>
 *   <li>小程序码：POST /wxa/getwxacodeunlimit（scene = dancerId，page =
 *       dancer-detail）——成功返回 PNG/JPEG 二进制，失败返回 JSON {errcode, errmsg}；
 *       scene 只编 dancerId（需求细节已在图片上，不塞需求参数——避开 32 字符 scene
 *       上限与 URL 编码复杂度，最小化泄漏面）；</li>
 *   <li>码图按 dancerId 24h 内存缓存（同一舞伴码相同，避免重复调用微信接口）。</li>
 * </ul>
 * 降级：微信调用失败抛 5001（BusinessException），前端捕获后图片不带码（仅保留
 * 提示文案），不影响图片出口主体。
 */
@Slf4j
@Service
public class WxacodeService {

    /** access_token 获取（GET） */
    private static final String TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";
    /** 小程序码生成（POST，成功 = 图片二进制；失败 = JSON） */
    private static final String WXACODE_URL =
            "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token={token}";
    /** 码落地页（小程序内路径；scene 参数由微信注入 onLoad(query)） */
    private static final String WXACODE_PAGE = "pages/dancer-detail/dancer-detail";
    /** access_token 有效期（微信固定 7200s） */
    private static final long ACCESS_TOKEN_TTL_SECONDS = 7200L;
    /** 提前 5 分钟刷新，避免临界过期 */
    private static final long ACCESS_TOKEN_REFRESH_EARLY_SECONDS = 300L;
    /** 码图缓存时长（同一舞伴码相同，24h 足够；微信无数量限制但避免重复外呼） */
    private static final long WXACODE_CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L;
    /** 外部 API 连接/读取超时（对齐 WechatService） */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DancerRepository dancerRepository;
    private final String appid;
    private final String secret;

    /** access_token 内存缓存（volatile 单例 + 过期时间戳） */
    private volatile String cachedToken;
    private volatile long tokenExpiresAtMillis;

    /** 码图缓存：dancerId → 图片字节 + 过期时间戳 */
    private final Map<Long, CachedWxacode> codeCache = new ConcurrentHashMap<>();

    public WxacodeService(
            @Value("${wechat.appid}") String appid,
            @Value("${wechat.secret}") String secret,
            ObjectMapper objectMapper,
            DancerRepository dancerRepository
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        this.restClient = RestClient.builder()
                .requestFactory(requestFactory)
                .build();
        this.objectMapper = objectMapper;
        this.appid = appid;
        this.secret = secret;
        this.dancerRepository = dancerRepository;
    }

    /** 码图缓存条目 */
    private record CachedWxacode(byte[] bytes, long expiresAtMillis) {}

    /**
     * 获取舞伴小程序码图片（PNG/JPEG 字节）。舞伴不存在 → 1001；微信接口失败 → 5001。
     * 缓存优先：24h 内命中直接返回，不重复外呼微信。
     */
    public byte[] getWxacode(Long dancerId) {
        dancerRepository.findById(dancerId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
        CachedWxacode cached = codeCache.get(dancerId);
        if (cached != null && System.currentTimeMillis() < cached.expiresAtMillis()) {
            return cached.bytes();
        }
        byte[] png = requestWxacode(String.valueOf(dancerId));
        codeCache.put(dancerId, new CachedWxacode(png,
                System.currentTimeMillis() + WXACODE_CACHE_TTL_MILLIS));
        return png;
    }

    /** 调微信 getwxacodeunlimit（成功 = 图片字节；失败 = JSON 错误 → 5001） */
    private byte[] requestWxacode(String scene) {
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                    "scene", scene,
                    "page", WXACODE_PAGE,
                    "check_path", false,
                    "width", 430));
        } catch (Exception e) {
            throw new BusinessException(5001, "小程序码生成失败");
        }
        byte[] resp = restClient.post()
                .uri(WXACODE_URL, accessToken())
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(byte[].class);
        if (resp == null || resp.length == 0) {
            throw new BusinessException(5001, "小程序码生成失败");
        }
        // 失败 = JSON {errcode, errmsg}（首字节 '{'）；成功 = 图片二进制
        if (resp[0] == '{') {
            String text = new String(resp, java.nio.charset.StandardCharsets.UTF_8);
            log.warn("WeChat getwxacodeunlimit failed: {}", text);
            throw new BusinessException(5001, "小程序码生成失败");
        }
        return resp;
    }

    /** access_token（单例缓存 + 提前 5 分钟过期刷新；并发 synchronized 只取一次） */
    private String accessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpiresAtMillis) {
            return cachedToken;
        }
        synchronized (this) {
            if (cachedToken != null && System.currentTimeMillis() < tokenExpiresAtMillis) {
                return cachedToken;
            }
            String body = restClient.get()
                    .uri(TOKEN_URL, appid, secret)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new BusinessException(5001, "微信接口无响应");
            }
            TokenResponse resp;
            try {
                resp = objectMapper.readValue(body, TokenResponse.class);
            } catch (Exception e) {
                log.error("WeChat access_token response parse failed, body={}", body, e);
                throw new BusinessException(5001, "微信接口响应格式异常");
            }
            if (resp.errcode() != null && resp.errcode() != 0) {
                log.warn("WeChat access_token failed: errcode={}, errmsg={}",
                        resp.errcode(), resp.errmsg());
                throw new BusinessException(5001, "微信接口响应异常");
            }
            if (resp.accessToken() == null || resp.accessToken().isBlank()) {
                throw new BusinessException(5001, "微信接口响应异常");
            }
            cachedToken = resp.accessToken();
            tokenExpiresAtMillis = System.currentTimeMillis()
                    + (ACCESS_TOKEN_TTL_SECONDS - ACCESS_TOKEN_REFRESH_EARLY_SECONDS) * 1000L;
            return cachedToken;
        }
    }

    /** 微信 access_token 响应体 */
    private record TokenResponse(String access_token, Integer expires_in, Integer errcode, String errmsg) {
        String accessToken() {
            return access_token;
        }
    }
}
