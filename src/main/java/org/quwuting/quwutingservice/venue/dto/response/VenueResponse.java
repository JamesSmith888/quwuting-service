package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.dto.VenueMatchHint;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.enums.VenueType;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;

import java.time.LocalDateTime;
import java.util.List;

public record VenueResponse(
        Long id,
        String name,
        VenueStatus status,
        String statusDisplay,
        /**
         * 门店类型（2026-09-13 新增，V24）：舞厅 / KTV / 歌友会，驱动列表快捷筛选与
         * 详情页地址展示粒度。前端据此分支渲染：<b>城市级类型（歌友会）只展示城市</b>，
         * 不渲染地址行与导航，改为「联系获取具体地址」入口——本字段是前端判断
         * "该店地址是否公开"的唯一依据（禁止前端硬编码类型名做判断）。
         */
        VenueType venueType,
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
        boolean statusChanged,
        /**
         * 门店报告「最新上报」行文案（2026-09-04，docs/agents/07-feedback-and-reporting.md）：
         * 「{相对时间} · {类型} · 舞友上报」（如「2 分钟前 · 暂停营业 · 舞友上报」，
         * 见 StatusReportLatestService#latestTextsByVenue）——公示期 2 天内每店最新一条
         * 公示中报告（活跃口径与详情页公告条同源），驱动列表卡片「最新上报」行与今晚热度
         * 文案（crowdLatestText）共用同一行控件轮播展示（2026-09-04 用户拍板，推翻同日
         * 「中性角标 reportBadgeText」方案）。无公示中报告 / 无展示语义场景
         * （详情/编辑/创建回显）为 null。
         */
        String statusLatestText,
        /**
         * 本次搜索的「匹配解释」载荷（2026-09-16 通用化，取代原 {@code matchedAlias} 单一载体；
         * docs/agents/38-venue-aliases.md §4.1）。用户用一个词搜到了店，卡片必须在可见位置
         * 复现他输入的那个词，否则「匹配不可自证」：搜不到用户以为平台没收录，搜到了却在
         * 卡片上找不到自己输的词，用户会认为平台数据错了。
         * <p>
         * <b>为什么是「卡片上不可见的载体」才进来</b>：解释行的唯一职责是补上卡片上缺失的
         * 那段文本。命中 {@code name} 走标题高亮、{@code city}/{@code district} 走位置行、
         * {@code tags} 走标签行——这些载体卡片上看得见，前端对既有行染色即可自证，
         * 再叠一行解释纯属冗余（详见 {@link org.quwuting.quwutingservice.venue.enums.VenueMatchField}）。
         * <p>
         * <b>语义边界（严禁扩散）</b>：
         * <ul>
         *   <li><b>单值 + 条件下发</b>：仅当本次 keyword 确实命中了该店某个不可见载体时非 null，
         *       按 ALIAS → SYNC_ALIAS → ADDRESS → DESCRIPTION 优先级取第一个；无 keyword /
         *       命中来源全部可在卡片上自证时为 null——非命中门店<b>零带宽、零布局变化</b>
         *       （前端条件渲染，条件行同信号行不占骨架屏）；</li>
         *   <li><b>只补展示、不改结果集</b>：命中集由 KW_MATCH 决定，本字段由 Service 层对当页
         *       门店内存二次判定装配（见 VenueService#loadMatchHints）——判定不中的最坏后果
         *       仅为「该店少一行解释」，绝不漏店；</li>
         *   <li><b>{@code text} 为 null 是有效值</b>：表示该载体确实命中、但原文不可公开展示
         *       （城市级地址类型——歌友会——的地址被 {@code VenueResponseMapper} 脱敏）。
         *       前端必须据此渲染「需联系获取」，<b>禁止</b>整行不渲染；</li>
         *   <li><b>注入边界同 isHot / crowdBadgeText 先例</b>：仅列表搜索场景传真实值；收藏列表/
         *       详情/编辑回显场景无 keyword 上下文，传值即语义错误（恒 null）。</li>
         * </ul>
         */
        VenueMatchHint matchedHint
) {}
