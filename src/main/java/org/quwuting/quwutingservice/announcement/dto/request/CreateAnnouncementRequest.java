package org.quwuting.quwutingservice.announcement.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementCategory;
import org.quwuting.quwutingservice.announcement.enums.AnnouncementTouchLevel;

import java.time.LocalDateTime;

/**
 * 创建公告请求（POST /admin/announcements/create，需 ADMIN）。
 * <p>
 * 容器级校验（标题必填 ≤50 字、正文必填 ≤50KB、分类必填）；source 由服务端
 * 固定置 MANUAL（管理端创建接口不接受 SYSTEM 来源——系统公告只能走
 * createSystem 内部通道）。pinned/publishAt/offlineAt 可空（缺省 = 不置顶、
 * 存草稿不发布、无计划下线）。
 * <p>
 * {@code touchLevel} 可空（2026-09-15）：<b>缺省 = 按分类派生</b>
 * （{@code category.defaultTouchLevel()}，NOTICE→ALERT / 其余→SILENT）。
 * 设计成"可空 + 服务端派生"而非"必填"是为了让自动化发布链路（assistant / Skill
 * 直接调接口）不传该字段也能落在正确档位——每加一条发布通道就要求补参数，
 * 漏一处就复发"每日舞讯计入未读"的老问题。
 */
public record CreateAnnouncementRequest(
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
