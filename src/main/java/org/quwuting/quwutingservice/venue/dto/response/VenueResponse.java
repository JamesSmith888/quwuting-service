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
         * Top Reaction 徽标（按所选窗口计数排序，最多 4 个 + 当前用户已参与的 code 不受截断，
         * count=0 的不展示），替代原 tagLikeCounts。
         * 含 reactedByMe（当前用户是否已参与）——刻意打破"列表层不含个人状态"的惯例，
         * 见 {@link org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge} 类注释。
         * 2026-08-08 契约变更：用户已参与的 code 恒在徽标内（"点击即知是否已参与"），
         * 即使其窗口计数低于 Top 4 截断线——截断会使用户刚参与的 chip 在列表重取后消失，
         * 根因见 AGENTS.md「Reaction 快速反馈系统 → 跨页一致性同步」。
         */
        List<ReactionBadge> topReactions,
        Integer sortWeight,
        /** 是否为城市内热门场所（城市内热度排名前 20% 且热度分 ≥ 配置门槛，
         *  见 AGENTS.md「热门场所标记」），驱动列表/收藏卡片视觉高亮 */
        boolean isHot,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        /** 数据最后更新时间（用户可见的时效性信号，用于判断信息可靠度） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt
) {}
