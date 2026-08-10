package org.quwuting.quwutingservice.dancer.dto.response;

import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;

import java.time.LocalDateTime;

/**
 * 舞伴相册照片条目（详情页/编辑页数据源）。
 * <p>
 * 可见性已在服务层过滤：非本人请求仅返回 PUBLIC；本人/管理员返回全部状态
 * （本人编辑页据 status 渲染「待审核/已公开/已驳回」徽标并可删除）。
 * status 前端渲染徽标用（非本人视角恒 PUBLIC）。
 */
public record DancerPhotoResponse(
        Long id,
        String url,
        DancerPhotoStatus status,
        int sortOrder,
        LocalDateTime createdAt
) {}
