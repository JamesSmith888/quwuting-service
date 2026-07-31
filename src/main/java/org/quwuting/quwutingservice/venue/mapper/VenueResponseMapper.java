package org.quwuting.quwutingservice.venue.mapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Venue 实体 → VenueResponse DTO 转换器。
 * 独立为组件供 VenueService、FavoriteService 等复用，避免映射逻辑重复。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class VenueResponseMapper {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm");
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {};
    private static final TypeReference<List<TicketEntry>> TICKET_LIST = new TypeReference<>() {};
    private static final TypeReference<List<PartnerFeeEntry>> PARTNER_FEE_LIST = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public VenueResponse toResponse(Venue v) {
        return toResponse(v, Collections.emptyMap(), false);
    }

    /**
     * @param tagLikeCounts 各标签的点赞数（tag → count），列表页/详情页批量查询后传入；
     *                      无需展示标签热度的场景（新建/编辑表单回显）传空 Map
     */
    public VenueResponse toResponse(Venue v, Map<String, Long> tagLikeCounts) {
        return toResponse(v, tagLikeCounts, false);
    }

    /**
     * @param tagLikeCounts 各标签的点赞数
     * @param isHot         是否为城市内热门场所（列表页视觉高亮）
     */
    public VenueResponse toResponse(Venue v, Map<String, Long> tagLikeCounts, boolean isHot) {
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
                formatHours(v.getAfternoonOpen(), v.getAfternoonClose()),
                formatHours(v.getEveningOpen(), v.getEveningClose()),
                deserializeList(v.getTickets(), TICKET_LIST, "tickets"),
                deserializeList(v.getPartnerFees(), PARTNER_FEE_LIST, "partnerFees"),
                v.getContactPhone(),
                v.getWechatQr(),
                deserializeStringList(v.getTags(), "tags"),
                tagLikeCounts != null ? tagLikeCounts : Collections.emptyMap(),
                v.getSortWeight(),
                isHot,
                v.getCreatedAt(),
                v.getUpdatedAt()
        );
    }

    private String formatHours(LocalTime open, LocalTime close) {
        if (open == null || close == null) return null;
        return open.format(TIME_FMT) + " - " + close.format(TIME_FMT);
    }

    /** 反序列化 JSON 数组字符串列（tags / photos），空数据返回空列表而非 null */
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
