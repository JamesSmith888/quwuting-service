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
 * <p>
 * 2026-08-14 扩展：<b>blurUrls</b> = 与原图一一对应的模糊图 URL（前端 canvas 离屏
 * 降采样生成，收费照片详情页"模糊可见轮廓"占位用，见 AGENTS.md「积分解锁」）。
 * 长度须与 urls 一致（index 对齐）；模糊图生成失败时允许缺省（null/空列表 →
 * 详情页未解锁回退纯锁占位）。
 */
public record AddDancerPhotosRequest(
        @NotEmpty(message = "请至少选择一张照片")
        @Size(max = MAX_PHOTOS_PER_BATCH, message = "单次最多上传" + MAX_PHOTOS_PER_BATCH + "张照片")
        List<@Valid @Size(max = 500, message = "照片地址过长") String> urls,

        /** 模糊图 URL 列表（与 urls 按 index 一一对应；可缺省） */
        @Size(max = MAX_PHOTOS_PER_BATCH, message = "模糊图数量超出范围")
        List<@Valid @Size(max = 500, message = "模糊图地址过长") String> blurUrls
) {
    /** 单次批量上传上限（与前端 image-upload 默认 maxCount 一致，防一次塞满全表） */
    public static final int MAX_PHOTOS_PER_BATCH = 9;
}
