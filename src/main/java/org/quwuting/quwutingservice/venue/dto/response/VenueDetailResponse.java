package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.LocalDateTime;

/**
 * 场所详情响应体（GET /venues/{id}）。
 * <p>
 * 相比列表接口的 {@link VenueResponse}，额外携带：
 * <ul>
 *   <li>canManage — 当前请求用户是否拥有该门店的管理权（认领人或平台管理员），
 *       由后端基于软鉴权上下文计算，匿名请求恒为 false。前端仅用于控制管理入口展示，
 *       安全边界在后端写操作接口。</li>
 *   <li>postCount — 动态总数，用于前端 Tab 标签计数展示。</li>
 *   <li>hasMyStatusReport — 当前用户是否已对此场所有活跃的状态上报。
 *       个人状态字段（与 canManage 同性质），实时查询、不缓存——
 *       VenueDetailResponse 不经过 @Cacheable，每次请求实时计算。
 *       驱动详情页"已报告·补充 / 撤销"与"报告暂停营业"的 UI 切换。</li>
 *   <li>statusUpdatedAt — 营业状态字段的最近一次变更时间（nullable）。
 *       唯一事实源 = {@code qwt_venue_status_logs} 最新一条的 createdAt
 *       （场所创建时的初始日志即起点），与 {@link VenueResponse#updatedAt()}
 *       （整个场所记录任意字段编辑都刷新）语义不同——营业状态详情弹窗
 *       「营业状态更新」展示的是前者。</li>
 * </ul>
 */
public record VenueDetailResponse(
        VenueResponse venue,
        boolean canManage,
        long postCount,
        boolean hasMyStatusReport,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime statusUpdatedAt
) {}
