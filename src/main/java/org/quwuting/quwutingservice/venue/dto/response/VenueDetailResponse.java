package org.quwuting.quwutingservice.venue.dto.response;

/**
 * 场所详情响应体（GET /venues/{id}）。
 * <p>
 * 相比列表接口的 {@link VenueResponse}，额外携带：
 * <ul>
 *   <li>canManage — 当前请求用户是否拥有该门店的管理权（认领人或平台管理员），
 *       由后端基于软鉴权上下文计算，匿名请求恒为 false。前端仅用于控制管理入口展示，
 *       安全边界在后端写操作接口。</li>
 *   <li>postCount — 动态总数，用于前端 Tab 标签计数展示。</li>
 * </ul>
 */
public record VenueDetailResponse(
        VenueResponse venue,
        boolean canManage,
        long postCount
) {}
