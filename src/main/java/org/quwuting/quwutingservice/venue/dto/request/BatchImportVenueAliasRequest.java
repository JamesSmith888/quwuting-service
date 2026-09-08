package org.quwuting.quwutingservice.venue.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 门店别名批量导入请求体（POST /admin/venue-aliases/batch-import，2026-09-08）。
 * <p>
 * 调用方 = 舞讯采集 Skill / Web 管理后台批量补录——把舞讯比对沉淀的别名
 * （错别字/异体字/曾用名/圈内涵称）一次性灌入 {@code qwt_venue_aliases}。
 * 容器级校验用注解（整体非法直接 400）；<b>元素级校验在 Service 逐条做</b>——
 * 单条脏数据（门店不存在/别名与主名同名）记 failed/skipped，不影响同批其他条目。
 * <p>
 * 上限 500 条/批：单日舞讯沉淀别名量级通常个位数到几十条，500 已留足余量
 * （对齐 export 单页上限，防误传全量刷库）。
 */
public record BatchImportVenueAliasRequest(
        @NotEmpty(message = "别名导入列表不能为空")
        @Size(max = 500, message = "单次最多导入 500 条别名")
        List<@Valid UpsertVenueAliasRequest> items
) {}
