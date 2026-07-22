package org.quwuting.quwutingservice.venue.enums;

/**
 * 门票类型。
 * FIXED — 固定票价（price 必填）；FREE — 免票（price 无意义）。
 * 时段免票等条件通过 TicketEntry.label 描述（如"下午4点前"），不硬编码时段逻辑。
 */
public enum TicketType {
    FIXED,
    FREE
}
