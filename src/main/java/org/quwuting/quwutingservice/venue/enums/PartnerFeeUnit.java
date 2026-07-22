package org.quwuting.quwutingservice.venue.enums;

/**
 * 舞伴费用计量单位。
 * MINUTE — 按时长计费（江浙沪常见：5分钟30元）；
 * SONG — 按曲数计费（西安等地连曲模式：3曲30元，每曲约3分钟）。
 * <p>
 * 与 TicketType 同理：用枚举区分计费形态，条件差异（如"5点前/后"）
 * 由 PartnerFeeEntry.label 自由文本描述，不硬编码时段逻辑。
 */
public enum PartnerFeeUnit {
    MINUTE,
    SONG
}
