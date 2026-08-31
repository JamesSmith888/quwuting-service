package org.quwuting.quwutingservice.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import java.net.http.HttpClient;
import java.time.Duration;

/**
 * 调用微信 jscode2session 接口，用临时 code 换取 openid。
 * 文档：https://developers.weixin.qq.com/miniprogram/dev/OpenApiDoc/user-login/code2Session.html
 */
@Slf4j
@Service
public class WechatService {

    private static final String JSCODE2SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session?appid={appid}&secret={secret}&js_code={code}&grant_type=authorization_code";

    /** 外部 API 连接超时：微信接口偶发慢响应，避免阻塞请求线程 */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    /** 外部 API 读取超时 */
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String appid;
    private final String secret;

    public WechatService(
            @Value("${wechat.appid}") String appid,
            @Value("${wechat.secret}") String secret,
            ObjectMapper objectMapper
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
    }

    /**
     * 用 wx.login() 获取的 code 换取 openid。
     * <p>
     * 微信 API 响应的 Content-Type 不可靠：jscode2session 已知会以 {@code text/plain}
     * 返回 JSON 体。RestClient 默认的 Jackson 转换器仅接受 {@code application/json}，
     * 直接 {@code .body(XxxResponse.class)} 会抛 {@code UnknownContentTypeException}。
     * 因此统一以 {@code String.class} 接收响应体（StringHttpMessageConverter 兼容任意
     * Content-Type），再用 ObjectMapper 手动解析。
     *
     * @param code 微信客户端 wx.login() 返回的临时登录凭证
     * @return 该用户的唯一标识 openid
     * @throws BusinessException 当微信返回错误码或响应无法解析时
     */
    public String code2Session(String code) {
        String body = restClient.get()
                .uri(JSCODE2SESSION_URL, appid, secret, code)
                .retrieve()
                .body(String.class);

        if (body == null || body.isBlank()) {
            throw new BusinessException(5001, "微信接口无响应");
        }

        Code2SessionResponse resp;
        try {
            resp = objectMapper.readValue(body, Code2SessionResponse.class);
        } catch (Exception e) {
            log.error("WeChat code2Session response parse failed, body={}", body, e);
            throw new BusinessException(5001, "微信接口响应格式异常");
        }

        if (resp.errcode() != null && resp.errcode() != 0) {
            log.warn("WeChat code2Session failed: errcode={}, errmsg={}", resp.errcode(), resp.errmsg());
            throw new BusinessException(1003, "微信登录失败: " + resp.errmsg());
        }
        if (resp.openid() == null || resp.openid().isBlank()) {
            throw new BusinessException(1003, "微信登录失败: 未获取到openid");
        }
        return resp.openid();
    }

    /** 微信 jscode2session 响应体 */
    private record Code2SessionResponse(
            String openid,
            String session_key,
            String unionid,
            Integer errcode,
            String errmsg
    ) {}

    // ── Web 管理后台扫码登录（2026-08-31） ─────────────────────────────────

    private static final String GET_ACCESS_TOKEN_URL =
            "https://api.weixin.qq.com/cgi-bin/token?grant_type=client_credential&appid={appid}&secret={secret}";
    private static final String GET_UNLIMITED_QRCODE_URL =
            "https://api.weixin.qq.com/wxa/getwxacodeunlimit?access_token={token}";

    /** access_token 缓存（微信有效期 7200s，提前 300s 刷新） */
    private volatile String accessToken;
    private volatile long accessTokenExpiresAt;

    /**
     * 获取微信 access_token（内存缓存，7200s 过期提前 300s 刷新）。
     * 用于小程序码生成等需稳定服务端凭据的接口。
     */
    public String getAccessToken() {
        if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - 300_000L) {
            return accessToken;
        }
        synchronized (this) {
            if (accessToken != null && System.currentTimeMillis() < accessTokenExpiresAt - 300_000L) {
                return accessToken;
            }
            String body = restClient.get()
                    .uri(GET_ACCESS_TOKEN_URL, appid, secret)
                    .retrieve()
                    .body(String.class);
            if (body == null || body.isBlank()) {
                throw new BusinessException(5001, "微信接口无响应");
            }
            AccessTokenResponse resp;
            try {
                resp = objectMapper.readValue(body, AccessTokenResponse.class);
            } catch (Exception e) {
                log.error("WeChat getAccessToken response parse failed, body={}", body, e);
                throw new BusinessException(5001, "微信接口响应格式异常");
            }
            if (resp.errcode() != null && resp.errcode() != 0) {
                log.warn("WeChat getAccessToken failed: errcode={}, errmsg={}", resp.errcode(), resp.errmsg());
                throw new BusinessException(5001, "获取微信凭据失败: " + resp.errmsg());
            }
            accessToken = resp.access_token();
            accessTokenExpiresAt = System.currentTimeMillis() + resp.expires_in() * 1000L;
            return accessToken;
        }
    }

    /**
     * 生成小程序码（getwxacodeunlimit）——Web 管理后台扫码登录用。
     * 成功返回 PNG 图片字节；失败抛业务异常。page 不校验已发布（check_path=false），
     * env_version 由调用方按环境传入（release/trial/develop）。
     *
     * @param scene       ≤32 字符的会话标识（本平台为 "wa_" + 会话 ID）
     * @param page        小程序页面路径（需在 app.json 注册）
     * @param envVersion  release / trial / develop
     */
    public byte[] getUnlimitedQrCode(String scene, String page, String envVersion) {
        String token = getAccessToken();
        String reqBody;
        try {
            reqBody = objectMapper.writeValueAsString(java.util.Map.of(
                    "scene", scene,
                    "page", page,
                    "check_path", false,
                    "env_version", envVersion
            ));
        } catch (Exception e) {
            log.error("小程序码参数序列化失败", e);
            throw new BusinessException(5001, "小程序码参数序列化失败");
        }
        ResponseEntity<byte[]> resp = restClient.post()
                .uri(GET_UNLIMITED_QRCODE_URL, token)
                .contentType(MediaType.APPLICATION_JSON)
                .body(reqBody)
                .retrieve()
                .toEntity(byte[].class);

        byte[] bodyBytes = resp.getBody();
        if (bodyBytes == null || bodyBytes.length == 0) {
            throw new BusinessException(5001, "微信接口无响应");
        }
        // 成功返回 image/* 二进制；失败返回 JSON（Content-Type: application/json）
        MediaType ct = resp.getHeaders().getContentType();
        boolean isJson = ct != null && ct.includes(MediaType.APPLICATION_JSON);
        if (isJson || bodyBytes[0] == '{') {
            try {
                QrCodeError err = objectMapper.readValue(bodyBytes, QrCodeError.class);
                log.warn("WeChat getwxacodeunlimit failed: errcode={}, errmsg={}", err.errcode(), err.errmsg());
                throw new BusinessException(5001, "生成小程序码失败: " + err.errmsg());
            } catch (BusinessException be) {
                throw be;
            } catch (Exception e) {
                throw new BusinessException(5001, "小程序码接口响应异常");
            }
        }
        return bodyBytes;
    }

    /** 微信 access_token 响应体 */
    private record AccessTokenResponse(String access_token, Integer expires_in, Integer errcode, String errmsg) {}

    /** getwxacodeunlimit 错误响应体（成功时是二进制图片，无 JSON） */
    private record QrCodeError(Integer errcode, String errmsg) {}
}
