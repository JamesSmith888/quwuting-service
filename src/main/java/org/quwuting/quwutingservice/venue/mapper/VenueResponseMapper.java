package org.quwuting.quwutingservice.venue.mapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.config.VenueDefaultsConfig;
import org.quwuting.quwutingservice.venue.dto.BusinessHoursEntry;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.List;

/**
 * Venue 实体 → VenueResponse DTO 转换器。
 * 独立为组件供 VenueService、FavoriteService 等复用，避免映射逻辑重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VenueResponseMapper {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<TicketEntry>> TICKET_LIST = new TypeReference<>() {};
    private static final TypeReference<List<PartnerFeeEntry>> PARTNER_FEE_LIST = new TypeReference<>() {};
    private static final TypeReference<List<BusinessHoursEntry>> BUSINESS_HOURS_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;
    private final VenueDefaultsConfig defaultsConfig;

    /** 创建/编辑表单回显场景（无徽标、无热门标记、无浏览量；卡片展示场景勿用，见三参重载 javadoc） */
    public VenueResponse toResponse(Venue v) {
        return toResponse(v, Collections.emptyList(), false, 0L);
    }

    /**
     * @param topReactions Top Reaction 徽标列表，列表页/详情页批量查询后传入；
     *                      无需展示的场景（新建/编辑表单回显）传空 List
     *
     * <p><b>注意：本重载 isHot 恒为 false、viewCount 恒为 0</b>——仅限"热门标记/浏览量
     * 无展示语义"的场景（创建/编辑表单回显、详情页基础响应）。任何渲染 venue-card 卡片
     * 的列表场景（城市列表/收藏列表）必须调用三参/四参重载传入真实值，禁止本重载用于
     * 卡片展示（历史缺陷：收藏列表热门标签恒不展示，见三参重载 javadoc）。
     */
    public VenueResponse toResponse(Venue v, List<ReactionBadge> topReactions) {
        return toResponse(v, topReactions, false, 0L);
    }

    /**
     * @param topReactions Top Reaction 徽标列表
     * @param isHot        是否为城市内热门场所（城市内 top 20% 且热度分 ≥ 门槛，
     *                     见 {@link org.quwuting.quwutingservice.venue.service.VenueLookupService#getHotVenueIds}）
     *
     * <p><b>卡片展示场景（列表/收藏等任何渲染 venue-card 的接口）必须走本重载或四参重载</b>，
     * 传入真实 isHot——历史缺陷：FavoriteService 误用双参重载（默认 isHot=false），
     * 导致收藏列表热门标签恒不展示。双参/单参重载仅限"热门标记无展示语义"的
     * 场景（创建/编辑表单回显、详情页基础响应）。
     */
    public VenueResponse toResponse(Venue v, List<ReactionBadge> topReactions, boolean isHot) {
        return toResponse(v, topReactions, isHot, 0L);
    }

    /**
     * 完整四参重载（2026-08-12 新增 viewCount）。
     *
     * @param topReactions Top Reaction 徽标列表
     * @param isHot        是否为城市内热门场所（城市内 top 20% 且热度分 ≥ 门槛）
     * @param viewCount    累计浏览量（全量历史口径 = qwt_venue_views 行数，按天按来源去重 PV
     *                     含匿名，与 VenueHeatResponse.viewCount30d 同源同口径的全量版）。
     *                     语义边界：这是「该门店浏览量」的事实字段——列表/收藏/详情
     *                     等任何消费场景传真实值（批量查询见
     *                     {@link org.quwuting.quwutingservice.venue.repository.VenueViewRepository#countByVenueIds}），
     *                     仅"无展示语义"场景（创建/编辑表单回显）传 0，禁止在消费场景
     *                     传默认 0（否则列表卡片浏览量恒为 0，同 isHot 历史缺陷模式）。
     */
    public VenueResponse toResponse(Venue v, List<ReactionBadge> topReactions, boolean isHot, long viewCount) {
        List<String> customTags = deserializeStringList(v.getTags(), "tags");
        List<String> effectiveTags = defaultsConfig.merge(customTags);
        List<String> defaultTags = defaultsConfig.tags();
        return new VenueResponse(
                v.getId(),
                v.getName(),
                v.getStatus(),
                v.getStatus().getDisplayName(),
                v.getImageUrl(),
                deserializeStringList(v.getPhotos(), "photos"),
                v.getDescription(),
                v.getCity(),
                v.getDistrict(),
                v.getAddress(),
                v.getLongitude(),
                v.getLatitude(),
                deserializeList(v.getBusinessHours(), BUSINESS_HOURS_LIST, "businessHours"),
                deserializeList(v.getTickets(), TICKET_LIST, "tickets"),
                deserializeList(v.getPartnerFees(), PARTNER_FEE_LIST, "partnerFees"),
                v.getContactPhone(),
                v.getWechatQr(),
                effectiveTags,
                defaultTags,
                topReactions != null ? topReactions : Collections.emptyList(),
                v.getSortWeight(),
                viewCount,
                isHot,
                v.getCreatedAt(),
                v.getUpdatedAt()
        );
    }

    /** 反序列化 JSON 数组字符串列（tags / photos / businessHours），空数据返回空列表而非 null */
    private List<String> deserializeStringList(String json, String fieldName) {
        return deserializeList(json, STRING_LIST, fieldName);
    }

    /** 反序列化 JSON 数组字符串列为类型化列表，空数据返回空列表而非 null */
    private <T> List<T> deserializeList(String json, TypeReference<List<T>> typeRef, String fieldName) {
        if (!StringUtils.hasText(json)) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to deserialize {}: {}", fieldName, json);
            return Collections.emptyList();
        }
    }
}
