package org.quwuting.quwutingservice.groupchat.controller;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.common.ApiResponse;
import org.quwuting.quwutingservice.groupchat.dto.response.GroupChatListResponse;
import org.quwuting.quwutingservice.groupchat.service.GroupChatService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 舞友群公开接口 — 无鉴权（群二维码为运营公开内容，任何用户可浏览加入）。
 * 分组返回 全国 / 城市 / 地域 三类启用群。
 */
@RestController
@RequestMapping("/group-chats")
@RequiredArgsConstructor
public class GroupChatController {

    private final GroupChatService groupChatService;

    /** GET /group-chats → { nationwide: [], city: [], region: [] } */
    @GetMapping
    public ApiResponse<GroupChatListResponse> list() {
        return ApiResponse.ok(groupChatService.listPublic());
    }
}
