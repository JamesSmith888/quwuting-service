package org.quwuting.quwutingservice.bulletin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.quwuting.quwutingservice.media.MediaAttachment;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 创建快讯请求（POST /admin/bulletins/create，需 ADMIN）。
 * <p>
 * 与公告创建请求的差异：<b>无 category 字段</b>——快讯分类由服务端固定为 FLASH，
 * source 固定为 MANUAL（两个字段都不接受调用方指定，防跨域写入）；
 * 另有 city / venueId 两个快讯专属字段（均可空）。
 * <p>
 * 与公告同款：publishAt 未来时刻 = 计划发布时间；缺省 = 存草稿不发布。
 * {@code media} 可空 = 结构化媒体附件（2026-09-28，docs/agents/47 §7.3），
 * null / 空 = 幂等清空；URL 仅接受本应用存储白名单内地址。
 */
public record CreateBulletinRequest(
        @NotBlank(message = "快讯内容不能为空")
        @Size(max = 50000, message = "快讯内容不能超过 50KB")
        String content,

        @Size(max = 32, message = "城市名称不能超过 32 字")
        String city,

        Long venueId,

        List<MediaAttachment> media,

        LocalDateTime publishAt,

        LocalDateTime offlineAt
) {}
