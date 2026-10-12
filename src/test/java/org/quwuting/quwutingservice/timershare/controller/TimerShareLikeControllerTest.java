package org.quwuting.quwutingservice.timershare.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.GlobalExceptionHandler;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.timershare.dto.request.SetTimerShareLikeRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeSetResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikesResponse;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 点赞的 HTTP 层契约（V48；standalone MockMvc——不起容器、不连库）。
 * 只锁 HTTP 这一层会悄悄出错的东西：路由与动词（全仓只有 GET / POST）、鉴权先于任何副作用
 * （未登录 ⇒ 401 且 Service 从未被调用）、请求体经真实 Jackson 转换器绑定、响应经 ApiResponse 包装后的形状。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareLikeControllerTest {

    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private TimerShareLikeService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        mvc = MockMvcBuilders.standaloneSetup(new TimerShareLikeController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
        UserContext.set(10L, UserRole.USER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void readWrapsTheItemsAndPassesTheCallerAndToken() throws Exception {
        when(service.likes(10L, TOKEN)).thenReturn(new TimerShareLikesResponse(true,
                List.of(new TimerShareLikesResponse.Item("JOINER", 1, true, false, true)), 1_780_000_000_000L));

        mvc.perform(get("/timer-shares/" + TOKEN + "/likes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.items[0].relation").value("JOINER"))
                .andExpect(jsonPath("$.data.items[0].seq").value(1))
                .andExpect(jsonPath("$.data.items[0].likedByMe").value(true))
                .andExpect(jsonPath("$.data.items[0].likesMe").value(false))
                .andExpect(jsonPath("$.data.items[0].likeable").value(true));
    }

    @Test
    void writeBindsTheJsonBodyAndReturnsTheOutcomeAsData() throws Exception {
        when(service.set(eq(10L), eq(TOKEN), any())).thenReturn(
                new TimerShareLikeSetResponse("OK", true, 1_780_000_000_000L));

        mvc.perform(post("/timer-shares/" + TOKEN + "/likes").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"relation\":\"JOINER\",\"seq\":2,\"liked\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.outcome").value("OK"))
                .andExpect(jsonPath("$.data.liked").value(true));

        ArgumentCaptor<SetTimerShareLikeRequest> body = ArgumentCaptor.forClass(SetTimerShareLikeRequest.class);
        verify(service).set(eq(10L), eq(TOKEN), body.capture());
        assertEquals("JOINER", body.getValue().relation());
        assertEquals(2, body.getValue().seq());
        assertEquals(true, body.getValue().liked());
    }

    @Test
    void unauthenticatedCallsAreRejectedWith401BeforeAnyServiceCall() throws Exception {
        UserContext.clear();

        mvc.perform(get("/timer-shares/" + TOKEN + "/likes")).andExpect(status().isUnauthorized());
        mvc.perform(post("/timer-shares/" + TOKEN + "/likes").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void onlyGetAndPostAreMapped() {
        // 全仓禁 PUT / PATCH / DELETE（取消赞也是 POST）。standalone 下未映射的动词会被全局兜底处理器吞成 500，
        // 无法靠请求断言，所以直接断言映射注解：控制器的每个处理方法只能是 GET 或 POST
        for (java.lang.reflect.Method method : TimerShareLikeController.class.getDeclaredMethods()) {
            boolean get = method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class);
            boolean post = method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class);
            assertEquals(true, get ^ post, method.getName() + " 必须恰好是 GET 或 POST 之一");
            assertEquals(false, method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class));
        }
    }
}
