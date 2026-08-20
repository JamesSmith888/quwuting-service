package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 门店照片上传请求体（POST /venues/{id}/photos，登录即可）。
 * 与 AddDancerPhotosRequest 同构：URL 列表为前端直传 Supabase Storage 后的公开
 * 访问地址（经 /storage/upload-token 签发），后端落库前必须过 ImageContentValidator
 * 内容级校验（08-12 安全约定：图片 URL 落库字段必须挂载校验）。
 * 单次数量上限与前端 image-upload maxCount=9 对齐，后端独立校验防绕过。
 */
public record AddVenuePhotosRequest(
        @NotEmpty(message = "请至少选择一张照片")
        @Size(max = 9, message = "单次最多上传 9 张照片")
        List<@Size(max = 500) String> urls
) {
}
