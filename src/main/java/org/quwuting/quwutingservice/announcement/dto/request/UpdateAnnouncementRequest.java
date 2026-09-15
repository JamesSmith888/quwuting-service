package org.quwuting.quwutingservice.announcement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementTouchLevel;

import java.time.LocalDateTime;

/**
 * 更新公告请求（POST /admin/announcements/{id}/update，需 ADMIN）。
 * <p>
 * 字段与创建一致；状态机约束在 Service（2026-09-05 修订——发布中可编辑）：
 * <ul>
 *   <li>DRAFT：全字段可改（含 publishAt 定时发布）；</li>
 *   <li>PUBLISHED：title/content/category/pinned/touchLevel/offlineAt 可改并即时生效；
 *       <b>publishAt 锁定</b>（已生效的发布时间改到未来会让公告对用户瞬间消失，
 *       要改定时请先下线再重新发布）；</li>
 *   <li>OFFLINE：禁改（需重新 publish 走新发布周期）。</li>
 * </ul>
 * {@code touchLevel} 可空 = 按分类派生（同创建语义，见
 * {@link CreateAnnouncementRequest}）。
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

        AnnouncementTouchLevel touchLevel,

        Boolean pinned,

        LocalDateTime publishAt,

        LocalDateTime offlineAt
) {}
