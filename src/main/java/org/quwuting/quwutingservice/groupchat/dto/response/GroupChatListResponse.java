package org.quwuting.quwutingservice.groupchat.dto.response;

import java.util.List;

/**
 * 舞友群公开列表响应（GET /group-chats）。
 * <p>
 * 按维度分组返回，前端按 全国 → 城市 → 地域 固定顺序渲染（有数据的组才展示）。
 * 城市组不做客户端城市过滤——运营配置的群数量可控（全国 1-2、城市若干、地域 0-2），
 * 一次性返回让用户自选（"你的城市"置顶标记由前端按最近选择城市派生，见
 * pages/group-chats 实现）。
 */
public record GroupChatListResponse(
        List<GroupChatResponse> nationwide,
        List<GroupChatResponse> city,
        List<GroupChatResponse> region
) {
}
