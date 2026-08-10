package org.quwuting.quwutingservice.dancer.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 舞伴本人上传相册照片请求体（POST /dancers/{id}/photos）。
 * <p>
 * 照片一律先审后发（插入即 PENDING），普通用户不可调用（服务层校验 createdBy 匹配或管理员）。
 * url 来自前端经 /storage/upload-token 直传 Supabase 后返回的公开地址。
 */
public record AddDancerPhotosRequest(
        @NotEmpty(message = "请至少选择一张照片")
        @Size(max = MAX_PHOTOS_PER_BATCH, message = "单次最多上传" + MAX_PHOTOS_PER_BATCH + "张照片")
        List<@Valid @Size(max = 500, message = "照片地址过长") String> urls
) {
    /** 单次批量上传上限（与前端 image-upload 默认 maxCount 一致，防一次塞满全表） */
    public static final int MAX_PHOTOS_PER_BATCH = 9;
}
