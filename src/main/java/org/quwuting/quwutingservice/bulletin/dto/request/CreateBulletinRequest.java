package org.quwuting.quwutingservice.bulletin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 创建快讯请求（POST /admin/bulletins/create，需 ADMIN）。
 * <p>
 * 与公告创建请求的差异：<b>无 category 字段</b>——快讯分类由服务端固定为 FLASH，
 * source 固定为 MANUAL（两个字段都不接受调用方指定，防跨域写入）；
 * 另有 city / venueId 两个快讯专属字段（均可空）。
 * <p>
 * 与公告同款：publishAt 未来时刻 = 计划发布时间；缺省 = 存草稿不发布。
 */
public record CreateBulletinRequest(
        @NotBlank(message = "快讯内容不能为空")
        @Size(max = 50000, message = "快讯内容不能超过 50KB")
        String content,

        @Size(max = 32, message = "城市名称不能超过 32 字")
        String city,

        Long venueId,

        LocalDateTime publishAt,

        LocalDateTime offlineAt
) {}
