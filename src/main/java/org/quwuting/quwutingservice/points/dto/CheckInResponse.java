package org.quwuting.quwutingservice.points.dto;

/**
 * 打卡响应（POST /points/check-in）。
 * checkedIn = 本次是否新增打卡（false = 今日已打卡，幂等返回，不重复发分）。
 */
public record CheckInResponse(boolean checkedIn, int reward, long balance) {}
