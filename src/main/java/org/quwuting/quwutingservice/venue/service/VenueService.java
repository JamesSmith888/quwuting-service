package org.quwuting.quwutingservice.venue.service;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.service.ResourceAccessService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venuereaction.ReactionCode;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.service.VenueReactionService;
import org.quwuting.quwutingservice.venue.config.VenueDefaultsConfig;import org.quwuting.quwutingservice.venue.dto.PartnerFeeEntry;
import org.quwuting.quwutingservice.venue.dto.TicketEntry;
import org.quwuting.quwutingservice.venue.dto.request.CreateVenueRequest;
import org.quwuting.quwutingservice.venue.dto.response.AdminVenuePhotoResponse;
import org.quwuting.quwutingservice.venue.dto.response.CityStatsResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueDetailResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenuePhotoResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueResponse;
import org.quwuting.quwutingservice.venue.dto.response.VenueSuggestResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenuePhoto;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.PartnerFeeUnit;
import org.quwuting.quwutingservice.venue.enums.TicketType;
import org.quwuting.quwutingservice.venue.enums.VenuePhotoStatus;
import org.quwuting.quwutingservice.venue.enums.VenueSortMode;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.mapper.VenueResponseMapper;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportService;
import org.quwuting.quwutingservice.venue.repository.VenuePhotoRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venue.repository.VenueViewRepository;
import org.quwuting.quwutingservice.venuepost.repository.VenuePostRepository;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportLatestService;
import org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService;
import org.quwuting.quwutingservice.venuestatuswatcher.service.VenueStatusWatcherService;
import org.quwuting.quwutingservice.venueclaim.entity.VenueClaim;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.quwuting.quwutingservice.venueclaim.repository.VenueClaimRepository;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.storage.ImageContentValidator;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VenueService {

    private static final int MAX_PAGE_SIZE = 50;

    // ===== 关键词检索（2026-09-02 搜索增强 v2，见 listVenues 注释与
    //       docs/agents/07-list-page.md「关键词匹配口径」） =====

    /** 关键词拆词上限：超过截断取前 N——逐词行集探测次数与交集查询量受控 */
    private static final int MAX_KEYWORD_TOKENS = 4;
    /** 关键词原始输入长度上限（防超长 LIKE pattern，@RequestParam 之外的防御） */
    private static final int MAX_KEYWORD_LENGTH = 60;
    /** 关键词分隔符（半角/全角空格、中英文逗号、顿号、斜杠 → 拆词 AND） */
    private static final String KEYWORD_SPLIT_REGEX = "[\\s,，、/]+";
    /** 联想建议条数上限（suggest 轻量接口，前端滚动联想无需更多） */
    private static final int MAX_SUGGEST_SIZE = 8;

    // ===== 门店照片域（2026-08-20，见 AGENTS.md「门店照片域」） =====

    /** 单次照片上传数量上限（与前端 image-upload maxCount=9 对齐——后端独立校验防绕过） */
    private static final int MAX_PHOTOS_PER_UPLOAD = 9;

    /** 管理端审核列表门店名占位（门店已软删时回退，审核页仍可辨识来源） */
    private static final String PHOTO_VENUE_GONE_NAME = "门店已删除";
    /** 管理端审核列表上传者占位（存量导入 created_by=0 / 用户已软删时回退） */
    private static final String PHOTO_UPLOADER_GONE_NAME = "未知用户";

    /**
     * 列表排序/热门标记的「行为热度」公式所需的正向 Reaction code 列表
     * （HEAT_SCORE SQL 镜像的 :positiveCodes 参数，唯一事实源 = ReactionCode）。
     */
    private static final List<String> POSITIVE_REACTION_CODES = ReactionCode.positiveCodeNames();

    private final VenueRepository venueRepository;
    private final ResourceAccessService resourceAccessService;
    private final VenuePostRepository venuePostRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
    /** 浏览记录（列表/详情响应组装累计浏览量 viewCount 用，2026-08-12 新增） */
    private final VenueViewRepository venueViewRepository;
    private final VenueResponseMapper venueResponseMapper;
    private final VenueReactionService venueReactionService;
    /** 门店热度上报（2026-08-29 列表角标批量生成，见 badgeTextsByVenue） */
    private final CrowdReportService crowdReportService;
    /** 门店报告列表角标批量生成（2026-09-04「有用户上报」，独立微服务规避构造器循环，见其类注释） */
    private final StatusReportLatestService statusReportLatestService;
    private final VenueHeatService venueHeatService;
    private final ObjectMapper objectMapper;
    private final VenueLookupService venueLookupService;
    private final VenueDefaultsConfig defaultsConfig;
    private final org.quwuting.quwutingservice.config.PointsProperties pointsProperties;
    private final VenueClaimRepository venueClaimRepository;
    /** 关注门店营业状态（状态变更挂点：向关注者发 VENUE_STATUS_CHANGED 站内信） */
    private final VenueStatusWatcherService venueStatusWatcherService;
    /** 图片内容校验（2026-08-12 恶意文件防线：业务提交时对图片 URL 做内容级校验） */
    private final ImageContentValidator imageValidator;
    /** 门店相册照片（2026-08-20 门店照片域：独立表 + PENDING 先审后发，见 AGENTS.md「门店照片域」） */
    private final VenuePhotoRepository venuePhotoRepository;
    /** 场所实体缓存显式逐出（照片写方法 key 依赖查询结果，@CacheEvict 无法表达，见 VenueClaimService 同款先例） */
    private final CacheManager cacheManager;

    /**
     * 详情接口「公共部分」缓存（2026-08-13 新增，性能优化：详情接口 DB 往返 5→2 次）。
     * <p>
     * 根因：getVenueDetail 每次请求都执行 5 次 DB 往返（venue 实体 / badges 聚合 /
     * viewCount COUNT / detailStats / claim / statusLog）——其中 venue、badges 已有缓存，
     * 但 viewCount COUNT、statusLog、claimed 属于「与请求用户无关的全局事实」，30s 内
     * 不变，却每次全查。Supabase 跨洲单次往返 300~500ms，省 2 次即省 600ms~1s 详情延迟。
     * <p>
     * 语义边界（与 VenueHeatService 同模式，服务内嵌 Caffeine LoadingCache）：
     * <ul>
     *   <li>只缓存<b>公共部分</b>（VenueResponse base + 状态更新时间 + 认领事实）——
     *       用户相关字段（canManage / hasMyStatusReport / myClaimStatus）永远实时查询；
     *       canManage 由 venue 实体内存计算（零查询），detailStats 合并单查询，
     *       claim 仅登录时查（匿名恒 null）；</li>
     *   <li>refresh-ahead 30s + 硬过期 10min + 单飞：活跃场所不吃同步冷加载，
     *       与 heat / tagStats / reaction 聚合缓存同族语义；</li>
     *   <li>新鲜度主保障 = 写路径显式 {@link #invalidateDetailPublic}：场所编辑、
     *       状态变更（采纳暂停/恢复）、认领审批后立即失效，refresh 仅兜底；</li>
     *   <li>base.topReactions（默认窗口徽标）内嵌了聚合缓存快照，Reaction toggle 写路径
     *       只失效聚合缓存、不失效本缓存——30s 内徽标短暂滞后可接受（详情页 Reaction UI
     *       主流程走 /reactions/stats 独立接口，不依赖 base.topReactions；后者仅承载
     *       列表快照/兜底展示）。</li>
     * </ul>
     */
    private final LoadingCache<Long, VenueDetailPublic> venueDetailPublicCache = Caffeine.newBuilder()
            .maximumSize(500)
            .refreshAfterWrite(30, TimeUnit.SECONDS)
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build(this::computeVenueDetailPublic);

    /**
     * 列表主查询「无坐标视图」缓存（2026-08-30 性能优化，根因见 AGENTS.md「首页性能优化」）。
     * <p>
     * 背景：列表接口单次请求 8~9 次跨洲 DB 往返（主查询 + count + badges + 个人态 +
     * 浏览量 + 照片 + 角标×2），单次往返 300~500ms（ECS↔Supabase 东京）——主查询
     * （HEAT_SCORE 全表每行 6+ 标量子查询 ×2 处出现 + 分页 count）是其中最重的两跳，
     * 且对「无坐标 + 无筛选」的公共视图而言，结果与请求用户完全无关、低频变化
     * （门店新增/编辑/状态变更），却被每个用户每次冷启动重复计算。
     * <p>
     * 缓存边界（与 {@link #venueDetailPublicCache} 同族语义，个人态永不缓存）：
     * <ul>
     *   <li><b>仅缓存 hasCoords=false 分支</b>（无坐标：全国 / 显式城市视角，按
     *       行为热度排序的公共视图）——带坐标查询的结果集受 {@code radiusKm} 半径筛选
     *       约束（2026-09-01 起排序不含邻近加成，但半径过滤使结果与请求者位置相关），
     *       缓存共享语义不成立（不同位置用户 300km 半径内的门店集合不同）；</li>
     *   <li>缓存粒度 = <b>Page&lt;Venue&gt; 实体列表</b>（纯公共数据），不含任何
     *       用户相关字段——Reaction 个人参与态（reactedByMe）、canManage 等仍在
     *       缓存外实时查询组装（ReactionBadge 注释契约「个人状态永远实时查询」）；</li>
     *   <li>TTL 60s + 写路径显式失效（{@link #invalidateVenueListCache}）：门店
     *       新增/编辑/状态变更/照片变化立即生效；热度排序（reaction/favorite/view
     *       累积）靠 60s 自然过期兜底——此类低频变化用户无秒级实时感知诉求
     *       （热门 ID 集合本身也是 5min 缓存）；</li>
     *   <li>LoadingCache 单飞：同参数并发只回源一次，热参数组合（默认全国推荐）
     *       多用户共享同一份结果。</li>
     * </ul>
     * 键 = 影响主查询结果的全部参数（sortMode 决定无坐标排序口径——RECOMMENDED/
     * DISTANCE 降级走 searchRankedNoLocation，HEAT/NEWEST 排序不同；positiveCodes/
     * pointsWeight 为配置值重启才变；hotIds 全局集合 5min 缓存由 60s TTL 自然兜底）。
     */
    private final LoadingCache<VenueListKey, Page<Venue>> venueListCache = Caffeine.newBuilder()
            .maximumSize(128)
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .build(this::loadVenueListPage);

    /** 列表主查询无坐标视图缓存键（不可变参数指纹，见 {@link #venueListCache} 注释）。
     *  kwPrefixPattern（2026-09-02 相关度排序前缀 pattern）与 keywordPattern 同源同变，
     *  一并入键保证 ORDER BY 相关度键与结果一致。 */
    private record VenueListKey(
            VenueSortMode sortMode,
            String city,
            String district,
            VenueStatus status,
            String keywordPattern,
            String kwPrefixPattern,
            String tagPattern,
            boolean hotOnly,
            int page,
            int size
    ) {}

    /**
     * 新增场所（仅管理员）。
     * <p>
     * 2026-08-20 门店照片域：photos 不再写入 venue.photos JSON 列（V35 起该列废弃、
     * 读路径整体切换 qwt_venue_photos 独立表）——创建表单携带的照片由本方法转存
     * 独立表并直发 PUBLIC（创建者为管理方可信写者，保留旧 JSON 列直写公开语义）。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true),
            @CacheEvict(value = CacheConfig.CACHE_CITY_STATS, allEntries = true)
    })
    public VenueResponse createVenue(CreateVenueRequest req) {
        validateTickets(req.tickets());
        imageValidator.validate(req.imageUrl());
        imageValidator.validateAll(req.photos());
        imageValidator.validate(req.wechatQr());
        Venue venue = new Venue();
        venue.setName(req.name());
        venue.setStatus(req.status() != null ? req.status() : VenueStatus.OPEN);
        venue.setImageUrl(req.imageUrl());
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
        // 创建表单携带的照片转存独立表（直发 PUBLIC，见方法 javadoc）
        List<String> photos = req.photos() == null ? List.of() : req.photos();
        if (!photos.isEmpty()) {
            persistPhotosDirect(saved.getId(), UserContext.getCurrentUserId(), photos, VenuePhotoStatus.PUBLIC);
        }
        // 新门店出现：列表主查询缓存立即失效（新店 60s 内必须可见）
        invalidateVenueListCache();
        return venueResponseMapper.toResponse(saved, Collections.emptyList(), false, 0L,
                loadPublicPhotosByVenueIds(List.of(saved.getId())).getOrDefault(saved.getId(), List.of()));
    }

    /**
     * 更新场所信息（平台管理员或获授权的资料管理员）。
     * <p>
     * 全量覆盖可编辑字段（与 CreateVenueRequest 同结构），claimedBy 不在此接口变更。
    * 权限校验统一读取资源授权；claimedBy 仅保留认领摘要语义。
     * <p>
     * 2026-08-20 门店照片域：<b>忽略 req.photos()</b>——照片改由独立接口逐张管理
     * （POST /venues/{id}/photos 上传、/photos/{photoId}/remove 删除），编辑表单不再
     * 全量覆盖（JSON 列全量覆盖会误删他人 UGC 照片，见 AGENTS.md「门店照片域」）。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.CACHE_VENUE, key = "#id"),
            @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true),
            @CacheEvict(value = CacheConfig.CACHE_CITY_STATS, allEntries = true)
    })
    public VenueResponse updateVenue(Long id, CreateVenueRequest req) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        resourceAccessService.requireCurrentUser(ResourceType.VENUE, id,
            ResourcePermission.VENUE_PROFILE_EDIT);
        validateTickets(req.tickets());
        // 2026-08-24 修复「只改位置也报 1005 图片地址不合法」：图片未变更不重校验。
        // 存量 URL（历史 picsum 占位图 / 高德图床直写主图）不在 ImageContentValidator
        // 白名单内，编辑表单回显原样提交必被拒；本接口只校验「新提交的值」——
        // 与库中现值不同才校验，未变更（相等）跳过。清空图片（null）由 validator
        // 空值放行兜底，不属本分支。
        if (!Objects.equals(req.imageUrl(), venue.getImageUrl())) {
            imageValidator.validate(req.imageUrl());
        }
        if (!Objects.equals(req.wechatQr(), venue.getWechatQr())) {
            imageValidator.validate(req.wechatQr());
        }

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
            // 关注者通知（2026-08-12 新增，见 AGENTS.md「关注门店营业状态通知」）：
            // 营业状态实际变更时同事务发站内信（幂等——状态未变不进本分支，不发）
            venueStatusWatcherService.notifyStatusChanged(
                    venue.getId(), venue.getStatus(), newStatus);
        }
        venue.setStatus(newStatus);
        venue.setImageUrl(req.imageUrl());
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
        VenueResponse response = venueResponseMapper.toResponse(venueRepository.save(venue),
                Collections.emptyList(), false, 0L,
                loadPublicPhotosByVenueIds(List.of(id)).getOrDefault(id, List.of()));
        // 场所编辑影响热度响应的输出（status/状态日志 → currentStatus / currentStatusDays）——
        // 显式逐出，与其余写路径一致
        venueHeatService.invalidate(id);
        // 列表主查询缓存失效：字段/状态变化影响列表排序与展示（2026-08-30 新增）
        invalidateVenueListCache();
        // 详情公共部分缓存失效：字段/状态变更后 base 响应（含 photos/tickets/status/
        // statusUpdatedAt/claimed 快照）必须立即重算（2026-08-13 新增）
        invalidateDetailPublic(id);
        return response;
    }

    // ─── 门店相册照片（2026-08-20 门店照片域：独立表 + PENDING 先审后发） ────────────
    //
    // 根因（AGENTS.md「门店照片域」）：旧 venue.photos JSON 列只能经"创建/编辑门店"
    // 整表提交全量覆盖（写权限 = 认领人/管理员），普通到店用户（最大照片贡献来源）
    // 零通道，且无逐张审核闸门不敢开放 UGC → 照片趋零。本组方法把门店照片升级为
    // 独立资产域（V35 qwt_venue_photos），完整复用舞伴照片（DancerPhoto）模式：
    // 上传即落独立表（管理方 canManage 直发 PUBLIC / 普通用户 PENDING 先审后发）、
    // sortOrder 上传序、管理端逐张审核、读路径只含 PUBLIC。
    //
    // 可见性规则（fetchVenuePhotos 管理入口回显用）：
    //   - PUBLIC：所有人可见；
    //   - PENDING / REJECTED：仅上传者本人（createdBy）与门店管理方（canManage）/管理员可见。

    /**
     * 上传门店照片（POST /venues/{id}/photos，仅平台管理员——2026-08-20 深夜收口）。
     * <p>
     * 原为登录即可的 UGC 通道（管理方直发 PUBLIC / 普通用户 PENDING 先审后发），
     * 因个人主体小程序未开放「社交服务」类目被审核驳回——普通用户/认领人上传照片属
     * 用户自行生成内容发布，与已下架「发布动态」同判（见 AGENTS.md「门店照片域」）。
     * 收口后 admin 上传直发 PUBLIC（可信写者，保留旧 JSON 列直写公开语义）。
     * <p>
     * 返回本人视角全量照片（PUBLIC 全部）。写路径失效公共缓存（详情/列表照片已变化，
     * 见 {@link #invalidateDetailPublic}）。
     */
    @Transactional
    public List<VenuePhotoResponse> addVenuePhotos(Long userId, Long venueId, List<String> urls) {
        Venue venue = venueLookupService.findById(venueId);
        if (urls == null || urls.isEmpty()) {
            throw new BusinessException(1001, "请至少选择一张照片");
        }
        if (urls.size() > MAX_PHOTOS_PER_UPLOAD) {
            throw new BusinessException(1001, "单次最多上传 " + MAX_PHOTOS_PER_UPLOAD + " 张照片");
        }
        persistPhotosDirect(venueId, userId, urls, VenuePhotoStatus.PUBLIC);
        // 公开照片变化：详情/列表公共缓存立即失效（key 为事务内已确定的 venueId，显式逐出）
        evictVenueEntityCache(venueId);
        invalidateDetailPublic(venueId);
        invalidateVenueListCache();
        return fetchVenuePhotos(venueId, userId);
    }

    /**
     * 平台侧批量导入门店公开相册（2026-08-22 新增，高德图片同步专用，仅 ADMIN 链路）。
     * <p>
     * 与 {@link #addVenuePhotos} 的差异：
     * <ul>
     *   <li><b>跳过 ImageContentValidator 域名白名单</b>——高德官方图床 URL
     *       （store.is.autonavi.com）非本应用存储桶前缀，走用户上传校验必然 1005 拒绝；
     *       URL 来自受信数据源（高德 place/text 响应），非用户输入，无 SSRF 面。</li>
     *   <li>createdBy = 0（存量导入，无用户归属）；status = PUBLIC 直发公开。</li>
     *   <li><b>重置式导入</b>：先物理删除该店全部高德导入记录（created_by=0）再插入
     *       最新列表——每店同步结果恒 = 最新匹配快照，错配图随重跑自愈，幂等无需去重。</li>
     * </ul>
     * 保留 TextSanitizer 清洗 + http 前缀粗校验（防脏数据）；写路径失效公共缓存。
     */
    @Transactional
    public void syncGalleryPhotos(Long venueId, List<String> urls) {
        venuePhotoRepository.deleteImportedByVenue(venueId);
        if (urls == null || urls.isEmpty()) {
            return;
        }
        int nextOrder = venuePhotoRepository.findMaxSortOrder(venueId) + 1;
        for (String raw : urls) {
            String clean = TextSanitizer.sanitize(raw, 500);
            if (clean.isEmpty() || !clean.startsWith("http")) {
                continue;
            }
            VenuePhoto photo = new VenuePhoto();
            photo.setVenueId(venueId);
            photo.setUrl(clean);
            photo.setStatus(VenuePhotoStatus.PUBLIC);
            photo.setCreatedBy(0L);
            photo.setSortOrder(nextOrder++);
            venuePhotoRepository.save(photo);
        }
        evictVenueEntityCache(venueId);
        invalidateDetailPublic(venueId);
        invalidateVenueListCache();
    }

    /**
     * 清除门店全部图片（2026-08-22 新增，图片同步纠错入口：人工判定错配后回退）。
     * 主图 image_url 置空 + 物理删除高德导入相册（created_by=0）+ 详情/列表缓存失效，
     * 门店回到「无图」状态可重新同步。幂等：门店本就无图时同样安全（delete 0 行）。
     */
    @Transactional
    public void clearImportedPhotos(Long venueId) {
        venueRepository.findByIdAndDeletedFalse(venueId).ifPresent(v -> {
            v.setImageUrl(null);
            venueRepository.save(v);
        });
        venuePhotoRepository.deleteImportedByVenue(venueId);
        evictVenueEntityCache(venueId);
        invalidateDetailPublic(venueId);
        invalidateVenueListCache();
    }

    /**
     * 删除门店照片（软删；POST /venues/{id}/photos/{photoId}/remove）。
     * 权限：上传者本人（仅可删自己的 PENDING/REJECTED，防删除他人公开贡献）或门店
     * 管理方（canManage，门店负责人对门店相册有管理权，可删任意含 PUBLIC）或管理员。
     * 写路径失效公共缓存（删除公开照片后详情/列表变化）。
     */
    @Transactional
    public void removeVenuePhoto(Long userId, Long venueId, Long photoId) {
        VenuePhoto photo = venuePhotoRepository.findByIdAndDeletedFalse(photoId)
                .orElseThrow(() -> new BusinessException(1001, "照片不存在"));
        if (!photo.getVenueId().equals(venueId)) {
            throw new BusinessException(1001, "照片不属于该门店");
        }
        boolean canManage = resourceAccessService.canCurrentUser(ResourceType.VENUE, venueId,
            ResourcePermission.VENUE_PHOTO_DELETE);
        boolean isOwnerOfNonPublic = photo.getCreatedBy().equals(userId)
                && photo.getStatus() != VenuePhotoStatus.PUBLIC;
        if (!canManage && !isOwnerOfNonPublic) {
            throw new BusinessException(1003, "仅上传者或门店管理方可删除照片");
        }
        photo.setDeleted(true);
        venuePhotoRepository.save(photo);
        if (photo.getStatus() == VenuePhotoStatus.PUBLIC) {
            evictVenueEntityCache(venueId);
            invalidateDetailPublic(venueId);
            invalidateVenueListCache();
        }
    }

    // ─── 管理端照片审核（仅 ADMIN，仿 DancerService#listAdminPhotos/updatePhotoStatus） ───

    /**
     * 管理端门店照片审核列表（仅 ADMIN，含全部状态，按上传时间倒序——新照片优先审核）。
     * status 可选过滤（缺省全部，管理员从「待审核」筛选进入待办）。
     */
    @Transactional(readOnly = true)
    public Page<AdminVenuePhotoResponse> listAdminPhotos(VenuePhotoStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), MAX_PAGE_SIZE));
        Page<Object[]> rows = venuePhotoRepository.findAdminPage(status == null ? null : status.name(), pageable);
        List<AdminVenuePhotoResponse> content = rows.getContent().stream()
                .map(r -> new AdminVenuePhotoResponse(
                        (Long) r[0], (String) r[1], VenuePhotoStatus.valueOf((String) r[2]),
                        (Long) r[3], r[4] != null ? (String) r[4] : PHOTO_VENUE_GONE_NAME,
                        (Long) r[5], r[6] != null ? (String) r[6] : PHOTO_UPLOADER_GONE_NAME,
                        (LocalDateTime) r[7]))
                .toList();
        return new PageImpl<>(content, pageable, rows.getTotalElements());
    }

    /**
     * 管理端门店照片审核（仅 ADMIN）：PENDING → PUBLIC（通过，公开）/ PENDING → REJECTED
     * （驳回，reason 可选仅服务端审计日志——上传者本人在管理入口可见 REJECTED 状态后
     * 自行删除重传，不新增站内信，见 AGENTS.md「门店照片域 · 审核」）。
     * 已审核照片重复提交幂等返回；PENDING → PUBLIC 时失效详情/列表缓存（照片对外可见变化）。
     */
    @Transactional
    public void updateVenuePhotoStatus(Long adminId, Long photoId, VenuePhotoStatus status, String reason) {
        VenuePhoto photo = venuePhotoRepository.findByIdAndDeletedFalse(photoId)
                .orElseThrow(() -> new BusinessException(1001, "照片不存在"));
        if (photo.getStatus() == status) {
            return; // 幂等：目标状态相同直接返回
        }
        if (photo.getStatus() != VenuePhotoStatus.PENDING) {
            throw new BusinessException(1003, "仅待审核照片可审核");
        }
        photo.setStatus(status);
        venuePhotoRepository.save(photo);
        if (status == VenuePhotoStatus.PUBLIC) {
            // 待审 → 公开：照片对外可见变化，详情/列表公共缓存立即失效
            // （key 依赖事务内查询结果，显式 CacheManager.evict 而非 @CacheEvict）
            evictVenueEntityCache(photo.getVenueId());
            invalidateDetailPublic(photo.getVenueId());
            invalidateVenueListCache();
        }
        log.info("管理员 {} 审核门店照片 {} → {}（门店 {}）{}", adminId, photoId, status,
                photo.getVenueId(), reason == null || reason.isBlank() ? "" : "，说明：" + TextSanitizer.sanitize(reason, 200));
    }

    /** 场所实体缓存显式逐出（照片写方法共用；key 依赖查询结果，见 VenueClaimService 同款先例） */
    private void evictVenueEntityCache(Long venueId) {
        // 全限定类型名：避免与频控字段的 caffeine Cache import 简名冲突
        org.springframework.cache.Cache cache = cacheManager.getCache(CacheConfig.CACHE_VENUE);
        if (cache != null) {
            cache.evict(venueId);
        }
    }

    // ─── 照片读取（批量公开加载 / 管理入口本人视角） ────────────────────────────────

    /**
     * 批量加载门店公开照片（PUBLIC，按 sortOrder 升序），返回 venueId → URL 列表。
     * 列表/详情/收藏消费统一入口：一次 IN 查询覆盖整页门店（同徽标/浏览量批量模式），
     * 规避 N+1。空集合返回空 Map（调用方 getOrDefault 兜底）。
     */
    public Map<Long, List<String>> loadPublicPhotosByVenueIds(List<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Collections.emptyMap();
        }
        List<Object[]> rows = venuePhotoRepository.findPublicUrlsByVenueIds(venueIds);
        Map<Long, List<String>> grouped = new java.util.HashMap<>();
        for (Object[] row : rows) {
            Long venueId = (Long) row[0];
            grouped.computeIfAbsent(venueId, k -> new ArrayList<>()).add((String) row[1]);
        }
        return grouped;
    }

    /**
     * 门店照片列表（本人视角回显，GET /venues/{id}/photos，登录可选）：
     * PUBLIC 全部 + 本人（或管理方）的 PENDING/REJECTED——普通用户看不到他人的
     * 待审/驳回照片。管理入口（venue-create 编辑模式照片区）据此回显状态与删除。
     */
    @Transactional(readOnly = true)
    public List<VenuePhotoResponse> listVenuePhotos(Long venueId) {
        return fetchVenuePhotos(venueId, UserContext.getCurrentUserId());
    }

    /** 管理入口照片回显（本人视角可见性）：PUBLIC 全部 + 本人（或管理方/管理员）的
     * PENDING/REJECTED——普通用户看不到他人的待审/驳回照片。按 sortOrder 升序。
     */
    public List<VenuePhotoResponse> fetchVenuePhotos(Long venueId, Long userId) {
        venueLookupService.findById(venueId);
        boolean canManage = resourceAccessService.canCurrentUser(ResourceType.VENUE, venueId,
            ResourcePermission.VENUE_PHOTO_DELETE);
        return venuePhotoRepository.findByVenueIdAndDeletedFalseOrderBySortOrderAscIdAsc(venueId).stream()
                .filter(p -> p.getStatus() == VenuePhotoStatus.PUBLIC
                        || canManage
                        || (userId != null && p.getCreatedBy().equals(userId)))
                .map(p -> new VenuePhotoResponse(p.getId(), p.getUrl(), p.getStatus()))
                .toList();
    }

    /**
     * 照片直插（校验 + 落库，上传/创建共用）：URL 逐个过 ImageContentValidator
     * （08-12 安全约定：图片 URL 落库字段必须挂载内容级校验，防外部 URL 绕过存储桶
     * 防线）；sortOrder 从当前最大 +1 追加，维持上传序。status 由调用方按写者身份分派。
     * <p>
     * 2026-08-27 幂等去重（批量重复入库同族修复，逻辑同 DancerService.addPhotos）：
     * 已入库（未软删）/本请求重复的 URL 整项跳过；已入库即已通过校验，跳过不重复
     * 校验（安全防线不因去重而削弱）。
     */
    private void persistPhotosDirect(Long venueId, Long userId, List<String> urls, VenuePhotoStatus status) {
        Set<String> existing = new HashSet<>(venuePhotoRepository.findUrlsByVenueIdAndDeletedFalse(venueId));
        Set<String> seen = new HashSet<>();
        int nextOrder = venuePhotoRepository.findMaxSortOrder(venueId) + 1;
        for (String raw : urls) {
            String clean = TextSanitizer.sanitize(raw, 500);
            if (clean.isEmpty() || !clean.startsWith("http")) {
                throw new BusinessException(1001, "照片地址不合法");
            }
            if (existing.contains(clean) || !seen.add(clean)) {
                continue;
            }
            imageValidator.validate(clean);
            VenuePhoto photo = new VenuePhoto();
            photo.setVenueId(venueId);
            photo.setUrl(clean);
            photo.setStatus(status);
            photo.setCreatedBy(userId);
            photo.setSortOrder(nextOrder++);
            venuePhotoRepository.save(photo);
        }
    }

    /**
     * 报告采纳联动：将门店标记为「暂停营业」（2026-08-10 新增）。
     * <p>
     * 供 {@link StatusReportService#adoptReport} 在采纳流转事务内调用（REQUIRED 传播
     * 加入同一事务）——管理员核实暂停报属实后，门店营业状态随之改为 SUSPENDED，
     * 与 updateVenue 同模式写状态变迁日志 + 失效场所/热门缓存。
     * <p>
     * 幂等：门店已是 SUSPENDED 时直接返回（不重复写变迁日志——状态未变，审计链
     * 不应产生冗余记录）；缓存逐出经 {@link @Caching} 在方法返回后仍会执行（无害）。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.CACHE_VENUE, key = "#venueId"),
            @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true)
    })
    public void markSuspendedByReport(Long venueId, Long changedBy) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        if (venue.getStatus() == VenueStatus.SUSPENDED) {
            return; // 已是暂停营业：不重复写变迁日志（幂等，审计链无冗余）
        }
        VenueStatus fromStatus = venue.getStatus();
        VenueStatusLog statusLog = new VenueStatusLog();
        statusLog.setVenueId(venue.getId());
        statusLog.setFromStatus(fromStatus);
        statusLog.setToStatus(VenueStatus.SUSPENDED);
        statusLog.setChangedBy(changedBy);
        venueStatusLogRepository.save(statusLog);
        venue.setStatus(VenueStatus.SUSPENDED);
        venueRepository.save(venue);
        // 关注者通知（同事务；幂等早退已拦截"已是 SUSPENDED"场景，此处必为实际变更）
        venueStatusWatcherService.notifyStatusChanged(
                venue.getId(), fromStatus, VenueStatus.SUSPENDED);
        // 热度失效：status 是热度响应输出（currentStatus / currentStatusDays）的组成部分，
        // 与 updateVenue 的显式逐出同模式（venueHeat 为服务内嵌 LoadingCache，不走 @CacheEvict）
        venueHeatService.invalidate(venueId);
        // 详情公共部分缓存失效：status 与 statusUpdatedAt（新增状态日志）均属公共事实
        invalidateDetailPublic(venueId);
        // 列表主查询缓存失效：营业状态是列表徽标/排序展示（2026-08-30 新增）
        invalidateVenueListCache();
    }

    /**
     * 报告采纳联动：将门店标记为「营业中」（2026-08-11 新增，与
     * {@link #markSuspendedByReport} 对称）。
     * <p>
     * 供 {@link org.quwuting.quwutingservice.venuestatusreport.service.StatusReportService#adoptReport}
     * 在采纳 RESUMED（恢复营业）类型时调用（REQUIRED 传播加入同一事务）——管理员核实
     * 恢复属实后，门店营业状态随之改回 OPEN，与 markSuspendedByReport 同模式写状态变迁
     * 日志 + 失效场所/热门缓存。
     * <p>
     * 幂等：门店已是 OPEN 时直接返回（不重复写变迁日志——状态未变，审计链不应产生
     * 冗余记录）；缓存逐出经 {@link @Caching} 在方法返回后仍会执行（无害）。
     */
    @Transactional
    @Caching(evict = {
            @CacheEvict(value = CacheConfig.CACHE_VENUE, key = "#venueId"),
            @CacheEvict(value = CacheConfig.CACHE_HOT_VENUE_IDS, allEntries = true)
    })
    public void reopenByReport(Long venueId, Long changedBy) {
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        if (venue.getStatus() == VenueStatus.OPEN) {
            return; // 已是营业中：不重复写变迁日志（幂等，审计链无冗余）
        }
        VenueStatus fromStatus = venue.getStatus();
        VenueStatusLog statusLog = new VenueStatusLog();
        statusLog.setVenueId(venue.getId());
        statusLog.setFromStatus(fromStatus);
        statusLog.setToStatus(VenueStatus.OPEN);
        statusLog.setChangedBy(changedBy);
        venueStatusLogRepository.save(statusLog);
        venue.setStatus(VenueStatus.OPEN);
        venueRepository.save(venue);
        // 关注者通知（同事务；幂等早退已拦截"已是 OPEN"场景，此处必为实际变更）
        venueStatusWatcherService.notifyStatusChanged(
                venue.getId(), fromStatus, VenueStatus.OPEN);
        venueHeatService.invalidate(venueId);
        // 详情公共部分缓存失效：status 与 statusUpdatedAt（新增状态日志）均属公共事实
        invalidateDetailPublic(venueId);
        // 列表主查询缓存失效：营业状态是列表徽标/排序展示（2026-08-30 新增）
        invalidateVenueListCache();
    }

    /**
     * 场所详情（含管理权限判定与动态计数）。
     * <p>
     * canManage 基于软鉴权上下文计算：平台管理员或门店认领人为 true，匿名请求恒为 false。
     * 该字段仅驱动前端管理入口的展示，安全边界在后端各写操作接口的角色校验。
     * <p>
     * DB 往返压缩（2026-08-13 公共部分缓存后）：
     * <ul>
     *   <li><b>公共部分</b>（与请求用户无关）：venue 实体 + 默认窗口 Top Reaction 徽标 +
     *       累计浏览量 + 状态更新时间 + 认领事实 → 内嵌 Caffeine 缓存
     *       （{@link #venueDetailPublicCache}，refresh-ahead 30s），命中时零 DB 往返；</li>
     *   <li><b>用户相关部分</b>（永远实时）：动态总数 + 个人上报标记合并单查询
     *       （{@link VenuePostRepository#findDetailStats}，匿名 userId=null 时
     *       hasMyStatusReport 恒 false）；认领申请状态仅登录时查（匿名恒 null）；
     *       canManage 由 venue 实体（缓存命中）内存计算，零查询。</li>
     * </ul>
     * 缓存全命中 + 匿名时仅 1 次 DB 往返（detailStats）；登录命中 2 次（detailStats + claim）。
     * 冷启动（公共部分回源）最多 4~5 次，回源后其余请求单飞共享。
     */
    @Transactional(readOnly = true)
    public VenueDetailResponse getVenueDetail(Long id) {
        // 公共部分：base 响应 + 状态更新时间 + 认领事实（缓存，见字段注释）
        VenueDetailPublic pub = venueDetailPublicCache.get(id);
        // 用户相关部分实时：动态总数 + "我是否已上报"合并单查询（TTL 口径
        // expires_at > now，匿名 userId=null 时 EXISTS 恒不命中自然返回 false）
        VenuePostRepository.DetailStats detailStats =
                venuePostRepository.findDetailStats(id, UserContext.getCurrentUserId(), LocalDateTime.now());
        long postCount = detailStats.getPostcount() != null ? detailStats.getPostcount() : 0L;
        // MySQL 迁移（2026-08-30）：EXISTS 投影为 0/1 整数（PG 为 boolean），按 Long 判等
        boolean hasMyStatusReport = detailStats.getHasmyreport() != null && detailStats.getHasmyreport() == 1L;
        // canManage：venue 实体（缓存命中）内存计算，零查询
        venueLookupService.findById(id);
        boolean canManage = resourceAccessService.canCurrentUser(ResourceType.VENUE, id,
            ResourcePermission.VENUE_PROFILE_EDIT);
        // 认领申请状态（2026-08-11）：未登录恒 null，登录才查（驱动「认领舞厅」
        // 菜单项禁用/审核中态，见 VenueDetailResponse javadoc）
        ClaimStatus myClaimStatus = null;
        Long currentUserId = UserContext.getCurrentUserId();
        if (currentUserId != null) {
            myClaimStatus = venueClaimRepository
                    .findFirstByUserIdAndVenueIdAndStatusOrderByCreatedAtDesc(
                            currentUserId, id, ClaimStatus.PENDING)
                    .map(VenueClaim::getStatus)
                    .orElse(null);
        }
        return new VenueDetailResponse(pub.base(), canManage, postCount, hasMyStatusReport,
                pub.statusUpdatedAt(), pub.claimed(), myClaimStatus);
    }

    /**
     * 详情接口公共部分计算（缓存 loader，勿直接调用——经 {@link #venueDetailPublicCache}）。
     * 与请求用户无关：默认窗口（近7天）徽标 + 累计浏览量 + 状态最近变更时间 + 认领事实。
     * 注意 getBadges 传 userId=null（公共聚合不含个人参与态——个人态在 /reactions/stats
     * 实时返回；base.topReactions 仅承载列表快照/兜底展示）。
     */
    private VenueDetailPublic computeVenueDetailPublic(Long id) {
        Venue venue = venueLookupService.findById(id);
        List<ReactionBadge> topReactions = venueReactionService.getBadges(
                id, null, ReactionWindow.DAYS_7);
        // 累计浏览量（全量历史口径，单店 COUNT 命中 (venue_id, view_date) 索引，毫秒级）：
        // viewCount 是 VenueResponse 事实字段，详情基础响应同样传真实值（见 Mapper 四参重载 javadoc）
        long viewCount = venueViewRepository.countByVenueId(id);
        // 2026-08-20 门店照片域：详情基础响应照片改读独立表 PUBLIC（JSON 列废弃）
        List<String> photos = loadPublicPhotosByVenueIds(List.of(id)).getOrDefault(id, List.of());
        VenueResponse base = venueResponseMapper.toResponse(venue, topReactions, false, viewCount, photos);
        return new VenueDetailPublic(base,
                venueStatusLogRepository.findLatestStatusChangeTime(id),
                venue.getClaimedBy() != null);
    }

    /**
     * 详情公共部分缓存显式失效（写路径调用：场所编辑 / 状态变更 / 认领审批）。
     * 与 {@link VenueHeatService#invalidate} 同模式——内嵌 LoadingCache 不走
     * Spring CacheManager，@CacheEvict 无法表达，必须显式调用。
     */
    public void invalidateDetailPublic(Long venueId) {
        venueDetailPublicCache.invalidate(venueId);
    }

    /**
     * 详情接口「公共部分」值对象（与请求用户无关，见 {@link #venueDetailPublicCache} 注释）。
     */
    private record VenueDetailPublic(VenueResponse base, LocalDateTime statusUpdatedAt, boolean claimed) {}

    /**
     * 场所列表：筛选 + 排序 + 距离半径 + 分页。
     * <p>
     * <b>排序由服务端在库内完成</b>（分页正确性要求排序与分页同一查询，见 AGENTS.md「复合评分排序」）：
     * <ul>
     *   <li>recommended（默认）：复合评分 = 行为热度（{@code VenueRepository.HEAT_SCORE} 镜像公式，
     *       2026-08-08 与热度页统一，见「场所热度」章节；2026-08-27 起行为热度=0 的门店
     *       运营权重不生效——零人气门店不靠权重霸榜；<b>2026-09-01 距离加成移除</b>——
     *       距离的唯一影响是 {@code radiusKm} 半径筛选（默认 300km），排序纯看热度，
     *       本地近 ≠ 靠前（用户实证：热度 2 的本地店不应压过热度 17 的外地店）；</li>
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
     * <p>
     * {@code tag}（可选，2026-08-12 新增「龙女」快捷筛选）：仅返回 tags 含该标签子串的
     * 场所（Service 层包装为 %xx% 后按 LIKE 匹配，口径见 {@link VenueRepository#LIST_FILTERS}）。
     * 与城市/状态/热门/距离等筛选正交可叠加；默认不传 = 不过滤。
     */
    @Transactional(readOnly = true)
    public Page<VenueResponse> listVenues(String city, String district,
                                          VenueStatus status, String keyword,
                                          Double latitude, Double longitude,
                                          String window, String sort, Double radiusKm,
                                          Boolean hot, String tag,
                                          int page, int size) {
        // 关键词检索模型 v2（2026-09-02，docs/agents/07-list-page.md「关键词匹配口径」）：
        //   - 归一：trim + 长度上限 → 按分隔符拆词 → 每词 LIKE 字面转义（!、%、_，
        //     与 KW_MATCH / RELEVANCE_KEYS 的 ESCAPE '!' 配套）；
        //   - 单词：整串子串匹配（KW_MATCH 六字段 + 门店同步别名）+ kwPrefix 前缀 pattern
        //     （驱动搜索相关度排序，见 RELEVANCE_KEYS）；
        //   - 多词：逐词 findIdsByKeyword 行集探测求交集 → :filterIds 白名单回灌主查询
        //     （keyword=null + v.id IN 交集；多词是宽意图，结果量小，相关度 CASE 停用，
        //     排序走热度——精确性由交集保证）；
        //   - 空词：keyword 维度不过滤（与旧行为一致）。
        // 停业/暂停门店全程不过滤（结果口径 = 搜索只承诺「匹配」不承诺「状态」，OPEN 仅
        // 在 RECOMMENDED 相关度排序内加权前置，见 RELEVANCE_KEYS 注释——2026-09-02 用户拍板）。
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(page, size);
        List<String> searchTerms = splitSearchTerms(keyword);
        String keywordPattern = null;
        String kwPrefixPattern = null;
        Set<Long> filterIds = null;
        if (searchTerms.size() == 1) {
            keywordPattern = "%" + searchTerms.get(0) + "%";
            kwPrefixPattern = searchTerms.get(0) + "%";
        } else if (searchTerms.size() > 1) {
            filterIds = intersectKeywordIds(searchTerms);
            if (filterIds.isEmpty()) {
                return new PageImpl<>(Collections.emptyList(), pageable, 0);
            }
        }
        String tagPattern = StringUtils.hasText(tag) ? "%" + tag.trim() + "%" : null;
        boolean hasCoords = latitude != null && longitude != null;
        // radiusKm 为 null / ≤ 0 视为不限（前端 0 = 不限，防御性归一）
        Double radius = (radiusKm != null && radiusKm > 0) ? radiusKm : null;
        VenueSortMode sortMode = VenueSortMode.from(sort);
        // 热门场所 ID 集合（5min 缓存）：列表查询前获取，一次取用双职责——
        // ① hot 筛选参数（hotOnly=true 时按集合过滤）② 响应 isHot 标记
        Set<Long> hotVenueIds = venueLookupService.getHotVenueIds();
        boolean hotOnly = Boolean.TRUE.equals(hot);
        Page<Venue> result = dispatchListQuery(sortMode, blankToNull(city), blankToNull(district),
                status, keywordPattern, kwPrefixPattern, filterIds, tagPattern,
                hasCoords, latitude, longitude, radius,
                POSITIVE_REACTION_CODES, pointsProperties.heatWeight(),
                hotOnly, hotVenueIds, pageable);
        // 批量查询整页场所的 Top Reaction 徽标，避免逐条查询造成的 N+1（见 VenueReactionService#batchGetBadges）
        List<Long> venueIds = result.getContent().stream().map(Venue::getId).toList();
        Map<Long, List<ReactionBadge>> reactionsByVenue =
                venueReactionService.batchGetBadges(venueIds, UserContext.getCurrentUserId(),
                        ReactionWindow.from(window));
        // 批量累计浏览量（2026-08-12 列表卡片「👁 浏览数」数据源）：一次 IN + GROUP BY
        // 覆盖整页，避免逐条 COUNT 的 N+1；口径 = qwt_venue_views 全量行数（按天按来源去重 PV 含匿名，
        // 与 viewCount30d 同源同口径的全量版，见 VenueViewRepository#countByVenueIds javadoc）
        Map<Long, Long> viewCounts = venueIds.isEmpty() ? Collections.emptyMap()
                : venueViewRepository.countByVenueIds(venueIds).stream()
                        .collect(Collectors.toMap(
                                row -> (Long) row[0],
                                row -> ((Number) row[1]).longValue()));
        // 批量公开照片（2026-08-20 门店照片域：一次 IN 覆盖整页，规避 N+1，同徽标/浏览量批量模式）
        Map<Long, List<String>> photosByVenue = loadPublicPhotosByVenueIds(venueIds);
        // 批量今晚热度角标（2026-08-29：一次 IN + GROUP BY 覆盖整页，中性「N人报过」，
        // ≥3 人独立上报才生成——列表公共面克制，见 CrowdReportService#badgeTextsByVenue）
        Map<Long, String> crowdBadges = crowdReportService.badgeTextsByVenue(venueIds);
        // 批量「最新上报」行（2026-08-29：每店窗口内最新一条 → 克制文案
        // 「{时间} · {标识}舞友上报」，有上报即生成——实时动态与角标互补，
        // 见 CrowdReportService#latestTextsByVenue）
        Map<Long, String> crowdLatestTexts = crowdReportService.latestTextsByVenue(venueIds);
        // 批量门店报告「最新上报」行文案（2026-09-04：公示期内每店最新一条公示中报告 →
        // 「{时间} · {类型} · 舞友上报」，与今晚热度文案共用同一行控件轮播展示——2026-09-04
        // 用户拍板推翻同日「中性角标」方案；活跃口径与详情页公告条同源——列表行有文案 ⇔
        // 详情页有公告条，见 StatusReportLatestService#latestTextsByVenue）
        Map<Long, String> statusLatestTexts = statusReportLatestService.latestTextsByVenue(venueIds);
        return result.map(v -> venueResponseMapper.toResponse(
                v, reactionsByVenue.getOrDefault(v.getId(), Collections.emptyList()),
                hotVenueIds.contains(v.getId()),
                viewCounts.getOrDefault(v.getId(), 0L),
                photosByVenue.getOrDefault(v.getId(), List.of()),
                crowdBadges.get(v.getId()),
                crowdLatestTexts.get(v.getId()),
                false,
                statusLatestTexts.get(v.getId())));
    }

    /**
     * 列表查询分流（排序 × 坐标 × 半径 的显式矩阵，见 {@link #listVenues} 注释）。
     * 坐标必为 null 或双非 null（hasCoords 为准），distance 数学查询只在 hasCoords=true 分支被调用。
     * {@code hotOnly}/{@code hotIds} 透传给全部变体（热门筛选与排序正交，见 LIST_FILTERS 注释）。
     * <p>
     * 无坐标分支走 {@link #venueListCache}（2026-08-30 性能优化）：全国 / 显式城市
     * 视角的行为热度排序是公共数据（与请求用户无关、低频变化），60s 缓存 + 写路径
     * 显式失效（见 {@link #invalidateVenueListCache}）；带坐标分支（结果集受半径
     * 筛选约束，与请求者位置相关）恒实时查询。
     */
    private Page<Venue> dispatchListQuery(VenueSortMode sortMode, String city, String district,
                                          VenueStatus status, String keywordPattern, String kwPrefixPattern,
                                          Set<Long> filterIds, String tagPattern,
                                          boolean hasCoords, Double latitude, Double longitude,
                                          Double radius, List<String> positiveCodes, int pointsWeight,
                                          boolean hotOnly, Set<Long> hotIds, PageRequest pageable) {
        // 无坐标分支：仅单串 keyword（filterIds 为 null）可入缓存——多词 AND 的白名单集合
        // 与请求参数强耦合（无法收敛进 VenueListKey），且为精确意图的窄结果，恒实时查询。
        if (!hasCoords && filterIds == null) {
            return venueListCache.get(new VenueListKey(
                    sortMode, city, district, status, keywordPattern, kwPrefixPattern, tagPattern,
                    hotOnly, pageable.getPageNumber(), pageable.getPageSize()));
        }
        // 多词 AND 无坐标（filterIds != null 且 !hasCoords）：不走缓存（见上），直接实时
        // <b>无坐标变体</b>——latitude/longitude 为 null 时不可传入 double 形参的
        // searchRanked/searchNearest（自动拆箱 NPE，2026-09-02 多词搜索实测），
        // 分派口径与 loadVenueListPage 的无坐标 loader 完全一致（RECOMMENDED/DISTANCE
        // 降级共用 searchRankedNoLocation）。
        if (!hasCoords) {
            return switch (sortMode) {
                case RECOMMENDED, DISTANCE -> venueRepository.searchRankedNoLocation(
                        city, district, status, keywordPattern, kwPrefixPattern, filterIds, tagPattern,
                        positiveCodes, pointsWeight, hotOnly, hotIds, pageable);
                case HEAT -> venueRepository.searchHeat(
                        city, district, status, keywordPattern, filterIds, tagPattern,
                        positiveCodes, pointsWeight, hotOnly, hotIds, pageable);
                case NEWEST -> venueRepository.searchNewest(
                        city, district, status, keywordPattern, filterIds, tagPattern,
                        hotOnly, hotIds, pageable);
            };
        }
        return switch (sortMode) {
            case RECOMMENDED -> venueRepository.searchRanked(city, district, status, keywordPattern, kwPrefixPattern,
                    filterIds, tagPattern,
                    latitude, longitude, radius, positiveCodes, pointsWeight, hotOnly, hotIds, pageable);
            case DISTANCE -> venueRepository.searchNearest(city, district, status, keywordPattern, filterIds, tagPattern,
                    latitude, longitude, radius, hotOnly, hotIds, pageable);
            case HEAT -> hasCoords && radius != null
                    ? venueRepository.searchHeatWithinRadius(city, district, status, keywordPattern, filterIds, tagPattern,
                            latitude, longitude, radius, positiveCodes, pointsWeight, hotOnly, hotIds, pageable)
                    : venueRepository.searchHeat(city, district, status, keywordPattern, filterIds, tagPattern,
                            positiveCodes, pointsWeight, hotOnly, hotIds, pageable);
            case NEWEST -> hasCoords && radius != null
                    ? venueRepository.searchNewestWithinRadius(city, district, status, keywordPattern, filterIds, tagPattern,
                            latitude, longitude, radius, hotOnly, hotIds, pageable)
                    : venueRepository.searchNewest(city, district, status, keywordPattern, filterIds, tagPattern,
                            hotOnly, hotIds, pageable);
        };
    }

    /**
     * 列表主查询无坐标视图缓存 loader（经 {@link #venueListCache} 调用，勿直接调用）。
     * 按 {@link VenueListKey#sortMode()} 分发无坐标排序口径：RECOMMENDED 与 DISTANCE
     * 降级共用 searchRankedNoLocation（无坐标无法按距离排序，防御性回退推荐排序）；
     * HEAT / NEWEST 排序口径不同，各走其专用查询。
     */
    private Page<Venue> loadVenueListPage(VenueListKey key) {
        PageRequest pageable = PageRequest.of(key.page(), key.size());
        // hotIds 全局集合（5min 缓存）：loader 回源时取当前集合，缓存条目内的 hotOnly
        // 过滤随 5min 集合刷新自然兜底（60s TTL < 5min，无需联动失效）
        Set<Long> hotIds = venueLookupService.getHotVenueIds();
        return switch (key.sortMode()) {
            case RECOMMENDED, DISTANCE -> venueRepository.searchRankedNoLocation(
                    key.city(), key.district(), key.status(), key.keywordPattern(), key.kwPrefixPattern(),
                    null, key.tagPattern(),
                    POSITIVE_REACTION_CODES, pointsProperties.heatWeight(), key.hotOnly(), hotIds, pageable);
            case HEAT -> venueRepository.searchHeat(
                    key.city(), key.district(), key.status(), key.keywordPattern(),
                    null, key.tagPattern(),
                    POSITIVE_REACTION_CODES, pointsProperties.heatWeight(), key.hotOnly(), hotIds, pageable);
            case NEWEST -> venueRepository.searchNewest(
                    key.city(), key.district(), key.status(), key.keywordPattern(),
                    null, key.tagPattern(),
                    key.hotOnly(), hotIds, pageable);
        };
    }

    /**
     * 列表主查询无坐标视图缓存显式失效（写路径调用，与 {@link #invalidateDetailPublic}
     * 同模式——内嵌 LoadingCache 不走 Spring CacheManager，@CacheEvict 无法表达）。
     * 门店新增/编辑/状态变更/照片变化后调用：列表内容或排序立即生效，
     * 不依赖 60s TTL 兜底。热度累积（reaction/favorite/view）变化不失效，靠 TTL。
     */
    public void invalidateVenueListCache() {
        venueListCache.invalidateAll();
    }

    /** 有场所的城市列表（按场所数倒序），供前端热门城市选择。
     *  2026-08-30 性能优化：5min TTL 缓存（CacheConfig.CACHE_CITY_STATS）——城市列表
     *  仅门店新增/编辑才变化（实测每次进入首页重查 321ms），createVenue/updateVenue
     *  写路径 allEntries 逐出（见两方法缓存注解）。 */
    @Cacheable(value = CacheConfig.CACHE_CITY_STATS, sync = true)
    @Transactional(readOnly = true)
    public List<CityStatsResponse> listCityStats() {
        return venueRepository.findCityStats().stream()
                .map(p -> new CityStatsResponse(p.getCity(), p.getVenueCount()))
                .toList();
    }

    /**
     * 门店名称联想建议（2026-09-02 搜索增强，GET /venues/suggest，首页搜索框联想下拉）。
     * <p>
     * 联想键 = <b>完整单串</b>：含分隔符（空格/逗号/顿号/斜杠）的多词输入不联想
     * （拆词组合是精确搜索意图，走 GET /venues 列表接口——联想只服务「边打店名边补全」）。
     * 匹配与排序（前缀命中 &gt; 中缀 &gt; 门店同步别名，OPEN 前置，新收录兜底）在
     * {@link org.quwuting.quwutingservice.venue.repository.VenueRepository#suggestByName}
     * 库内完成；limit 由前端传、服务端收敛至 {@link #MAX_SUGGEST_SIZE}。
     * 数据规模数百级、前缀命中通常唯一，无需检索服务/热词聚合设施。
     */
    @Transactional(readOnly = true)
    public List<VenueSuggestResponse> listVenueSuggestions(String keyword, int limit) {
        if (!StringUtils.hasText(keyword)) return List.of();
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        if (trimmed.matches(".*" + KEYWORD_SPLIT_REGEX + ".*")) return List.of();
        String term = escapeLikeLiteral(trimmed);
        int size = Math.min(Math.max(1, limit), MAX_SUGGEST_SIZE);
        List<Venue> venues = venueRepository.suggestByName(
                term + "%", "%" + term + "%", PageRequest.of(0, size));
        return venues.stream()
                .map(v -> new VenueSuggestResponse(
                        v.getId(), v.getName(), v.getCity(),
                        v.getDistrict() == null ? "" : v.getDistrict(),
                        v.getStatus() == null ? "" : v.getStatus().name()))
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

    // ===== 关键词检索 helpers（2026-09-02，与 repository 的 KW_MATCH/RELEVANCE_KEYS 配套） =====

    /**
     * 关键词拆词归一：trim + 截长 → 按 {@link #KEYWORD_SPLIT_REGEX} 拆词 → 去空 token →
     * 截断至 {@link #MAX_KEYWORD_TOKENS} → 每词 LIKE 字面转义。返回空列表 = 无关键词。
     * 转义（escapeLikeLiteral）后词内 {@code !}/{@code %}/{@code _} 为字面量，
     * 与 repository 谓词/排序里的 {@code ESCAPE '!'} 配套，防通配符注入全表命中。
     */
    private List<String> splitSearchTerms(String keyword) {
        if (!StringUtils.hasText(keyword)) return List.of();
        String trimmed = keyword.trim();
        if (trimmed.length() > MAX_KEYWORD_LENGTH) trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
        return Arrays.stream(trimmed.split(KEYWORD_SPLIT_REGEX))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .limit(MAX_KEYWORD_TOKENS)
                .map(VenueService::escapeLikeLiteral)
                .toList();
    }

    /**
     * LIKE 字面子串转义（与 repository 的 {@code ESCAPE '!'} 配套）：
     * escape 字符 {@code !} 先转义自身（! → !!），再转义通配符 % / _（→ !% / !_）——
     * 顺序不可颠倒（若先转 %/_，其前缀 ! 会被后续的 !→!! 误转成 !!%）。
     * 选择 '!' 而非反斜杠：HQL 语义层要求 escape 字面量恰为单字符且 MySQL 对
     * backslash 有字符串字面转义语义（双歧义），'!' 在 MySQL/JPQL 均无歧义。
     */
    private static String escapeLikeLiteral(String term) {
        return term.replace("!", "!!").replace("%", "!%").replace("_", "!_");
    }

    /**
     * 多词 AND 行集探测：逐词 {@code findIdsByKeyword}（%词% pattern，命中口径 =
     * KW_MATCH 六字段 + 同步别名）求交集。任一词零命中 → 恒为空集（上游短路返回空页，
     * 不再发主查询）。数据规模数百级：每词一次 LIKE 全扫毫秒级，最多
     * {@link #MAX_KEYWORD_TOKENS} 次 + 一次内存交集。
     */
    private Set<Long> intersectKeywordIds(List<String> terms) {
        Set<Long> acc = null;
        for (String term : terms) {
            List<Long> ids = venueRepository.findIdsByKeyword("%" + term + "%");
            if (ids.isEmpty()) return Collections.emptySet();
            Set<Long> current = new HashSet<>(ids);
            if (acc == null) {
                acc = current;
            } else {
                acc.retainAll(current);
                if (acc.isEmpty()) return acc;
            }
        }
        return acc == null ? Collections.emptySet() : acc;
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
