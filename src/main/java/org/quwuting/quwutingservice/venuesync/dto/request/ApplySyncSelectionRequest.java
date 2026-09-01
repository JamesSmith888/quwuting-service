package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 「可直接更新」tab 专属批量写库请求（2026-09-01）。
 * <p>
 * 语义：仅提交 venue 命中且 confidence ∈ {EXACT, ALIAS} 的选中条目（与批量 apply
 * 提交集一致，forceReversal=false——低置信 CONTAINED/FUZZY 仍走 apply-item 单条人工放行）。
 * 前端只把「需要更新」的条目 venue 传上来（快照未落库或可反转），避免整报告重写已写条目。
 *
 * @param venueIds 平台门店 id 列表（条目 venue.venue_id；同报告内快照唯一键 =
 *                 venue_id + report_date + source_id，重复 id 自动去重）
 */
public record ApplySyncSelectionRequest(
        @NotEmpty(message = "venueIds 不能为空")
        @Size(max = 500, message = "单次最多提交 500 家门店")
        List<@NotNull(message = "venueId 不能为空") Long> venueIds
) {
}
