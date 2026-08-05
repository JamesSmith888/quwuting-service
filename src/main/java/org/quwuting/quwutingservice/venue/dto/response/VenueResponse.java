package org.quwuting.quwutingservice.venue.dto.response;

import com.fasterxml.jackson.annotation.JsonFormat;
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
        /** 下午场营业时间，如 "14:00 - 18:00"，无数据时为 null */
        String afternoonHours,
        /** 晚场营业时间，如 "19:00 - 02:00"，无数据时为 null */
        String eveningHours,
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
         * Top Reaction 徽标（最多4个，按近30天热度排序，count=0 的不展示），替代原 tagLikeCounts。
         * 含 reactedByMe（当前用户是否已参与）——刻意打破"列表层不含个人状态"的惯例，
         * 见 {@link org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge} 类注释。
         */
        List<ReactionBadge> topReactions,
        Integer sortWeight,
        /** 是否为城市内热门场所（城市内热度排名前 20%，至少 1 家），驱动列表卡片视觉高亮 */
        boolean isHot,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createdAt,
        /** 数据最后更新时间（用户可见的时效性信号，用于判断信息可靠度） */
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updatedAt
) {}
