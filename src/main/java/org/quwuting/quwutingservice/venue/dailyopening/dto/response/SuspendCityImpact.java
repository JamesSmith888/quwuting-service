package org.quwuting.quwutingservice.venue.dailyopening.dto.response;

/**
 * 批量置「暂停营业」对单个城市的影响面（2026-10-01，影响面熔断，见
 * {@code SuspendBlastRadiusGuard}）。
 *
 * @param city         城市（与门店 city 字面一致；{@code confirmedCities} 须原样回传）
 * @param openCount    该城当前营业中门店数（分母）
 * @param toSuspend    本批计划暂停数（已扣除门禁跳过、非 OPEN、重复条目）
 * @param ratioPercent 占比（四舍五入百分比）
 * @param tripped      是否触发熔断
 * @param confirmed    调用方是否已在 confirmedCities 中确认放行
 */
public record SuspendCityImpact(
        String city,
        long openCount,
        int toSuspend,
        int ratioPercent,
        boolean tripped,
        boolean confirmed
) {}
