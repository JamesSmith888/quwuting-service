package org.quwuting.quwutingservice.geo.tencent;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.config.GeocodeProperties;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.exception.ExternalServiceUnavailableException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 腾讯位置服务状态码语义与配额熔断（2026-10-01，Mockito 单测，不发真实网络请求）。
 */
class TencentLbsClientTest {

    /** 2026-10-01 22:00（北京时间）——距次日 0 点 2 小时 */
    private static final Clock AT_22_BEIJING =
            Clock.fixed(Instant.parse("2026-10-01T14:00:00Z"), ZoneId.of("Asia/Shanghai"));

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @SuppressWarnings("unchecked")
    private static HttpClient respondingWith(String body) throws Exception {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        when(response.body()).thenReturn(body);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
        return http;
    }

    private TencentLbsClient client(HttpClient http) {
        return new TencentLbsClient(new GeocodeProperties("test-key"), objectMapper, AT_22_BEIJING, http);
    }

    @Test
    void dailyQuotaExhaustionLatchesUntilMidnightWithoutFurtherCalls() throws Exception {
        HttpClient http = respondingWith("{\"status\":121,\"message\":\"此key每日调用量已达到上限\"}");
        TencentLbsClient client = client(http);

        ExternalServiceUnavailableException first = assertThrows(ExternalServiceUnavailableException.class,
                () -> client.reverseCity(32.0, 120.8));
        assertEquals(2 * 3600, first.getRetryAfterSeconds(), "Retry-After = 距北京时间次日 0 点的秒数");

        ExternalServiceUnavailableException second = assertThrows(ExternalServiceUnavailableException.class,
                () -> client.reverseCity(31.0, 121.4));
        assertEquals(2 * 3600, second.getRetryAfterSeconds());
        verify(http, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void rateLimitIsTransientAndDoesNotLatch() throws Exception {
        HttpClient http = respondingWith("{\"status\":120,\"message\":\"每秒请求量已达到上限\"}");
        TencentLbsClient client = client(http);

        assertEquals(1, assertThrows(ExternalServiceUnavailableException.class,
                () -> client.reverseCity(32.0, 120.8)).getRetryAfterSeconds());
        assertThrows(ExternalServiceUnavailableException.class, () -> client.reverseCity(32.0, 120.8));
        verify(http, times(2)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    void parameterErrorIsCallersFault() throws Exception {
        TencentLbsClient client = client(respondingWith("{\"status\":310,\"message\":\"请求参数信息有误\"}"));
        assertEquals(1001, assertThrows(BusinessException.class, () -> client.reverseCity(32.0, 120.8)).getCode());
    }

    @Test
    void successfulReverseReturnsCity() throws Exception {
        TencentLbsClient client = client(respondingWith(
                "{\"status\":0,\"result\":{\"address_component\":{\"city\":\"南通市\"}}}"));
        assertEquals("南通市", client.reverseCity(32.0, 120.8));
    }

    @Test
    void missingKeyIsConfigurationErrorNotUpstreamFailure() {
        TencentLbsClient client = new TencentLbsClient(new GeocodeProperties(""), objectMapper,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), mock(HttpClient.class));
        assertEquals(1004, assertThrows(BusinessException.class, () -> client.reverseCity(32.0, 120.8)).getCode());
    }
}
