package org.quwuting.quwutingservice.points.dto;

/**
 * 赠送响应（POST /points/gift）——服务端确认后的绝对余额与赠送流水 id。
 */
public record GiftResponse(long balance, long giftId) {}
