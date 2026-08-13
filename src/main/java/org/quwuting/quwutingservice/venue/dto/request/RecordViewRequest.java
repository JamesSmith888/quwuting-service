package org.quwuting.quwutingservice.venue.dto.request;

/**
 * 记录场所浏览请求体（POST /venues/{id}/view）。
 * <p>
 * source 为浏览来源（LIST=列表进入 / SHARE=分享卡片打开 / OTHER=其他），
 * 可空——旧版本客户端不传该字段，后端兜底为 OTHER（见 VenueViewService.normalizeSource）。
 */
public record RecordViewRequest(
        String source
) {
}
