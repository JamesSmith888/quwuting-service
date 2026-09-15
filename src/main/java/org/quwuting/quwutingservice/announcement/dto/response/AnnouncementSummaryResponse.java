package org.quwuting.quwutingservice.announcement.dto.response;

import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementSource;

import java.time.LocalDateTime;

/**
 * 用户端公告列表项（GET /announcements，需登录）。
 * <p>
 * 列表轻量化：不含 content（详情接口单独拉取）；read/unread 按当前用户
 * 已读回执批量派生（一次 IN 查询，消除 N+1）。发布时间展示用 publishAt
 * （定时发布 = 计划生效时刻，语义对齐「公告何时生效」而非创建时刻）。
 * <p>
 * <b>read 与 unread 是两个不同的判断（2026-09-15）</b>：
 * <ul>
 *   <li>{@code read} = <b>已读回执事实</b>（用户是否打开过该条详情）；</li>
 *   <li>{@code unread} = <b>是否构成未读债务</b>（{@code touchLevel = ALERT} 且无回执）
 *       —— SILENT 的流水类公告（每日舞讯）永远为 false。</li>
 * </ul>
 * <b>渲染未读点 / 未读徽标一律消费 {@code unread}</b>；{@code read} 只用于
 * "已读事实"类展示（原文详情回显、阅读统计口径），勿用 {@code !read} 代替。
 */
public record AnnouncementSummaryResponse(
        Long id,
        String title,
        AnnouncementCategory category,
        AnnouncementSource source,
        boolean pinned,
        LocalDateTime publishAt,
        boolean read,
        boolean unread,
        LocalDateTime createdAt
) {}
