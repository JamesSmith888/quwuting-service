package org.quwuting.quwutingservice.dancer.dto.request;

/**
 * 记录舞伴浏览请求体（POST /dancers/{id}/view）。
 * <p>
 * source 为浏览来源（LIST=列表进入 / SHARE=分享卡片打开 / SEARCH=搜索结果进入 /
 * OTHER=其他），可空——旧版本客户端不传该字段，后端兜底为 OTHER
 * （见 DancerViewService.normalizeSource；语义与门店 RecordViewRequest 一致）。
 */
public record RecordDancerViewRequest(
        String source
) {
}
