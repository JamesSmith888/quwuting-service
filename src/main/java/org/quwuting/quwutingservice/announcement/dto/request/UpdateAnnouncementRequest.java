package org.quwuting.quwutingservice.announcement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;

import java.time.LocalDateTime;

/**
 * 更新公告请求（POST /admin/announcements/{id}/update，需 ADMIN）。
 * <p>
 * 字段与创建一致；状态机约束在 Service：
 * <ul>
 *   <li>DRAFT：全字段可改；</li>
 *   <li>PUBLISHED：<b>仅允许追加正文</b>（新内容 = 原内容 + 追加），title/category/
 *       pinned/publishAt/offlineAt 锁定——禁静默篡改已发公告（docs/agents/34 契约）；</li>
 *   <li>OFFLINE：禁改（需重新 publish 走新发布周期）。</li>
 * </ul>
 */
public record UpdateAnnouncementRequest(
        @NotBlank(message = "标题不能为空")
        @Size(max = 50, message = "标题不能超过 50 字")
        String title,

        @NotBlank(message = "公告内容不能为空")
        @Size(max = 50000, message = "公告内容不能超过 50KB")
        String content,

        @NotNull(message = "公告分类不能为空")
        AnnouncementCategory category,

        Boolean pinned,

        LocalDateTime publishAt,

        LocalDateTime offlineAt
) {}
