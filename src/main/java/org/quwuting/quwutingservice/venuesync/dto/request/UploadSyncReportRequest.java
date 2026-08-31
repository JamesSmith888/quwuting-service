package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 管线报告上报请求（POST /admin/venue-sync/reports）。
 * <p>
 * 调用方 = quwuting-ops/venue-opening 管线（--upload-report），
 * summary/items 为结构化对象，由服务层序列化后存 JSON 文本。
 * 幂等：同渠道同报告日覆盖。
 */
public record UploadSyncReportRequest(
        @NotNull(message = "reportDate 不能为空")
        LocalDate reportDate,

        @NotBlank(message = "sourceId 不能为空")
        @Size(max = 50, message = "sourceId 最长 50 字符")
        String sourceId,

        @Size(max = 100, message = "sourceLabel 最长 100 字符")
        String sourceLabel,

        @Size(max = 500, message = "url 最长 500 字符")
        String url,

        @NotNull(message = "summary 不能为空")
        Map<String, Object> summary,

        @NotNull(message = "items 不能为空")
        List<Map<String, Object>> items
) {}
