package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;

import java.time.LocalDateTime;
import java.util.List;

public record VenueResponse(
        Long id,
        String name,
        VenueStatus status,
        String statusDisplay,
        String imageUrl,
        /** 相册图片 URL 列表，无数据时为空列表 */
        List<String> photos,
        String description,
        String city,
        String district,
        String address,
        Double longitude,
        Double latitude,
        /**
         * 营业时段列表（时段名 + 起止时间，跨天时段 close<open 表示次日结束），
         * 无数据时为空列表
         */
        List<BusinessHoursEntry> businessHours,
        /** 门票规则列表，无数据时为空列表 */
        List<TicketEntry> tickets,
        /** 舞伴费用阶梯，无数据时为空列表 */
        List<PartnerFeeEntry> partnerFees,
        String contactPhone,
        String wechatQr,
        List<String> tags,
        /** 系统默认标签子集（tags 中索引 0..N-1），前端据此区分不可删除的系统标签与可删除的自定义标签 */
        List<String> defaultTags,
        /**
         * Reaction 徽标（**完整展示**：所选窗口内所有用户点击过的全部表情，count>0 的
         * 一个不落，按所选窗口计数降序，**不做任何截断**——需求 2026-08-09：取所有用户
         * 的所有已点击表情全部展示），替代原 tagLikeCounts。
         * 默认窗口 = 近7天（列表页可经 {@code window} 参数切换；收藏列表 / 详情基础响应
         * 固定近7天）；统计口径 = 所选窗口内**所有用户**对该门店的 reaction 数据。
         * 个人参与状态（reactedByMe）仅作徽标标注属性、不参与集合构成，见
         * {@link org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge}
         * 类注释与 {@code VenueReactionService#buildTopBadgesFromCounts} javadoc。
         */
        List<ReactionBadge> topReactions,
        Integer sortWeight,
        /**
         * 累计浏览量（全量历史口径，2026-08-12 新增）：qwt_venue_views 行数（按天按来源去重
         * PV 口径，与 VenueHeatResponse.viewCount30d 同源同口径的全量版，见
         * VenueResponseMapper 四参重载 javadoc）。驱动列表卡片底部「👁 浏览数」展示。
         * 卡片展示场景（列表/收藏/详情）传真实值；无展示语义场景（创建/编辑表单回显）为 0。
         */
        long viewCount,
        /** 是否为城市内热门场所（城市内热度排名前 20% 且热度分 ≥ 配置门槛，
         *  见 AGENTS.md「热门场所标记」），驱动列表/收藏卡片视觉高亮 */
        boolean isHot,
        /**
         * 今晚热度角标文案（2026-08-29，docs/agents/27-venue-crowd-report.md）：
         * 中性「N人报过」（最近 6 小时窗口内独立上报人数 ≥ 3 才生成），驱动列表卡片
         * 标签行行首 teal 胶囊（公共面克制：不携带档位词/冷清，防误伤与商家刷量）；
         * 无展示语义场景（详情/编辑/创建回显）为 null。
         */
        String crowdBadgeText,
        /**
         * 今晚热度「最新上报」行文案（2026-08-29，docs/agents/27-venue-crowd-report.md）：
         * 克制版「{相对时间} · {标识}舞友上报」（如「2 分钟前 · 资深舞友上报」）——
         * 窗口内有上报即生成，驱动列表卡片底部实时动态行（公共面克制：不显示档位词
         * 防商家自报贴标签，不公开昵称只用信任标识，见 CrowdReportService#latestTextsByVenue）；
         * 与 crowdBadgeText（≥3 人共识人数）语义解耦互补；无展示语义场景（详情/编辑/
         * 创建回显）为 null。
         */
        String crowdLatestText,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        /** 数据最后更新时间（用户可见的时效性信号，用于判断信息可靠度） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt,
        /**
         * 是否存在未读的关注门店状态变化提醒（2026-09-01「收藏即关注」，见
         * {@code FavoriteService#getFavoriteVenues}）：该门店最近一次营业状态变更后
         * 用户尚未打开过详情页（未读 VENUE_STATUS_CHANGED 站内信 > 0）。驱动收藏列表
         * 卡片「状态更新」角标——用户心智「收藏 = 在意的店」，状态变了要主动提醒；
         * 打开门店详情（后端按店批量已读）后随收藏列表重拉自动收敛。仅收藏列表
         * 场景下发真实值；其他场景（城市列表/详情/编辑回显）恒为 false——状态角标
         * 是收藏语义的提醒，城市列表不做（同 crowdBadgeText 仅列表场景的注入边界）。
         */
        boolean statusChanged
) {}
