package org.quwuting.quwutingservice.timershare.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse;
import org.quwuting.quwutingservice.timershare.service.TimerShareLikeHistoryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 计时「点赞记录」接口（2026-10-13；文档 = docs/agents/55-timer-share-likes.md §八）。
 * <p>
 * 与 {@link TimerShareLikeController} 同一前缀、独立控制器：那边是「一张会话里的点赞状态与写入」，这里是跨会话的
 * 只读记录。全仓只有 GET / POST；方法首行 {@code UserContext.requireAuth()}（鉴权先于任何副作用）。
 *
 * <pre>
 *   GET /timer-shares/likes/history                                 我自己收到的 / 送出的赞，按人分组
 *   GET /timer-shares/likes/history?token=&amp;relation=&amp;seq=         我在这张会话里点开的那位同行者的记录（授权 = 我是这张会话的成员）
 * </pre>
 * 路由不与 {@code /{token}/likes} 冲突：该路径的第 3 段恒为 {@code likes}，这里第 3 段是 {@code history}。
 * token 放 query 而不是路径：它指明「看谁的」，是可选修饰，不是资源标识（且本域的 token 本来就出现在 URL 里，敏感度同路径）。
 */
@RestController
@RequestMapping("/timer-shares")
@RequiredArgsConstructor
public class TimerShareLikeHistoryController {

    private final TimerShareLikeHistoryService historyService;

    @GetMapping("/likes/history")
    public ApiResponse<TimerShareLikeHistoryResponse> history(
            @RequestParam(value = "token", required = false) String token,
            @RequestParam(value = "relation", required = false) String relation,
            @RequestParam(value = "seq", required = false) Integer seq) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(historyService.history(userId, token, relation, seq));
    }
}
