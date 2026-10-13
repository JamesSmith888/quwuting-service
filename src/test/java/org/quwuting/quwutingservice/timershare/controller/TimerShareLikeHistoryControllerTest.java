package org.quwuting.quwutingservice.timershare.controller;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.GlobalExceptionHandler;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 点赞记录的 HTTP 层契约（standalone MockMvc）：路由与动词（只有 GET）、鉴权先于任何副作用、
 * 可选「看谁的」参数的绑定（缺省 = null = 本人）、响应经 ApiResponse 包装后的形状。
 */
@ExtendWith(MockitoExtension.class)
class TimerShareLikeHistoryControllerTest {

    private static final String TOKEN = "AbC123xyz0";

    @Mock
    private TimerShareLikeHistoryService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        mvc = MockMvcBuilders.standaloneSetup(new TimerShareLikeHistoryController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
        UserContext.set(10L, UserRole.USER);
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static TimerShareLikeHistoryResponse sample() {
        return new TimerShareLikeHistoryResponse(true, "SELF", new TimerShareLikeHistoryResponse.Summary(1, 0, 1),
                List.of(new TimerShareLikeHistoryResponse.Group(false, "小美", null, 1, 0, 1_780_000_000_000L,
                        List.of(new TimerShareLikeHistoryResponse.Event("RECEIVED", 1_780_000_000_000L)))),
                false, 1_780_000_000_999L);
    }

    @Test
    void withoutPeerParametersAllThreeArriveAsNull() throws Exception {
        when(service.history(10L, null, null, null)).thenReturn(sample());

        mvc.perform(get("/timer-shares/likes/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.subject").value("SELF"))
                .andExpect(jsonPath("$.data.summary.received").value(1))
                .andExpect(jsonPath("$.data.groups[0].nickname").value("小美"))
                .andExpect(jsonPath("$.data.groups[0].events[0].direction").value("RECEIVED"));

        verify(service).history(10L, null, null, null);
    }

    @Test
    void peerParametersAreBoundFromTheQueryString() throws Exception {
        when(service.history(10L, TOKEN, "JOINER", 2)).thenReturn(sample());

        mvc.perform(get("/timer-shares/likes/history").param("token", TOKEN).param("relation", "JOINER").param("seq", "2"))
                .andExpect(status().isOk());

        verify(service).history(10L, TOKEN, "JOINER", 2);
    }

    @Test
    void unauthenticatedCallsAreRejectedWith401BeforeAnyServiceCall() throws Exception {
        UserContext.clear();

        mvc.perform(get("/timer-shares/likes/history")).andExpect(status().isUnauthorized());

        verifyNoInteractions(service);
    }

    @Test
    void doesNotCollideWithTheTokenScopedLikesRoute() throws Exception {
        // /timer-shares/{token}/likes 的第 3 段恒为 likes；记录路由的第 3 段是 history——互不吞对方
        when(service.history(10L, null, null, null)).thenReturn(sample());

        mvc.perform(get("/timer-shares/likes/history")).andExpect(status().isOk());
        verify(service).history(10L, null, null, null);
    }

    @Test
    void onlyGetIsMapped() {
        for (java.lang.reflect.Method method : TimerShareLikeHistoryController.class.getDeclaredMethods()) {
            assertEquals(true, method.isAnnotationPresent(org.springframework.web.bind.annotation.GetMapping.class),
                    method.getName() + " 必须是 GET");
            assertEquals(false, method.isAnnotationPresent(org.springframework.web.bind.annotation.PostMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PutMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.PatchMapping.class)
                    || method.isAnnotationPresent(org.springframework.web.bind.annotation.DeleteMapping.class));
        }
    }
}
