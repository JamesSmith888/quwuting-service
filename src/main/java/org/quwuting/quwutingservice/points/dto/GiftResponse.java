package org.quwuting.quwutingservice.points.dto;

/**
 * 赠送响应（POST /points/gift，2026-08-12 礼物化）——服务端确认后的绝对余额、
 * 赠送流水 id 与礼物 code/name（前端 toast 展示"已送出小熊"零本地映射）。
 */
public record GiftResponse(
        /** 赠送后的积分余额（服务端权威值，前端直接采用） */
        long balance,
        /** 赠送流水 id */
        long giftId,
        /** 所赠礼物 code（GiftCatalog 枚举名） */
        String giftCode,
        /** 所赠礼物中文名（后端唯一事实源下发，前端直接渲染） */
        String giftName
) {}
