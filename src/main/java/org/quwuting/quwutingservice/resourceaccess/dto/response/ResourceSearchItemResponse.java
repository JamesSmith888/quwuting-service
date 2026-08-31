package org.quwuting.quwutingservice.resourceaccess.dto.response;

/**
 * 管理端资源搜索条目（2026-08-31：新增授权/协作页的资源模糊搜索数据源）。
 * 轻量投影 = 列表点选所需最小字段；subLabel 为次级标识行
 * （门店 = "城市 · 区域"，舞伴 = 城市），与列表行 meta 口径一致。
 */
public record ResourceSearchItemResponse(
        Long id,
        String name,
        String city,
        String imageUrl,
        String subLabel
) {}
