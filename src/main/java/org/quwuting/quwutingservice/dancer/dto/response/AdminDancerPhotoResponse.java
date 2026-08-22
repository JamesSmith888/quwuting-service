package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerPhotoKind;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;

import java.time.LocalDateTime;

/**
 * 管理端照片审核列表项（GET /admin/dancers/photos，仅 ADMIN）。
 * 舞伴已软删时 dancerNickname 由服务层回退占位（与 AdminDancerResponse 同规则）。
 * 2026-08-22 视频扩展：kind/coverUrl/durationSeconds 供审核端区分媒体类型——
 * 视频卡片以封面帧图预览（点击播放弹层），照片卡片展示原图。
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
        LocalDateTime createdAt,
        /** 媒体类型（2026-08-22：PHOTO 照片 / VIDEO 短视频） */
        DancerPhotoKind kind,
        /** 视频封面帧图 URL（仅 kind=VIDEO 有值；可空 = 回退虚焦占位） */
        String coverUrl,
        /** 视频时长（秒，仅 kind=VIDEO 有值；零值 = 未知不展示） */
        int durationSeconds
) {}
