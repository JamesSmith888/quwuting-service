package org.quwuting.quwutingservice.points.dto;

/**
 * 目标收到礼物聚合条目（"收获的支持"礼物墙，2026-08-12 礼物化）。
 * 前端按 giftCode 查镜像字典渲染图片（gifts.ts），count = 收到件数；
 * 展示按 count 降序、同数按 code 声明序（后端排序稳定，前端零逻辑）。
 */
public record GiftCountResponse(
        /** 礼物 code（GiftCatalog 枚举名） */
        String giftCode,
        /** 该礼物收到件数 */
        long count
) {}
