package org.quwuting.quwutingservice.tagdict.dto.request;

import jakarta.validation.constraints.Pattern;

/**
 * 管理员更新标签字典条目请求体（PUT /admin/tag-dict/{id}，仅 ADMIN；2026-08-26）。
 * 当前仅支持展示配色（color）：hex 格式（#RRGGBB）、空串 = 清除配色、
 * null = 不修改。契约与前端 constants/tag-colors.ts 的 hex 校验一致。
 */
public record UpdateTagDictRequest(
        /** 展示配色（hex #RRGGBB；空串 = 清除配色；null = 不修改） */
        @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$", message = "颜色格式应为 #RRGGBB")
        String color
) {}
