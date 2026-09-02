package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 门店名称联想建议项（2026-09-02 搜索增强，GET /venues/suggest，首页搜索框联想下拉）。
 * <p>
 * 轻量投影：联想行只需 id / 名称 / 位置 / 状态——与列表 {@link VenueResponse} 解耦
 * （联想不承载徽标 / 热度 / 照片 / 浏览量等重型组装，一次直查返回，见
 * VenueService#listVenueSuggestions）。命中词的展示高亮由前端本地按输入词渲染，
 * 服务端不下发命中位置（数据规模数百级，无检索服务端高亮需求）。
 *
 * @param name     门店正式名（用户点选后作为关键词整串搜索）
 * @param district 区县，缺失为空串
 * @param status   营业状态存储态（OPEN/SUSPENDED/CLOSED…，前端联想行状态徽标用）
 */
public record VenueSuggestResponse(
        long id,
        String name,
        String city,
        String district,
        String status
) {}
