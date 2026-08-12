package org.quwuting.quwutingservice.message.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.message.dto.response.MessageResponse;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 站内信（消息中心）用户级接口（需登录，路由挂载在 /users/me/messages 下）。
 * <ul>
 *   <li>GET /users/me/messages — 我的站内信（分页倒序，read 派生布尔）</li>
 *   <li>GET /users/me/messages/unread-count — 未读数（个人中心 / 首页 FAB 未读徽标）</li>
 *   <li>POST /users/me/messages/{id}/read — 单条标记已读（越权/已读幂等）</li>
 *   <li>POST /users/me/messages/read-all — 全部标记已读（打开消息中心即调用）</li>
 * </ul>
 * 消息是用户级资源：查询/已读操作一律以当前登录用户为收件人边界（user_id = 本人），
 * 越权访问返回空结果（repository 按 userId 过滤），不泄露他人消息。
 */
@RestController
@RequestMapping("/users/me/messages")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;

    /** 我的站内信（分页倒序；打开消息中心后前端调用 read-all 批量已读） */
    @GetMapping
    public ApiResponse<Page<MessageResponse>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(messageService.list(userId, page, size));
    }

    /** 未读消息数（未读徽标数据源；轻量接口，个人中心 / 首页 FAB onShow 拉取） */
    @GetMapping("/unread-count")
    public ApiResponse<Long> unreadCount() {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(messageService.unreadCount(userId));
    }

    /**
     * 未读的关注门店状态变化提醒（首页「关注状态变化」提醒卡片数据源，2026-08-12）：
     * VENUE_STATUS_CHANGED 类型的未读消息最新前 N 条（默认 3），未读即提醒、
     * 点击深链门店详情页 + 标记已读后从卡片消失（历史留在消息中心）。
     */
    @GetMapping("/status-alerts")
    public ApiResponse<List<MessageResponse>> statusAlerts(
            @RequestParam(defaultValue = "3") int limit) {
        Long userId = UserContext.requireAuth();
        return ApiResponse.ok(messageService.listStatusAlerts(userId, limit));
    }

    /** 单条标记已读（幂等；越权静默成功——消息已按收件人过滤） */
    @PostMapping("/{id}/read")
    public ApiResponse<Void> markOneRead(@PathVariable Long id) {
        Long userId = UserContext.requireAuth();
        messageService.markOneRead(userId, id);
        return ApiResponse.ok(null);
    }

    /** 全部标记已读（用户打开消息中心后批量置为已读；幂等） */
    @PostMapping("/read-all")
    public ApiResponse<Void> markAllRead() {
        Long userId = UserContext.requireAuth();
        messageService.markAllRead(userId);
        return ApiResponse.ok(null);
    }
}
