package org.quwuting.quwutingservice.timershare.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.timershare.dto.request.SetTimerShareLikeRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeSetResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikesResponse;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计时「给同行者点赞」接口（2026-10-12，V48；文档 = docs/agents/54-timer-share.md §十五）。
 * <p>
 * 与 {@link TimerShareController} 同一前缀、独立控制器：点赞是另一个关注点（有自己的开关、速率、窗口与通知），
 * 不让会话生命周期的控制器继续膨胀。全仓只有 GET / POST；方法首行 {@code UserContext.requireAuth()}
 * （重放安全不变量：鉴权先于任何副作用）。
 *
 * <pre>
 *   GET  /timer-shares/{token}/likes   我面前的每一位：我赞了没有 / 他赞了我没有 / 还赞得了吗
 *   POST /timer-shares/{token}/likes   赞 / 取消赞（幂等；预期业务状态以 outcome 回传）
 * </pre>
 */
@RestController
@RequestMapping("/timer-shares")
@RequiredArgsConstructor
public class TimerShareLikeController {

    private final TimerShareLikeService timerShareLikeService;

    @GetMapping("/{token}/likes")
    public ApiResponse<TimerShareLikesResponse> likes(@PathVariable("token") String token) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareLikeService.likes(userId, token));
    }

    @PostMapping("/{token}/likes")
    public ApiResponse<TimerShareLikeSetResponse> setLike(@PathVariable("token") String token,
                                                          @RequestBody SetTimerShareLikeRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareLikeService.set(userId, token, request));
    }
}
