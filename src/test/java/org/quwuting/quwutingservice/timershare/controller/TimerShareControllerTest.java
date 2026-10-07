package org.quwuting.quwutingservice.timershare.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.exception.GlobalExceptionHandler;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareCloseResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareJoinResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareStatusResponse;
import org.quwuting.quwutingservice.timershare.service.TimerShareService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP 层契约（V42；standalone MockMvc——不起 Spring 容器、不连库）。
 * <p>
 * Service 已被 Mockito 单测覆盖，这里只锁定「HTTP 这一层」会悄悄出错的东西：
 * ① 路由与动词（全仓只有 GET/POST；码图路径的 {@code .jpg} 后缀不被内容协商吃掉）；
 * ② 鉴权先于任何副作用（未登录 ⇒ 401 且 Service 从未被调用——重放安全不变量）；
 * ③ 请求体经真实 Jackson 转换器绑定；响应经 {@code ApiResponse} 包装后的 JSON 形状；
 * ④ 码图的 404 / 304 / 缓存头。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareControllerTest {

    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private TimerShareService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        mvc = MockMvcBuilders.standaloneSetup(new TimerShareController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new JacksonJsonHttpMessageConverter(mapper))
                .build();
        UserContext.set(10L, UserRole.USER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void createBindsTheJsonBodyAndWrapsTheResponse() throws Exception {
        when(service.createOrRefresh(eq(10L), any())).thenReturn(
                new TimerShareResponse(TOKEN, "/timer-shares/" + TOKEN + "/wxacode.jpg", 1780000600000L, 1780000000000L, 0, 5));

        mvc.perform(post("/timer-shares").contentType(MediaType.APPLICATION_JSON).content(
                        "{\"sessionKey\":\"1780000000000\",\"wallElapsedMs\":600000,\"netElapsedSeconds\":540,\"running\":true,"
                                + "\"rule\":{\"tiers\":[{\"durationMinutes\":4,\"price\":20}]},\"venueId\":42}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value(TOKEN))
                .andExpect(jsonPath("$.data.qrPath").value("/timer-shares/" + TOKEN + "/wxacode.jpg"))
                .andExpect(jsonPath("$.data.maxJoins").value(5));

        ArgumentCaptor<CreateTimerShareRequest> body = ArgumentCaptor.forClass(CreateTimerShareRequest.class);
        verify(service).createOrRefresh(eq(10L), body.capture());
        assertEquals("1780000000000", body.getValue().sessionKey());
        assertEquals(600000L, body.getValue().wallElapsedMs());
        assertEquals(540, body.getValue().netElapsedSeconds());
        assertEquals(true, body.getValue().running());
        assertEquals(4.0, body.getValue().rule().tiers().get(0).durationMinutes());
        assertEquals(42L, body.getValue().venueId());
    }

    @Test
    void unauthenticatedCallsAreRejectedWith401BeforeAnyServiceCall() throws Exception {
        UserContext.clear();
        mvc.perform(post("/timer-shares").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/timer-shares/" + TOKEN + "/status")).andExpect(status().isUnauthorized());
        mvc.perform(post("/timer-shares/" + TOKEN + "/join")).andExpect(status().isUnauthorized());
        mvc.perform(post("/timer-shares/" + TOKEN + "/close")).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void businessErrorsKeepHttp200WithTheCodeInTheBody() throws Exception {
        when(service.createOrRefresh(eq(10L), any())).thenThrow(new BusinessException(1042, "计时同步暂未开放"));
        mvc.perform(post("/timer-shares").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1042))
                .andExpect(jsonPath("$.message").value("计时同步暂未开放"));
    }

    @Test
    void statusCloseAndJoinRouteToTheServiceWithTheCallerIdentity() throws Exception {
        when(service.status(10L, TOKEN)).thenReturn(new TimerShareStatusResponse("ACTIVE", 1, 5, 1780000600000L, 1780000000000L));
        when(service.close(10L, TOKEN)).thenReturn(new TimerShareCloseResponse(true));
        when(service.join(10L, TOKEN)).thenReturn(TimerShareJoinResponse.withoutSnapshot("EXPIRED", 1780000000000L));

        mvc.perform(get("/timer-shares/" + TOKEN + "/status"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.joinCount").value(1));
        mvc.perform(post("/timer-shares/" + TOKEN + "/close"))
                .andExpect(jsonPath("$.data.closed").value(true));
        mvc.perform(post("/timer-shares/" + TOKEN + "/join"))
                .andExpect(jsonPath("$.data.outcome").value("EXPIRED"))
                .andExpect(jsonPath("$.data.snapshot").doesNotExist()); // 对象映射器此处是 NON_NULL；真实应用里 ALWAYS 注解会写出 null，见 WireFormatTest
    }

    @Test
    void onlyTheDeclaredVerbsReachTheService() throws Exception {
        // 全仓只有 GET / POST。错误的动词不会命中任何处理器（具体状态码由全局异常处理器决定，
        // 本仓既有行为是兜底映射，不是 405——这里不断言它，只断言「绝不会执行到 Service、绝不会 2xx」）
        for (org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req : java.util.List.of(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/timer-shares/" + TOKEN + "/close"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/timer-shares/" + TOKEN + "/close"),
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch("/timer-shares/" + TOKEN + "/join"),
                get("/timer-shares/" + TOKEN + "/join"),
                post("/timer-shares/" + TOKEN + "/status"))) {
            int code = mvc.perform(req).andReturn().getResponse().getStatus();
            org.junit.jupiter.api.Assertions.assertTrue(code >= 400, "错误的动词不应成功：" + code);
        }
        verifyNoInteractions(service);
    }

    @Test
    void qrImageIsServedPubliclyWithEtagAndShortPrivateCache() throws Exception {
        UserContext.clear(); // 码图公开：<image src> 带不了 Authorization
        when(service.renderQr(TOKEN)).thenReturn(Optional.of(new WxacodeImage(new byte[]{1, 2, 3}, "wx|release|page|t=" + TOKEN)));

        mvc.perform(get("/timer-shares/" + TOKEN + "/wxacode.jpg"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(content().bytes(new byte[]{1, 2, 3}))
                .andExpect(header().string("ETag", "\"wx|release|page|t=" + TOKEN + "\""))
                .andExpect(header().string("Cache-Control", "max-age=600, private"));
    }

    @Test
    void qrImageConditionalRequestGets304() throws Exception {
        UserContext.clear();
        when(service.renderQr(TOKEN)).thenReturn(Optional.of(new WxacodeImage(new byte[]{1, 2, 3}, "fp-" + TOKEN)));
        mvc.perform(get("/timer-shares/" + TOKEN + "/wxacode.jpg").header("If-None-Match", "\"fp-" + TOKEN + "\""))
                .andExpect(status().isNotModified());
    }

    @Test
    void qrImageForAnUnavailableShareIs404() throws Exception {
        UserContext.clear();
        when(service.renderQr(TOKEN)).thenReturn(Optional.empty());
        mvc.perform(get("/timer-shares/" + TOKEN + "/wxacode.jpg")).andExpect(status().isNotFound());
    }
}
