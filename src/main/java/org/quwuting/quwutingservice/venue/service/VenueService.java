package org.quwuting.quwutingservice.venue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.service.VenueReactionService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.config.VenueDefaultsConfig;import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.dto.request.CreateVenueRequest;
import org.quwuting.quwutingservice.venue.dto.response.CityStatsResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueDetailResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.PartnerFeeUnit;
import org.quwuting.quwutingservice.venue.enums.TicketType;
import org.quwuting.quwutingservice.venue.enums.VenueSortMode;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venuepost.repository.VenuePostRepository;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueService {

    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 列表排序/热门标记的「行为热度」公式所需的正向 Reaction code 列表
     * （HEAT_SCORE SQL 镜像的 :positiveCodes 参数，唯一事实源 = ReactionCode）。
     */
    private static final List<String> POSITIVE_REACTION_CODES = ReactionCode.positiveCodeNames();

    private final VenueRepository venueRepository;
    private final VenuePostRepository venuePostRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    private final VenueResponseMapper venueResponseMapper;
    private final VenueReactionService venueReactionService;
    private final VenueHeatService venueHeatService;
    private final ObjectMapper objectMapper;
    private final VenueLookupService venueLookupService;
    private final VenueDefaultsConfig defaultsConfig;

    @Transactional
    @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true)
    public VenueResponse createVenue(CreateVenueRequest req) {
        validateTickets(req.tickets());
        Venue venue = new Venue();
        venue.setName(req.name());
        venue.setStatus(req.status() != null ? req.status() : VenueStatus.OPEN);
        venue.setImageUrl(req.imageUrl());
        venue.setPhotos(serializeStringList(req.photos()));
        venue.setDescription(req.description());
        // 城市必填、区县选填（2026-08-08 放宽：行政区非业务必填），须来自前端 region
        // picker 的标准行政区划名（与列表筛选共用同一词表，精确匹配）
        venue.setCity(req.city().trim());
        venue.setDistrict(req.district() == null ? null : req.district().trim());
        venue.setAddress(req.address());
        venue.setLongitude(req.longitude());
        venue.setLatitude(req.latitude());
        venue.setBusinessHours(serializeList(req.businessHours()));
        venue.setTickets(serializeList(req.tickets()));
        venue.setPartnerFees(serializeList(normalizePartnerFees(req.partnerFees())));
        venue.setContactPhone(req.contactPhone());
        venue.setWechatQr(req.wechatQr());
        venue.setTags(serializeStringList(defaultsConfig.filterCustomOnly(req.tags())));
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
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.CACHE_VENUE, key = "#id"),
            @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true)
    })
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
        venue.setDistrict(req.district() == null ? null : req.district().trim());
        venue.setAddress(req.address());
        venue.setLongitude(req.longitude());
        venue.setLatitude(req.latitude());
        venue.setBusinessHours(serializeList(req.businessHours()));
        venue.setTickets(serializeList(req.tickets()));
        venue.setPartnerFees(serializeList(normalizePartnerFees(req.partnerFees())));
        venue.setContactPhone(req.contactPhone());
        venue.setWechatQr(req.wechatQr());
        venue.setTags(serializeStringList(defaultsConfig.filterCustomOnly(req.tags())));
        venue.setSortWeight(req.sortWeight() != null ? req.sortWeight() : venue.getSortWeight());
        VenueResponse response = venueResponseMapper.toResponse(venueRepository.save(venue));
        // 场所编辑影响热度响应的输出（status/状态日志 → currentStatus / currentStatusDays）——
        // 显式逐出，与其余写路径一致
        venueHeatService.invalidate(id);
        return response;
    }

    /**
     * 场所详情（含管理权限判定与动态计数）。
     * <p>
     * canManage 基于软鉴权上下文计算：平台管理员或门店认领人为 true，匿名请求恒为 false。
     * 该字段仅驱动前端管理入口的展示，安全边界在后端各写操作接口的角色校验。
     * <p>
     * DB 往返压缩：场所实体经 {@link VenueLookupService#findById} 缓存；Top Reaction 徽标复用
     * {@link org.quwuting.quwutingservice.venuereaction.service.VenueReactionAggregateService}
     * 的聚合缓存（与 /reactions/stats 端点共享同一 venueId key，详情页并发请求单飞回源），
     * 个人参与状态单独实时查询；动态总数与"我是否已上报"合并为单条标量子查询
     * （{@link VenuePostRepository#findDetailStats}，个人状态实时计算不缓存）。
     * 缓存全命中时仅 2 次往返，冷启动最多 4 次。
     */
    @Transactional(readOnly = true)
    public VenueDetailResponse getVenueDetail(Long id) {
        Venue venue = venueLookupService.findById(id);
        // 详情基础响应的徽标固定取默认窗口（近7天）——详情页 Reaction 完整统计走 /reactions/stats
        // （四窗口全量），本字段仅承载列表快照/兜底展示，不参与详情页 Reaction UI 主流程
        List<ReactionBadge> topReactions = venueReactionService.getBadges(
                id, UserContext.getCurrentUserId(), ReactionWindow.DAYS_7);
        VenueResponse base = venueResponseMapper.toResponse(venue, topReactions);
        // 「我是否已上报」必须与活跃计数同一 TTL 口径：hasmyreport 的活跃判定带
        // created_at >= now-4h 过滤（TTL 常量唯一权威源 = StatusReportService，见
        // VenuePostRepository.findDetailStats javadoc——历史实现漏过滤导致 TTL 过期后
        // 详情页"已报告·补充"永不还原）
        LocalDateTime reportSince = LocalDateTime.now().minusHours(StatusReportService.ACTIVE_REPORT_TTL_HOURS);
        VenuePostRepository.DetailStats detailStats =
                venuePostRepository.findDetailStats(id, UserContext.getCurrentUserId(), reportSince);
        boolean canManage = computeCanManage(venue);
        long postCount = detailStats.getPostcount() != null ? detailStats.getPostcount() : 0L;
        boolean hasMyStatusReport = Boolean.TRUE.equals(detailStats.getHasmyreport());
        // 营业状态字段的最近一次变更时间（详情弹窗「营业状态更新」展示源）：
        // 取自状态日志表最新一条 createdAt，而非整个场所记录的 updatedAt——
        // 后者任意字段编辑都刷新，语义不匹配"营业状态何时更新的"
        return new VenueDetailResponse(base, canManage, postCount, hasMyStatusReport,
                venueStatusLogRepository.findLatestStatusChangeTime(id));
    }

    /**
     * 场所列表：筛选 + 排序 + 距离半径 + 分页。
     * <p>
     * <b>排序由服务端在库内完成</b>（分页正确性要求排序与分页同一查询，见 AGENTS.md「复合评分排序」）：
     * <ul>
     *   <li>recommended（默认）：复合评分 = 行为热度（{@code VenueRepository.HEAT_SCORE} 镜像公式，
     *       2026-08-08 与热度页统一，见「场所热度」章节）+ 邻近加成 100/(1+距离km)——有定位时
     *       本地场所自然置顶，无定位时退化为行为热度；</li>
     *   <li>distance：纯距离升序（仅展示有坐标的场所）；无定位时降级为推荐排序
     *       （无法按距离排序，防御性回退而非空列表）；</li>
     *   <li>heat：行为热度（不含距离项，与「热门场所标记」同口径）；</li>
     *   <li>newest：创建时间倒序。</li>
     * </ul>
     * <p>
     * {@code radiusKm}（可选，km）为距离半径筛选，与排序方式正交：仅在叠加在"含坐标"的查询上
     * （距离计算需要请求者位置为圆心）。无坐标请求携带 radiusKm 时忽略（前端不会发送——
     * 前端仅在有定位缓存时附带坐标与半径，此处忽略仅作防御）。
     * <p>
     * <b>双查询拆分坑位</b>（AGENTS.md「双查询拆分」）：含 radians()/acos() 距离数学的查询
     * 必须传非 null 坐标——Postgres 将无类型的 null 绑定参数推断为 bytea，radians() 无法解析。
     * 因此本方法按"坐标有无 × 排序方式 × 半径有无"显式分流，杜绝向距离查询传 null 坐标：
     * <pre>
     * 推荐排序    → 有坐标：searchRanked(坐标, 半径)  无坐标：searchRankedNoLocation
     * 距离最近    → 有坐标：searchNearest(坐标, 半径) 无坐标：降级 searchRankedNoLocation
     * 热度最高    → 有坐标且有半径：searchHeatWithinRadius  其余：searchHeat
     * 最新收录    → 有坐标且有半径：searchNewestWithinRadius 其余：searchNewest
     * </pre>
     * 热度最高/最新收录的排序本身不依赖坐标，仅在叠加半径时借用坐标作圆心——所以只有
     * "有坐标且有半径"才进入 WithRadius 变体，避免无谓地把坐标参数绑定进不含距离数学的查询。
     * <p>
     * {@code window} 控制卡片 Top Reaction 徽标的排序/筛选窗口（近7天/近30天/全部，
     * 默认近7天——舞厅强时间变化场景，列表默认展示近期热度，见 AGENTS.md「Reaction 快速反馈系统」）。
     * <p>
     * {@code hot}（可选，2026-08-08 新增「热门」快捷筛选）：true 时仅返回热门场所——
     * ID ∈ {@link VenueLookupService#getHotVenueIds()}（城市内 top 20% 且 热度分 ≥ 门槛，
     * 5min 缓存）。与城市/状态/距离等筛选正交可叠加；默认不传 = 不过滤（默认口径不做隐式过滤）。
     * 热门集合同时用于响应 isHot 标记，一次获取两处复用。
     */
    @Transactional(readOnly = true)
    public Page<VenueResponse> listVenues(String city, String district,
                                          VenueStatus status, String keyword,
                                          Double latitude, Double longitude,
                                          String window, String sort, Double radiusKm,
                                          Boolean hot,
                                          int page, int size) {
        String keywordPattern = StringUtils.hasText(keyword) ? "%" + keyword.trim() + "%" : null;
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, size);
        boolean hasCoords = latitude != null && longitude != null;
        // radiusKm 为 null / ≤ 0 视为不限（前端 0 = 不限，防御性归一）
        Double radius = (radiusKm != null && radiusKm > 0) ? radiusKm : null;
        VenueSortMode sortMode = VenueSortMode.from(sort);
        // 热门场所 ID 集合（5min 缓存）：列表查询前获取，一次取用双职责——
        // ① hot 筛选参数（hotOnly=true 时按集合过滤）② 响应 isHot 标记
        Set<Long> hotVenueIds = venueLookupService.getHotVenueIds();
        boolean hotOnly = Boolean.TRUE.equals(hot);
        Page<Venue> result = dispatchListQuery(sortMode, blankToNull(city), blankToNull(district),
                status, keywordPattern, hasCoords, latitude, longitude, radius,
                POSITIVE_REACTION_CODES, hotOnly, hotVenueIds, pageable);
        // 批量查询整页场所的 Top Reaction 徽标，避免逐条查询造成的 N+1（见 VenueReactionService#batchGetBadges）
        List<Long> venueIds = result.getContent().stream().map(Venue::getId).toList();
        Map<Long, List<ReactionBadge>> reactionsByVenue =
                venueReactionService.batchGetBadges(venueIds, UserContext.getCurrentUserId(),
                        ReactionWindow.from(window));
        return result.map(v -> venueResponseMapper.toResponse(
                v, reactionsByVenue.getOrDefault(v.getId(), Collections.emptyList()),
                hotVenueIds.contains(v.getId())));
    }

    /**
     * 列表查询分流（排序 × 坐标 × 半径 的显式矩阵，见 {@link #listVenues} 注释）。
     * 坐标必为 null 或双非 null（hasCoords 为准），distance 数学查询只在 hasCoords=true 分支被调用。
     * {@code hotOnly}/{@code hotIds} 透传给全部变体（热门筛选与排序正交，见 LIST_FILTERS 注释）。
     */
    private Page<Venue> dispatchListQuery(VenueSortMode sortMode, String city, String district,
                                          VenueStatus status, String keywordPattern,
                                          boolean hasCoords, Double latitude, Double longitude,
                                          Double radius, List<String> positiveCodes,
                                          boolean hotOnly, Set<Long> hotIds, PageRequest pageable) {
        return switch (sortMode) {
            case RECOMMENDED -> hasCoords
                    ? venueRepository.searchRanked(city, district, status, keywordPattern,
                            latitude, longitude, radius, positiveCodes, hotOnly, hotIds, pageable)
                    : venueRepository.searchRankedNoLocation(city, district, status, keywordPattern,
                            positiveCodes, hotOnly, hotIds, pageable);
            case DISTANCE -> hasCoords
                    ? venueRepository.searchNearest(city, district, status, keywordPattern,
                            latitude, longitude, radius, hotOnly, hotIds, pageable)
                    // 无定位无法按距离排序：防御性降级为推荐排序（而非空列表/报错）
                    : venueRepository.searchRankedNoLocation(city, district, status, keywordPattern,
                            positiveCodes, hotOnly, hotIds, pageable);
            case HEAT -> hasCoords && radius != null
                    ? venueRepository.searchHeatWithinRadius(city, district, status, keywordPattern,
                            latitude, longitude, radius, positiveCodes, hotOnly, hotIds, pageable)
                    : venueRepository.searchHeat(city, district, status, keywordPattern,
                            positiveCodes, hotOnly, hotIds, pageable);
            case NEWEST -> hasCoords && radius != null
                    ? venueRepository.searchNewestWithinRadius(city, district, status, keywordPattern,
                            latitude, longitude, radius, hotOnly, hotIds, pageable)
                    : venueRepository.searchNewest(city, district, status, keywordPattern,
                            hotOnly, hotIds, pageable);
        };
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
