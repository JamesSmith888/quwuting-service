package org.quwuting.quwutingservice.venuepresence;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.GlobalExceptionHandler;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venuepresence.controller.VenueVisitFootprintController;
import org.quwuting.quwutingservice.venuepresence.dto.response.VenueVisitFootprintResponse;
import org.quwuting.quwutingservice.venuepresence.service.VenueVisitBadgeService;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 详情页「到店足迹」行接口的 HTTP 层契约（2026-10-16；standalone MockMvc——不起容器、不连库）。
 * <p>
 * 只锁 HTTP 这一层会悄悄出错的东西：
 * ① <b>软鉴权</b>——游客（无 token）照样 200，{@code viewerId = null} 透传给 Service，
 * ⛔ 绝不是 401（401 会触发前端 httpRequest 的「清凭证 → 提示重新登录」自愈链，对纯展示读是错误副作用）；
 * 登录用户的 id 原样透传；② <b>只有 GET</b>（全仓只有 GET / POST；本资源无写动作，POST 不得到达 Service）；
 * ③ <b>null 不被全局 NON_NULL 策略吞掉</b>——{@code mineText = null} 与「字段缺失」对前端是两件事
 * （35 号教训；DTO 上的 {@code @JsonInclude(ALWAYS)} 是唯一防线，这里用与生产同款的 NON_NULL 映射器验证它真的生效）。
 */
@ExtendWith(MockitoExtension.class)
class VenueVisitFootprintControllerTest {

    @Mock
    private VenueVisitBadgeService service;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        // 与生产一致的全局 NON_NULL 策略：若 DTO 丢了 ALWAYS 注解，null 字段会被静默删掉
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        mvc = MockMvcBuilders.standaloneSetup(new VenueVisitFootprintController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    void guestGetsThePublicLineAndANullMineLineNotA401() throws Exception {
        UserContext.clear();
        when(service.footprintViewOf(121L, null)).thenReturn(
                new VenueVisitFootprintResponse("感谢 3 位舞友 · 3 次到过这附近的足迹", null));

        String body = mvc.perform(get("/venues/121/visit-footprint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.badgeText").value("感谢 3 位舞友 · 3 次到过这附近的足迹"))
                .andReturn().getResponse().getContentAsString();

        verify(service).footprintViewOf(121L, null);
        assertTrue(body.contains("\"mineText\":null"),
                "mineText 必须以显式 null 下发（不是字段缺失）——DTO 的 ALWAYS 注解不得丢：" + body);
    }

    @Test
    void loggedInViewerIdIsPassedThroughAndMineLineIsReturned() throws Exception {
        UserContext.set(7L, UserRole.USER);
        when(service.footprintViewOf(121L, 7L)).thenReturn(new VenueVisitFootprintResponse(
                "感谢 5 位舞友 · 8 次到过这附近的足迹", "你在这附近留下过 3 次足迹 · 2 位舞友感谢了你"));

        mvc.perform(get("/venues/121/visit-footprint"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mineText").value("你在这附近留下过 3 次足迹 · 2 位舞友感谢了你"));

        verify(service).footprintViewOf(121L, 7L);
    }

    @Test
    void noWriteVerbExistsOnThisResource() throws Exception {
        // 不断言具体状态码：全局异常处理器对未支持的方法如何映射是它自己的事（现为 500），
        // 本资源要守的只有「POST 到不了 Service、也不会成功」
        int status = mvc.perform(post("/venues/121/visit-footprint")).andReturn().getResponse().getStatus();

        assertNotEquals(200, status, "本资源只有读，POST 不得成功");
        verifyNoInteractions(service);
    }
}
