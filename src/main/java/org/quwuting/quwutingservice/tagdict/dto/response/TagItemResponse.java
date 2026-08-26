package org.quwuting.quwutingservice.tagdict.dto.response;

/**
 * 标签字典条目响应（id 稳定关联 + 展示名 + 说明文案 + 展示配色）。
 * 详情/列表/字典三处共用：详情与列表的 profileTags 即本结构列表（长按/点击弹说明
 * 前端直接用 description，无需二次拉字典）；编辑页表单的可选列表同构。
 * color（2026-08-26，标签级配色）：hex 展示色，可空（空 = 默认样式）。
 */
public record TagItemResponse(
        Long id,
        /** 展示名（标签 chip 文案） */
        String text,
        /** 说明文案（长按/点击标签弹层；空串 = 无说明） */
        String description,
        /** 展示配色（hex，如 #E63946；空 = 默认样式；2026-08-26 标签级配色） */
        String color
) {}
