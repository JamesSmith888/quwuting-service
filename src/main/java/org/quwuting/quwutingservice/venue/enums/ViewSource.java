package org.quwuting.quwutingservice.venue.enums;

/**
 * 场所浏览来源（qwt_venue_views.source 列，2026-08-13 新增）。
 * <ul>
 *   <li>LIST — 列表页进入（首页/收藏页等列表卡片点击进入详情页，前端以「列表快照
 *       takeVenueSnapshot 命中」为判据）</li>
 *   <li>SHARE — 分享卡片打开（分享路径携带 share_from 归因参数，前端以 onLoad
 *       解析到 share_from 为判据；与 qwt_venue_shares 的 OPEN 事件同源）</li>
 *   <li>OTHER — 其他来源（搜索、收藏页无快照直进、深链、旧版本客户端未上报等，兜底默认）</li>
 * </ul>
 * 语义约定：
 * <ul>
 *   <li>已登录用户按 (venue_id, user_id, view_date) 按天去重，upsert 保留「首次来源」——
 *       归因语义上首次进入路径最有分析价值（先列表后分享，当天记 LIST）；</li>
 *   <li>匿名用户不去重，每次访问均按当次来源记录（60s IP 频控兜底）；</li>
 *   <li>枚举类列不加 CHECK 约束（项目约定），非法值由应用层防御（见
 *       {@link org.quwuting.quwutingservice.venue.service.VenueViewService}）。</li>
 * </ul>
 */
public enum ViewSource {
    LIST,
    SHARE,
    OTHER
}
