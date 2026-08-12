package org.quwuting.quwutingservice.points.dto;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 礼物赠送者条目（2026-08-12 礼物墙点击弹层：赠送某礼物的用户列表）。
 * <p>
 * 弹层展示 = 头像 + 昵称（用户公开资料字段）+ 赠送时间（今天/昨天/具体时间，
 * 前端按 lastGiftedAt 派生）；数据超过弹层上限时经「查看全部」链接进入
 * 独立详情页（pages/gift-givers）。点击行跳转用户公开主页（GET /users/{id}）。
 * <p>
 * 聚合口径：按用户聚合（同一用户多次赠送同一礼物 = 一行），count = 累计件数，
 * lastGiftedAt = 该用户最近一次赠送时间。排序：件数降序、最近赠送时间降序
 * （后端稳定排序，前端零逻辑）。
 */
public record GifterResponse(
        /** 赠送者用户 ID（跳转用户公开主页的键） */
        Long userId,
        /** 昵称（用户公开资料；用户已删/无昵称时后端回退占位） */
        String nickname,
        /** 头像 URL（用户公开资料；未设置时前端渲染昵称首字符占位） */
        String avatarUrl,
        /** 该用户赠送该礼物的累计件数 */
        long count,
        /** 该用户最近一次赠送该礼物的时间（弹层/详情页行展示源） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
        LocalDateTime lastGiftedAt
) {}
