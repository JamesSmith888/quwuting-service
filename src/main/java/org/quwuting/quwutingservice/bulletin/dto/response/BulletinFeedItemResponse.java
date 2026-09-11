package org.quwuting.quwutingservice.bulletin.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 用户端快讯<b>流条目</b>（GET /bulletins，需登录；2026-09-10 由 {@code BulletinSummaryResponse}
 * 升级而来，见 docs/agents/47-bulletins.md「一、领域定位与信息架构」）。
 * <p>
 * <b>为什么列表接口下发 content 全文</b>：快讯列表页是 TG 频道式的<b>信息流</b>——每条内容
 * 在列表内直接完整呈现（气泡），不再"标题 → 点进详情"两步。这是本轮 IA 变更的核心，
 * 根因见 47 号文档（把公告的"列表-详情"结构套到模态完全不同的快讯上，用户点开只为
 * 看一句话/一张图，多一次跳转 + 多一次请求）。
 * <p>
 * 因此本 DTO 与详情 DTO 的差异只剩"是否携带 publishedAt"（列表按 publishAt 排序展示，
 * 详情才需要展示实际发布时间）；前端两条链路共用同一 markdown 渲染管线。
 * <p>
 * reactions = 表态徽标（只含 count&gt;0，一人一票），列表页每次请求按整页 id 批量聚合，
 * 无 N+1、无缓存（见 BulletinReactionRepository 注释）。
 * <p>
 * viewCount = 累计查看人数（信息流展示即计口径，见 BulletinViewService；按整页 id
 * 一次 IN + GROUP BY 聚合，无 N+1、无缓存）。
 * <p>
 * 仍<b>不含 read 字段</b>：快讯域有意不做已读回执（不进红点，新鲜度由时间戳表达）。
 *
 * @param id        快讯 id
 * @param excerpt   内容摘要（**派生只读**：本域无标题字段，见 BulletinExcerpt；仅供分享卡片标题 / aria 上下文）
 * @param content   Markdown 原文（前端 towxml 渲染；图片/视频走 markdown 原生语法）
 * @param city      城市标签（可空）
 * @param venueId   关联门店 id（可空）
 * @param publishAt 生效时间（定时发布 = 计划生效时刻；列表节奏锚点）
 * @param createdAt 创建时间（publishAt 缺失时的展示兜底）
 * @param reactions 表态徽标（count&gt;0，按人数降序；无表态时为空列表，非 null）
 * @param viewCount 累计查看人数（信息流展示即计，无展示时 0）
 */
public record BulletinFeedItemResponse(
        Long id,
        String excerpt,
        String content,
        String city,
        Long venueId,
        LocalDateTime publishAt,
        LocalDateTime createdAt,
        List<BulletinReactionBadge> reactions,
        Long viewCount
) {}
