package org.quwuting.quwutingservice.venuecrowd.dto.request;

/**
 * 门店热度上报请求（2026-08-29，docs/agents/27-venue-crowd-report.md）。
 * <p>
 * 快捷按钮枚举载荷（零自由文本——个人主体 UGC 文本审核红线第四次同构解）：
 * <ul>
 *   <li>{@code femaleLevel}（必填，1-4）：在店舞伴（女）数量档位（主信号）；</li>
 *   <li>{@code maleLevel}（选填，1-3）：男客密度档位（次信号），null = 跳过。</li>
 * </ul>
 * 枚举值由服务端 {@code CrowdFemaleLevel.of / CrowdMaleLevel.of} 校验，越界拒绝。
 */
public record SubmitCrowdReportRequest(
        int femaleLevel,
        Integer maleLevel
) {
}
