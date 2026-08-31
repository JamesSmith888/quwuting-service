package org.quwuting.quwutingservice.venuesync.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * 管线执行请求（2026-08-31，Web 管理后台「拉取数据」）。
 *
 * @param source         渠道 id（默认 xianbao360，见管线 --list-sources）
 * @param refreshSnapshot 是否连库刷新门店快照（默认 false 用缓存快照，更快）
 */
public record PipelineRunRequest(
        @NotBlank(message = "渠道不能为空") String source,
        Boolean refreshSnapshot) {

    public PipelineRunRequest {
        if (source == null || source.isBlank()) {
            source = "xianbao360";
        }
    }
}
