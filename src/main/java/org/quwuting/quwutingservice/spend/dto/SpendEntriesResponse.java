package org.quwuting.quwutingservice.spend.dto;

import java.util.List;

/**
 * 增量拉取响应：nextCursor = 本批最大 updatedAt（epoch 毫秒）；不足一页 = 已到
 * 尾部，客户端以 nextCursor 持久化下次游标（空列表时 nextCursor 原样回传请求游标）。
 */
public record SpendEntriesResponse(
        List<SpendEntryResponse> entries,
        Long nextCursor
) {
}
