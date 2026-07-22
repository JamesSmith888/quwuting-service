package org.quwuting.quwutingservice.auth.service;

import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.springframework.beans.factory.annotation.Value;
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
}
