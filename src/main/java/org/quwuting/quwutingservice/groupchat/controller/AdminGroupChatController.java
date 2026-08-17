package org.quwuting.quwutingservice.groupchat.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.groupchat.dto.request.GroupChatUpsertRequest;
import org.quwuting.quwutingservice.groupchat.dto.response.GroupChatResponse;
import org.quwuting.quwutingservice.groupchat.service.GroupChatService;
import org.quwuting.quwutingservice.security.UserContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 舞友群管理端接口（仅 ADMIN）。
 * 入口 = 首页 FAB「群聊管理」菜单（qwt-fab adminOnly 过滤，与「运营配置」并列）。
 * 更新 / 上下线 / 删除均走 POST（项目禁 PUT/PATCH/DELETE 语义）。
 */
@RestController
@RequestMapping("/admin/group-chats")
@RequiredArgsConstructor
public class AdminGroupChatController {

    private final GroupChatService groupChatService;

    /** 管理端列表（GET /admin/group-chats，需 ADMIN；含已下线，不含已软删） */
    @GetMapping
    public ApiResponse<List<GroupChatResponse>> list() {
        UserContext.requireAdmin();
        return ApiResponse.ok(groupChatService.listAdmin());
    }

    /** 创建群（POST /admin/group-chats，需 ADMIN） */
    @PostMapping
    public ApiResponse<GroupChatResponse> create(@Valid @RequestBody GroupChatUpsertRequest request) {
        return ApiResponse.ok(groupChatService.create(request, UserContext.requireAdmin()));
    }

    /** 更新群（POST /admin/group-chats/{id}/update，需 ADMIN；全量覆盖，enabled 不动） */
    @PostMapping("/{id}/update")
    public ApiResponse<GroupChatResponse> update(
            @PathVariable Long id,
            @Valid @RequestBody GroupChatUpsertRequest request
    ) {
        return ApiResponse.ok(groupChatService.update(id, request, UserContext.requireAdmin()));
    }

    /** 上下线切换（POST /admin/group-chats/{id}/toggle，需 ADMIN；公开读立即生效） */
    @PostMapping("/{id}/toggle")
    public ApiResponse<GroupChatResponse> toggle(@PathVariable Long id) {
        return ApiResponse.ok(groupChatService.toggle(id, UserContext.requireAdmin()));
    }

    /** 软删（POST /admin/group-chats/{id}/delete，需 ADMIN） */
    @PostMapping("/{id}/delete")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        groupChatService.delete(id, UserContext.requireAdmin());
        return ApiResponse.ok(null);
    }
}
