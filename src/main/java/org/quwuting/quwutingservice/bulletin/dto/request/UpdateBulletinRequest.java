package org.quwuting.quwutingservice.bulletin.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/**
 * 更新快讯请求（POST /admin/bulletins/{id}/update，需 ADMIN）。
 * <p>
 * 字段与创建一致（category/source 同样由服务端固定）。状态机约束沿用公告域口径：
 * <ul>
 *   <li>DRAFT：全字段可改（含 publishAt 定时发布）；</li>
 *   <li>PUBLISHED：content/city/venueId/offlineAt 可改并即时生效；
 *       publishAt 锁定（要改定时请先下线再重新发布）；</li>
 *   <li>OFFLINE：禁改（需重新 publish 走新发布周期）。</li>
 * </ul>
 * 快讯无置顶语义（纯时间流），故不含 pinned 字段；也**无 title 字段**（2026-09-11 五稿删除，共享表 title 列由服务端写派生摘要，见 BulletinExcerpt）。
 */
public record UpdateBulletinRequest(
        @NotBlank(message = "快讯内容不能为空")
        @Size(max = 50000, message = "快讯内容不能超过 50KB")
        String content,

        @Size(max = 32, message = "城市名称不能超过 32 字")
        String city,

        Long venueId,

        LocalDateTime publishAt,

        LocalDateTime offlineAt
) {}
