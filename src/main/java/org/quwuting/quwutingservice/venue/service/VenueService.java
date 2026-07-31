package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.taginteraction.service.TagInteractionService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.dto.request.CreateVenueRequest;
import org.quwuting.quwutingservice.venue.dto.response.CityStatsResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueDetailResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.PartnerFeeUnit;
import org.quwuting.quwutingservice.venue.enums.TicketType;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.quwuting.quwutingservice.venuepost.repository.VenuePostRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueService {

    private static final int MAX_PAGE_SIZE = 50;

    private final VenueRepository venueRepository;
    private final VenuePostRepository venuePostRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final VenueResponseMapper venueResponseMapper;
    private final TagInteractionService tagInteractionService;
    private final StatusReportService statusReportService;
    private final ObjectMapper objectMapper;

    @Transactional
    public VenueResponse createVenue(CreateVenueRequest req) {
        validateTickets(req.tickets());
        Venue venue = new Venue();
        venue.setName(req.name());
        venue.setStatus(req.status() != null ? req.status() : VenueStatus.OPEN);
        venue.setImageUrl(req.imageUrl());
        venue.setPhotos(serializeStringList(req.photos()));
        venue.setDescription(req.description());
        // 城市/区县必须来自前端 region picker 的标准行政区划名（与列表筛选共用同一词表，精确匹配）
        venue.setCity(req.city().trim());
        venue.setDistrict(req.district().trim());
        venue.setAddress(req.address());
        venue.setLongitude(req.longitude());
        venue.setLatitude(req.latitude());
        venue.setAfternoonOpen(req.afternoonOpen());
        venue.setAfternoonClose(req.afternoonClose());
        venue.setEveningOpen(req.eveningOpen());
        venue.setEveningClose(req.eveningClose());
        venue.setTickets(serializeList(req.tickets()));
        venue.setPartnerFees(serializeList(normalizePartnerFees(req.partnerFees())));
        venue.setContactPhone(req.contactPhone());
        venue.setWechatQr(req.wechatQr());
        venue.setTags(serializeStringList(req.tags()));
        venue.setSortWeight(req.sortWeight() != null ? req.sortWeight() : 0);
        Venue saved = venueRepository.save(venue);
        // 初始状态日志：建立审计链起点（fromStatus=null 表示首次创建）
        VenueStatusLog initialLog = new VenueStatusLog();
        initialLog.setVenueId(saved.getId());
        initialLog.setFromStatus(null);
        initialLog.setToStatus(saved.getStatus());
        initialLog.setChangedBy(UserContext.getCurrentUserId());
        venueStatusLogRepository.save(initialLog);
        return venueResponseMapper.toResponse(saved);
    }

    /**
     * 更新场所信息（管理员或门店认领人）。
     * <p>
     * 全量覆盖可编辑字段（与 CreateVenueRequest 同结构），claimedBy 不在此接口变更。
     * 权限校验：{@link UserContext#requireManageOrAdmin(Long)}——ADMIN 或 claimedBy 匹配。
     */
    @Transactional
    public VenueResponse updateVenue(Long id, CreateVenueRequest req) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        UserContext.requireManageOrAdmin(venue.getClaimedBy());
        validateTickets(req.tickets());

        venue.setName(req.name());
        // 状态变更检测：写入变迁日志（热度统计"暂停营业次数"的数据源）
        VenueStatus newStatus = req.status() != null ? req.status() : venue.getStatus();
        if (newStatus != venue.getStatus()) {
            VenueStatusLog statusLog = new VenueStatusLog();
            statusLog.setVenueId(venue.getId());
            statusLog.setFromStatus(venue.getStatus());
            statusLog.setToStatus(newStatus);
            statusLog.setChangedBy(UserContext.getCurrentUserId());
            venueStatusLogRepository.save(statusLog);
        }
        venue.setStatus(newStatus);
        venue.setImageUrl(req.imageUrl());
        venue.setPhotos(serializeStringList(req.photos()));
        venue.setDescription(req.description());
        venue.setCity(req.city().trim());
        venue.setDistrict(req.district().trim());
        venue.setAddress(req.address());
        venue.setLongitude(req.longitude());
        venue.setLatitude(req.latitude());
        venue.setAfternoonOpen(req.afternoonOpen());
        venue.setAfternoonClose(req.afternoonClose());
        venue.setEveningOpen(req.eveningOpen());
        venue.setEveningClose(req.eveningClose());
        venue.setTickets(serializeList(req.tickets()));
        venue.setPartnerFees(serializeList(normalizePartnerFees(req.partnerFees())));
        venue.setContactPhone(req.contactPhone());
        venue.setWechatQr(req.wechatQr());
        venue.setTags(serializeStringList(req.tags()));
        venue.setSortWeight(req.sortWeight() != null ? req.sortWeight() : venue.getSortWeight());
        return venueResponseMapper.toResponse(venueRepository.save(venue));
    }

    /**
     * 场所详情（含管理权限判定与动态计数）。
     * <p>
     * canManage 基于软鉴权上下文计算：平台管理员或门店认领人为 true，匿名请求恒为 false。
     * 该字段仅驱动前端管理入口的展示，安全边界在后端各写操作接口的角色校验。
     */
    @Transactional(readOnly = true)
    public VenueDetailResponse getVenueDetail(Long id) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        Map<String, Long> tagLikeCounts = tagInteractionService.batchGetTagLikeCounts(List.of(id))
                .getOrDefault(id, Collections.emptyMap());
        VenueResponse base = venueResponseMapper.toResponse(venue, tagLikeCounts);
        long postCount = venuePostRepository.countByVenueIdAndDeletedFalse(id);
        boolean canManage = computeCanManage(venue);
        boolean hasMyStatusReport = statusReportService.hasMyReport(id);
        return new VenueDetailResponse(base, canManage, postCount, hasMyStatusReport);
    }

    /**
     * 场所列表：筛选 + 复合评分排序 + 分页。
     * <p>
     * 排序为服务端复合评分（公式见 {@link VenueRepository#searchRanked}）：
     * 运营权重 + 热度（收藏 × 20 + 动态 × 10）+ 邻近加成 100/(1+距离km)。
     * latitude/longitude 为 null（用户未授权定位）时走无距离变体，退化为权重 + 热度排序——
     * 拆分为两个查询而非传 null 参数：Postgres 将无类型的 null 绑定参数推断为 bytea，
     * 会使 radians() 解析失败。
     * 城市/区县按标准行政区划名精确匹配——写入与查询共用 region picker 词表，禁止模糊匹配。
     */
    @Transactional(readOnly = true)
    public Page<VenueResponse> listVenues(String city, String district,
                                          VenueStatus status, String keyword,
                                          Double latitude, Double longitude,
                                          int page, int size) {
        String keywordPattern = StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, size);
        Page<Venue> result;
        if (latitude != null && longitude != null) {
            result = venueRepository.searchRanked(
                    blankToNull(city), blankToNull(district), status, keywordPattern,
                    latitude, longitude, pageable);
        } else {
            result = venueRepository.searchRankedNoLocation(
                    blankToNull(city), blankToNull(district), status, keywordPattern, pageable);
        }
        // 批量查询整页场所的标签点赞数，避免逐条查询造成的 N+1（见 TagInteractionService#batchGetTagLikeCounts）
        List<Long> venueIds = result.getContent().stream().map(Venue::getId).toList();
        Map<Long, Map<String, Long>> tagLikeCountsByVenue = tagInteractionService.batchGetTagLikeCounts(venueIds);
        // 查询城市内热门场所 ID 集合（单次全表查询，数据规模小）
        Set<Long> hotVenueIds = new HashSet<>(venueRepository.findHotVenueIds());
        return result.map(v -> venueResponseMapper.toResponse(
                v, tagLikeCountsByVenue.getOrDefault(v.getId(), Collections.emptyMap()),
                hotVenueIds.contains(v.getId())));
    }

    /** 有场所的城市列表（按场所数倒序），供前端热门城市选择 */
    @Transactional(readOnly = true)
    public List<CityStatsResponse> listCityStats() {
        return venueRepository.findCityStats().stream()
                .map(p -> new CityStatsResponse(p.getCity(), p.getVenueCount()))
                .toList();
    }

    // ===== private helpers =====

    /** 门票跨字段校验：FIXED 类型必须携带票价（注解校验无法表达条件必填） */
    private void validateTickets(List<TicketEntry> tickets) {
        if (tickets == null) return;
        for (TicketEntry ticket : tickets) {
            if (ticket.type() == TicketType.FIXED && ticket.price() == null) {
                throw new BusinessException(1001, "固定门票必须填写票价");
            }
        }
    }

    /**
     * 规范化舞伴费用列表：unit 缺省为 MINUTE（兼容旧客户端），label 空白统一为 null（存储整洁）。
     * 序列化后 JSON 始终包含显式 unit 值，读取端无需再处理 null 分支。
     */
    private List<PartnerFeeEntry> normalizePartnerFees(List<PartnerFeeEntry> fees) {
        if (fees == null || fees.isEmpty()) return fees;
        return fees.stream()
                .map(f -> new PartnerFeeEntry(
                        blankToNull(f.label()),
                        f.unit() != null ? f.unit() : PartnerFeeUnit.MINUTE,
                        f.minutes(),
                        f.price()))
                .toList();
    }

    private static String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    /**
     * 管理权判定规则：
     * 1. 平台管理员 → 对所有门店有管理权；
     * 2. 门店认领人（claimedBy）→ 对认领门店有管理权；
     * 3. 匿名 / 其他用户 → 无管理权。
     */
    private boolean computeCanManage(Venue venue) {
        Long userId = UserContext.getCurrentUserId();
        if (userId == null) {
            return false;
        }
        if (UserContext.getCurrentRole() == UserRole.ADMIN) {
            return true;
        }
        return userId.equals(venue.getClaimedBy());
    }

    /** 序列化字符串列表为 JSON 数组字符串（tags / photos 共用），空列表存 null */
    private String serializeStringList(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("Failed to serialize string list: {}", values, e);
            return null;
        }
    }

    /** 序列化结构化列表为 JSON 数组字符串（tickets / partnerFees 共用），空列表存 null */
    private String serializeList(List<?> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("Failed to serialize list: {}", values, e);
            return null;
        }
    }
}
