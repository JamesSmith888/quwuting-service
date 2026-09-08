package org.quwuting.quwutingservice.venue.dto.response;

import java.util.List;

/**
 * 门店别名批量导入响应体（POST /admin/venue-aliases/batch-import，2026-09-08）。
 * <p>
 * 逐条独立提交、单条失败不拖累整批（对齐 venue-sync batch-create 口径）：
 * <ul>
 *   <li>total    = 请求条目数；</li>
 *   <li>imported = 新增 + 软删行复活数；</li>
 *   <li>skipped  = 幂等跳过数（同店同名的有效行已存在 / 别名与门店主名同名无意义）；</li>
 *   <li>failed   = 单条失败明细（门店不存在、已删、非法输入等，带原因）。</li>
 * </ul>
 */
public record BatchImportVenueAliasResponse(
        int total,
        int imported,
        int skipped,
        List<FailedItem> failed
) {

    /**
     * 单条失败明细。
     *
     * @param index   在请求 items 中的下标（0 起）
     * @param venueId 门店 ID（解析失败时可能为 null）
     * @param alias   别名原文（trim 前）
     * @param error   失败原因
     */
    public record FailedItem(int index, Long venueId, String alias, String error) {}
}
