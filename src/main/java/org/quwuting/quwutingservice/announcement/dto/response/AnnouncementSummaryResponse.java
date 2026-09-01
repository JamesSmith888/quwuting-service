package org.quwuting.quwutingservice.announcement.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;

import java.time.LocalDateTime;

/**
 * 用户端公告列表项（GET /announcements，需登录）。
 * <p>
 * 列表轻量化：不含 content（详情接口单独拉取）；read 布尔按当前用户
 * 已读回执批量派生（一次 IN 查询，消除 N+1）。发布时间展示用 publishAt
 * （定时发布 = 计划生效时刻，语义对齐「公告何时生效」而非创建时刻）。
 */
public record AnnouncementSummaryResponse(
        Long id,
        String title,
        AnnouncementCategory category,
        AnnouncementSource source,
        boolean pinned,
        LocalDateTime publishAt,
        boolean read,
        LocalDateTime createdAt
) {}
