package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 设置 / 撤销「舞讯推断永久豁免」请求（POST /admin/venue-sync/guard/exempt，2026-09-14，V25）。
 * <p>
 * 使用场景：某家门店反复被人工修正（说明问题不在时间维度——该店不在舞讯覆盖范围，
 * 或被舞讯系统性漏报），继续加长人工锁是打补丁；此接口把「周期性返工」升级为
 * **一次性事实声明**：本店不参与舞讯白名单 / 未上榜差集推断，直到人工撤销。
 *
 * @param venueIds 门店 ID 列表（1~100）
 * @param exempt   true = 设为豁免；false = 撤销豁免（恢复参与舞讯推断）
 * @param note     人工说明（如「舞讯从未收录该店」）；null / 空 = 保留原备注
 */
public record VenueGuardExemptRequest(
        @NotEmpty(message = "门店 ID 列表不能为空")
        @Size(max = 100, message = "单次最多 100 家门店")
        List<Long> venueIds,

        boolean exempt,

        @Size(max = 200, message = "备注最长 200 字符")
        String note
) {}
