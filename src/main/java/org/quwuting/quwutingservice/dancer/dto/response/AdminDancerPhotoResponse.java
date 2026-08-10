package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;

import java.time.LocalDateTime;

/**
 * 管理端照片审核列表项（GET /admin/dancers/photos，仅 ADMIN）。
 * 舞伴已软删时 dancerNickname 由服务层回退占位（与 AdminDancerResponse 同规则）。
 */
public record AdminDancerPhotoResponse(
        Long id,
        String url,
        DancerPhotoStatus status,
        Long dancerId,
        /** 舞伴昵称（LEFT JOIN qwt_dancers；已软删回退"未知舞伴"） */
        String dancerNickname,
        String dancerCity,
        String dancerAvatarUrl,
        /** 上传时间（"yyyy-MM-dd HH:mm:ss"，审核列表按此倒序） */
        LocalDateTime createdAt
) {}
