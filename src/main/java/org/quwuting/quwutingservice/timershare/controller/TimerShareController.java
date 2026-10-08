package org.quwuting.quwutingservice.timershare.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.request.SettleTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareCloseResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareJoinResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerSharePeerResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareSettleResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareStatusResponse;
import org.quwuting.quwutingservice.timershare.service.TimerShareService;
import org.quwuting.quwutingservice.wxacode.service.WxacodeImage;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.WebRequest;

import java.time.Duration;
import java.util.Optional;

/**
 * 计时「二维码同步给对方」接口（2026-10-07，V42；2026-10-08，V45；文档 = docs/agents/54-timer-share.md）。
 * <p>
 * 全仓只有 GET / POST。除码图外全部需登录——方法首行 {@code UserContext.requireAuth()}
 * （重放安全不变量：鉴权先于任何副作用；401 后客户端静默续期并重放，不能出现「鉴权失败前已写了库」）。
 *
 * <pre>
 *   POST /timer-shares                      主持方：创建 / 刷新（幂等）
 *   GET  /timer-shares/{token}/status       主持方：轮询「谁加入了 / 谁已结算」+ 加入者资料
 *   POST /timer-shares/{token}/close        主持方：结束 / 结算 / 丢弃时清理（幂等）
 *   POST /timer-shares/{token}/settle       主持方或加入者：结算事实同步（尽力而为，V45）
 *   POST /timer-shares/{token}/join         接收方：扫码加入（预期业务状态以 outcome 回传）
 *   GET  /timer-shares/{token}/peer         接收方：读「对方」结算事实 + 主持方资料（V45）
 *   GET  /timer-shares/{token}/wxacode.jpg  码图（公开；token 即凭据）
 * </pre>
 */
@RestController
@RequestMapping("/timer-shares")
@RequiredArgsConstructor
public class TimerShareController {

    private final TimerShareService timerShareService;

    @PostMapping
    public ApiResponse<TimerShareResponse> createOrRefresh(@RequestBody CreateTimerShareRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareService.createOrRefresh(userId, request));
    }

    @GetMapping("/{token}/status")
    public ApiResponse<TimerShareStatusResponse> status(@PathVariable("token") String token) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareService.status(userId, token));
    }

    @PostMapping("/{token}/close")
    public ApiResponse<TimerShareCloseResponse> close(@PathVariable("token") String token) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareService.close(userId, token));
    }

    /**
     * 结算事实上报（2026-10-08，V45）：谁先结算谁上报，对方经 status / peer 读它。
     * 尽力而为——调用方（客户端结算完成后）不因失败重试打断主流程；token 无效 / 非成员
     * 以 {@code recorded=false} 数据回传（同 join 的 outcome 模式），读数非法才抛 1041。
     */
    @PostMapping("/{token}/settle")
    public ApiResponse<TimerShareSettleResponse> settle(@PathVariable("token") String token,
                                                        @RequestBody SettleTimerShareRequest request) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareService.settle(userId, token, request));
    }

    /**
     * 加入者读「对方」状态（2026-10-08，V45）：主持方结算事实（几点结束）+ 主持方资料。
     * 鉴权 = 必须是这张会话的加入者（主持方走 status）；CLOSED / EXPIRED 同样可读。
     */
    @GetMapping("/{token}/peer")
    public ApiResponse<TimerSharePeerResponse> peer(@PathVariable("token") String token) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareService.peer(userId, token));
    }

    @PostMapping("/{token}/join")
    public ApiResponse<TimerShareJoinResponse> join(@PathVariable("token") String token) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(timerShareService.join(userId, token));
    }

    /**
     * 码图（image/jpeg 直出，非 ApiResponse JSON——前端 image 组件直连）。
     * <p>
     * <b>公开</b>：小程序 {@code <image src>} 无法携带 Authorization 头。token 本身就是凭据
     * （10 位 base62 不可枚举），而码图的内容就是「一个 token 的二维码」——拿到图的人本来就拿到了
     * token，不存在比 token 更高的敏感度。防滥用靠 {@code TimerShareService#renderQr}：
     * 会话不存在 / 已关闭 / 已过期 / 开关关闭一律 404，且不触发微信外呼。
     * <p>
     * 缓存：ETag（= 码规格指纹，与 wxacode 域同口径）+ {@code private, max-age=600}。内容对一个 token 恒定，
     * 但会话失效后不应再被缓存命中，所以 max-age 取与二维码有效期同量级（10 分钟）而非 24 小时。
     */
    @GetMapping("/{token}/wxacode.jpg")
    public ResponseEntity<byte[]> wxacode(@PathVariable("token") String token, WebRequest request) {
        Optional<WxacodeImage> image = timerShareService.renderQr(token);
        if (image.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        if (request.checkNotModified(image.get().etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED).build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .eTag(image.get().etag())
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(10)).cachePrivate())
                .body(image.get().bytes());
    }
}
