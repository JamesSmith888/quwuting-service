package org.quwuting.quwutingservice.dancer.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.dancer.DancerTagCode;
import org.quwuting.quwutingservice.dancer.dto.request.RecognizeDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.response.*;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerPhoto;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognition;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognitionTag;
import org.quwuting.quwutingservice.dancer.entity.DancerVenue;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVenueRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.enums.PointsTargetType;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/**
 * 舞伴生态体系核心服务（领域边界：认可/标签/资料/场所关系，独立于舞厅 reaction——
 * 舞厅 reaction 评价"场所"，舞伴认可评价"个人"，二者互不干扰，见 AGENTS.md「舞伴生态体系」）。
 * <p>
 * <b>隐私与真实性边界（本模块第一约束）</b>：
 * <ul>
 *   <li>默认 PENDING（审核中）：所有新资料必须经管理员认证后才公开——禁止默认创建
 *       大量未授权人物主页（先认证、后展示）；</li>
 *   <li>普通用户对本模块唯一可写的公开影响 = 「认可 + 字典标签」（无照片上传、无敏感信息、
 *       不公开私人关系）；标签字典后台维护，禁止自由创建；</li>
 *   <li>舞伴本人（createdBy 匹配）与管理员可<b>编辑资料</b>与<b>上传相册照片</b>——
 *       照片逐张 PENDING 审核后公开（见 DancerPhotoStatus / AGENTS.md「相册与照片审核」）；
 *       编辑不重置公开状态，REJECTED 资料编辑后自动回到 PENDING 重新送审；</li>
 *   <li>可见性规则：NORMAL 公开；PENDING/HIDDEN/REJECTED 仅创建人本人与平台管理员可见。</li>
 * </ul>
 * <b>认可模型</b>：每日一记（一个用户对一个舞伴每天至多一行，取消即物理删除当日记录），
 * 与 Reaction 快速反馈系统同源——避免老数据永久占优势、防刷票；次日自动恢复可认可状态。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DancerService {

    /** 一次认可最多携带的标签数（产品规则：认可理由应聚焦，防止标签堆砌） */
    public static final int MAX_TAGS_PER_RECOGNITION = 3;

    /** 列表卡片最多展示的 Top 标签数（过多挤占卡片空间，详情页展示全量） */
    private static final int LIST_TOP_TAGS = 3;

    /** 详情页"最近认可"动态信息展示的天数（含今日） */
    private static final int RECENT_DAILY_DAYS = 7;

    /** 管理端照片列表中舞伴已软删时的昵称占位（审核页仍可辨识来源，同 AdminDancerResponse） */
    private static final String PHOTO_OWNER_GONE_NAME = "未知舞伴";

    private final DancerRepository dancerRepository;
    private final DancerVenueRepository dancerVenueRepository;
    private final DancerRecognitionRepository recognitionRepository;
    private final DancerRecognitionTagRepository recognitionTagRepository;
    private final DancerPhotoRepository photoRepository;
    private final DancerAggregateService aggregateService;
    private final VenueLookupService venueLookupService;
    private final MessageService messageService;
    private final org.quwuting.quwutingservice.points.service.PointsService pointsService;

    @PersistenceContext
    private EntityManager entityManager;

    // ─── 创建（两条通道：主动注册 → PENDING；后台创建 → NORMAL） ─────────────────

    /**
     * 创建舞伴资料。
     *
     * @param adminApproved true = 后台创建（管理员，status=NORMAL 直接公开）；false = 主动注册（status=PENDING 待认证）
     * @return 新建舞伴 ID
     */
    @Transactional
    public Long createDancer(Long userId, UpsertDancerRequest request, boolean adminApproved) {
        String nickname = TextSanitizer.sanitize(request.nickname(), 30);
        if (nickname.isEmpty()) {
            throw new BusinessException(1001, "昵称不能为空");
        }
        Dancer dancer = new Dancer();
        dancer.setNickname(nickname);
        dancer.setAvatarUrl(TextSanitizer.sanitize(request.avatarUrl(), 500));
        dancer.setBio(TextSanitizer.sanitize(request.bio(), 300));
        dancer.setGender(TextSanitizer.sanitize(request.gender(), 20));
        dancer.setCity(TextSanitizer.sanitize(request.city(), 50));
        dancer.setStatus(adminApproved ? DancerStatus.NORMAL : DancerStatus.PENDING);
        dancer.setCreatedBy(userId);
        dancer = dancerRepository.save(dancer);

        if (request.homeVenueId() != null) {
            attachHomeVenue(dancer.getId(), request.homeVenueId());
        }
        return dancer.getId();
    }

    /** 关联常驻舞厅（存在性校验在 VenueLookupService 缓存层；重复关联幂等，事务失败整体回滚） */
    private void attachHomeVenue(Long dancerId, Long venueId) {
        venueLookupService.findById(venueId); // 场所不存在 → BusinessException，整个创建回滚
        if (dancerVenueRepository.findByDancerIdAndVenueIdAndRelationAndDeletedFalse(
                dancerId, venueId, DancerVenueRelation.HOME).isEmpty()) {
            DancerVenue dv = new DancerVenue();
            dv.setDancerId(dancerId);
            dv.setVenueId(venueId);
            dv.setRelation(DancerVenueRelation.HOME);
            dancerVenueRepository.save(dv);
        }
    }

    // ─── 编辑（本人 / 管理员，全量覆盖可编辑字段） ───────────────────────────────

    /**
     * 更新舞伴资料（本人 createdBy 匹配 或 平台管理员）。
     * <ul>
     *   <li><b>全量覆盖</b>可编辑字段（nickname/avatarUrl/bio/gender/city，与创建同请求模型），
     *       status/createdBy 不可由本接口变更；</li>
     *   <li><b>homeVenueId = HOME 关系的完整替换语义</b>：传 null 清除全部 HOME；
     *       传新值替换为唯一新 HOME（删除旧 HOME 关系、幂等建立新关系）——
     *       编辑是"常驻舞厅变更"而非"追加"，防止多次编辑累积多个"常去"；</li>
     *   <li><b>REJECTED → PENDING 自动重审</b>：驳回后本人修改资料即重新送审，
     *       兑现驳回通知"可修改资料后重新提交"的产品承诺（2026-08-10 补齐，根因见
     *       AGENTS.md「舞伴生态体系 · 本人编辑」）；其余状态编辑后保持不变；</li>
     *   <li>返回更新后详情（前端据此整体刷新，无需二次请求）。</li>
     * </ul>
     */
    @Transactional
    public DancerDetailResponse updateDancer(Long userId, Long dancerId, UpsertDancerRequest request,
                                             UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canManage(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "无编辑权限");
        }
        String nickname = TextSanitizer.sanitize(request.nickname(), 30);
        if (nickname.isEmpty()) {
            throw new BusinessException(1001, "昵称不能为空");
        }
        dancer.setNickname(nickname);
        dancer.setAvatarUrl(TextSanitizer.sanitize(request.avatarUrl(), 500));
        dancer.setBio(TextSanitizer.sanitize(request.bio(), 300));
        dancer.setGender(TextSanitizer.sanitize(request.gender(), 20));
        dancer.setCity(TextSanitizer.sanitize(request.city(), 50));
        // REJECTED 资料编辑后重新送审（管理员直改仍由管理员后续流转，此处不覆盖）
        if (dancer.getStatus() == DancerStatus.REJECTED) {
            dancer.setStatus(DancerStatus.PENDING);
        }
        dancerRepository.save(dancer);
        replaceHomeVenue(dancerId, request.homeVenueId());
        return getDetail(dancerId, userId, currentRole);
    }

    /** HOME 关系完整替换：先软删全部旧 HOME，再按需建立新 HOME（null = 清除常驻舞厅） */
    private void replaceHomeVenue(Long dancerId, Long newVenueId) {
        List<DancerVenue> existingHomes = dancerVenueRepository.findByDancerIdAndRelationAndDeletedFalse(
                dancerId, DancerVenueRelation.HOME);
        for (DancerVenue dv : existingHomes) {
            if (newVenueId != null && dv.getVenueId().equals(newVenueId)) {
                return; // 目标 HOME 已存在：无需变更（其余旧 HOME 罕见情况由下方兜底清掉）
            }
            dv.setDeleted(true);
            dancerVenueRepository.save(dv);
        }
        if (newVenueId != null) {
            attachHomeVenue(dancerId, newVenueId);
        }
    }

    // ─── 列表 / 详情 ───────────────────────────────────────────────────────────

    /**
     * 公开舞伴列表（仅 NORMAL），按近7天认可倒序分页。
     * 批量编排（N+1 规避）：单条分页 SQL 带计数 → 一次 IN 查询覆盖整页的
     * Top 标签 + 一次 IN JOIN 查询覆盖常驻舞厅名 + 一次 IN 查询覆盖个人"今日已认可"。
     */
    @Transactional(readOnly = true)
    public Page<DancerSummaryResponse> listPublic(String city, int page, int size, Long currentUserId) {
        LocalDateTime now = LocalDateTime.now();
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Object[]> rows = dancerRepository.findPublicPage(
                city, LocalDate.now().atStartOfDay() /* 今日锚点 = 今日0点 */,
                now.minusDays(7), now.minusDays(30), pageable);
        if (rows.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, rows.getTotalElements());
        }
        List<Object[]> content = rows.getContent();
        List<Long> ids = content.stream().map(r -> (Long) r[0]).toList();

        // 行内计数：Object[]{id, ..., count_all(6), count_today(7), count_7d(8)}
        Map<Long, long[]> countsById = new HashMap<>();
        for (Object[] row : content) {
            countsById.put((Long) row[0], new long[]{
                    ((Number) row[6]).longValue(), ((Number) row[7]).longValue(), ((Number) row[8]).longValue()});
        }
        Map<Long, List<DancerTagStat>> tagsById = fetchTopTags(ids);
        Map<Long, String> homeVenueNameById = fetchHomeVenueNames(ids);
        Map<Long, String> coverPhotoUrlById = fetchCoverPhotoUrls(ids);
        Set<Long> myTodayIds = fetchMyTodayIds(ids, currentUserId);

        List<DancerSummaryResponse> summaries = new ArrayList<>(content.size());
        for (Object[] row : content) {
            Long id = (Long) row[0];
            long[] counts = countsById.get(id);
            summaries.add(new DancerSummaryResponse(
                    id, (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                    DancerStatus.NORMAL, homeVenueNameById.get(id), coverPhotoUrlById.get(id),
                    counts[0], counts[2], counts[1],
                    myTodayIds.contains(id), tagsById.getOrDefault(id, Collections.emptyList())));
        }
        return new PageImpl<>(summaries, pageable, rows.getTotalElements());
    }

    /** 公开舞伴的常驻城市词表（列表页城市筛选数据源，聚合真实数据） */
    @Transactional(readOnly = true)
    public List<String> listPublicCities() {
        return dancerRepository.findPublicCities();
    }

    /**
     * 舞伴详情（软鉴权：登录时返回个人状态）。
     * 可见性规则：NORMAL 所有人可见；PENDING/HIDDEN/REJECTED 仅创建人本人 + 平台管理员。
     * 相册照片按身份过滤：非本人仅 PUBLIC；本人/管理员返回全量（含待审/驳回态，编辑页回显）。
     */
    @Transactional(readOnly = true)
    public DancerDetailResponse getDetail(Long dancerId, Long currentUserId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, currentUserId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        boolean isMine = currentUserId != null && dancer.getCreatedBy().equals(currentUserId);
        boolean showAllPhotos = isMine || currentRole == UserRole.ADMIN;
        boolean myToday = currentUserId != null && recognitionRepository
                .findByUserIdAndDancerIdAndRecognitionDate(currentUserId, dancerId, LocalDate.now()).isPresent();
        // 收到积分统计（2026-08-10 V2：窗口口径与热度公式一致 = 近30天截至昨日，
        // 全量 = 历史累计；同源口径见 PointsTransactionRepository#sumReceived*）
        LocalDateTime windowStart = LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime windowEnd = LocalDate.now().atStartOfDay();
        long pointsReceivedTotal = pointsService.receivedTotal(PointsTargetType.DANCER, dancerId);
        long pointsReceived30d = pointsService.receivedSince(PointsTargetType.DANCER, dancerId, windowStart, windowEnd);
        return new DancerDetailResponse(
                dancer.getId(), dancer.getNickname(), dancer.getAvatarUrl(), dancer.getBio(),
                dancer.getGender(), dancer.getCity(), dancer.getStatus(),
                isMine, myToday, buildStats(dancerId),
                pointsReceivedTotal, pointsReceived30d,
                fetchPhotos(dancerId, showAllPhotos),
                fetchAllTags(dancerId), fetchVenues(dancerId));
    }

    /** 单舞伴标签聚合（GET /dancers/{id}/tags，公开接口；先校验舞伴可见性） */
    @Transactional(readOnly = true)
    public List<DancerTagStat> getTags(Long dancerId, Long currentUserId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, currentUserId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        return fetchAllTags(dancerId);
    }

    // ─── 认可（每日一记 toggle 模型） ──────────────────────────────────────────

    /**
     * 切换认可状态（toggle：今日未认可 → 认可（可携带标签），今日已认可 → 取消）。
     * <ul>
     *   <li>未命中今日记录 → 插入 + 写标签（贡献 +1，所有窗口）；</li>
     *   <li>命中今日记录 → 物理删除认可 + 级联删除当日标签（贡献 -1，所有窗口）；</li>
     *   <li>同日并发重复插入 → 唯一约束冲突，幂等视为已认可（防连点/多端竞态）。</li>
     * </ul>
     * 认可目标须对当前用户可见（NORMAL 或本人资料）。
     */
    @Transactional
    public RecognizeResponse toggleRecognize(Long userId, Long dancerId, RecognizeDancerRequest request,
                                             UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }

        LocalDate today = LocalDate.now();
        Optional<DancerRecognition> existing = recognitionRepository
                .findByUserIdAndDancerIdAndRecognitionDate(userId, dancerId, today);
        boolean recognized;
        if (existing.isPresent()) {
            // 取消当日认可：物理删除认可 + 级联删除其标签（"取消当天认可"语义 = 当日贡献整体移除）
            recognitionTagRepository.deleteByRecognitionId(existing.get().getId());
            recognitionRepository.delete(existing.get());
            recognized = false;
        } else {
            List<String> tags = validateAndDedupeTags(request);
            DancerRecognition recognition = new DancerRecognition();
            recognition.setUserId(userId);
            recognition.setDancerId(dancerId);
            recognition.setRecognitionDate(today);
            try {
                recognition = recognitionRepository.save(recognition);
            } catch (DataIntegrityViolationException e) {
                // 并发竞态：另一请求已创建今日记录，幂等视为已认可
                log.debug("toggle 认可并发冲突，幂等忽略: userId={}, dancerId={}", userId, dancerId);
                entityManager.clear();
                aggregateService.invalidate(dancerId);
                return new RecognizeResponse(true, buildStats(dancerId));
            }
            for (String tag : tags) {
                DancerRecognitionTag t = new DancerRecognitionTag();
                t.setRecognitionId(recognition.getId());
                t.setDancerId(dancerId);
                t.setUserId(userId);
                t.setTag(tag);
                recognitionTagRepository.save(t);
            }
            recognized = true;
        }
        aggregateService.invalidate(dancerId);
        return new RecognizeResponse(recognized, buildStats(dancerId));
    }

    /** 校验并去重标签：全部须命中字典；去重（保持顺序）；最多 MAX_TAGS_PER_RECOGNITION 个 */
    private List<String> validateAndDedupeTags(RecognizeDancerRequest request) {
        List<String> raw = request == null || request.tags() == null
                ? Collections.emptyList() : request.tags();
        List<String> result = new ArrayList<>();
        for (String tag : raw) {
            if (!DancerTagCode.isValid(tag)) {
                throw new BusinessException(1001, "无效的舞伴标签");
            }
            if (!result.contains(tag)) {
                result.add(tag);
            }
        }
        if (result.size() > MAX_TAGS_PER_RECOGNITION) {
            throw new BusinessException(1001, "每次认可最多选择" + MAX_TAGS_PER_RECOGNITION + "个标签");
        }
        return result;
    }

    // ─── 我的认可 / 我的舞伴主页 ───────────────────────────────────────────────

    /**
     * 我的认可记录（个人中心回顾视图，需登录）：最近一次认可在前；同一舞伴多日认可
     * 只展示最近一条（dedupe by dancerId 且保留最早出现——findMyRecognitions 已按
     * createdAt 倒序，首次出现即最近一条）。已被软删的舞伴跳过。
     */
    @Transactional(readOnly = true)
    public List<MyDancerRecognitionResponse> listMyRecognitions(Long userId) {
        List<Object[]> rows = recognitionRepository.findMyRecognitions(userId);
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> recognitionIds = rows.stream().map(r -> (Long) r[0]).toList();
        List<Long> dancerIds = rows.stream().map(r -> (Long) r[1]).distinct().toList();
        Map<Long, Dancer> dancersById = new HashMap<>();
        for (Dancer d : dancerRepository.findByIds(dancerIds)) {
            dancersById.put(d.getId(), d);
        }
        Map<Long, List<String>> tagsByRecognition = new HashMap<>();
        for (Object[] row : recognitionTagRepository.findTagsByRecognitionIds(recognitionIds)) {
            tagsByRecognition.computeIfAbsent((Long) row[0], k -> new ArrayList<>()).add((String) row[1]);
        }
        Map<Long, String> homeVenueNameById = fetchHomeVenueNames(dancerIds);

        Set<Long> seenDancerIds = new HashSet<>();
        List<MyDancerRecognitionResponse> result = new ArrayList<>();
        for (Object[] row : rows) {
            Long recognitionId = (Long) row[0];
            Long dancerId = (Long) row[1];
            if (!seenDancerIds.add(dancerId)) {
                continue; // 同舞伴只取最近一条
            }
            Dancer dancer = dancersById.get(dancerId);
            if (dancer == null) {
                continue; // 舞伴已物理删除（理论上软删，防御性跳过）
            }
            result.add(new MyDancerRecognitionResponse(
                    dancer.getId(), dancer.getNickname(), dancer.getAvatarUrl(), dancer.getBio(),
                    dancer.getCity(), homeVenueNameById.get(dancerId),
                    (LocalDate) row[2], tagsByRecognition.getOrDefault(recognitionId, Collections.emptyList())));
        }
        return result;
    }

    /** 我的舞伴主页（创建人视角，含 PENDING/HIDDEN 自有资料；status 由前端渲染徽标） */
    @Transactional(readOnly = true)
    public List<DancerSummaryResponse> listMyDancers(Long userId) {
        List<Dancer> dancers = dancerRepository.findByCreatedByAndDeletedFalseOrderByUpdatedAtDesc(userId);
        if (dancers.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = dancers.stream().map(Dancer::getId).toList();
        Map<Long, long[]> countsById = fetchCounts(ids);
        Map<Long, List<DancerTagStat>> tagsById = fetchTopTags(ids);
        Map<Long, String> homeVenueNameById = fetchHomeVenueNames(ids);
        Map<Long, String> coverPhotoUrlById = fetchCoverPhotoUrls(ids);
        Set<Long> myTodayIds = fetchMyTodayIds(ids, userId);

        List<DancerSummaryResponse> result = new ArrayList<>(dancers.size());
        for (Dancer d : dancers) {
            long[] counts = countsById.getOrDefault(d.getId(), new long[]{0L, 0L, 0L});
            result.add(new DancerSummaryResponse(
                    d.getId(), d.getNickname(), d.getAvatarUrl(), d.getBio(), d.getGender(), d.getCity(),
                    d.getStatus(), homeVenueNameById.get(d.getId()), coverPhotoUrlById.get(d.getId()),
                    counts[0], counts[2], counts[1],
                    myTodayIds.contains(d.getId()), tagsById.getOrDefault(d.getId(), Collections.emptyList())));
        }
        return result;
    }

    // ─── 相册（本人上传 → 逐张 PENDING 审核 → 管理员审 PUBLIC/REJECTED） ─────────

    /**
     * 舞伴本人/管理员上传相册照片（POST /dancers/{id}/photos）。
     * 上传即 PENDING（先审后发）；sortOrder 追加（取当前最大 +1），维持上传序。
     * 普通用户不可调用（canManage 校验——对舞伴唯一可写公开影响 = 认可+标签的约束不变）。
     * 返回本人视角全量照片（含刚上传的待审项）。
     */
    @Transactional
    public List<DancerPhotoResponse> addPhotos(Long userId, Long dancerId,
                                               List<String> urls, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canManage(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "仅舞伴本人或管理员可上传照片");
        }
        if (urls == null || urls.isEmpty()) {
            throw new BusinessException(1001, "请至少选择一张照片");
        }
        int nextOrder = maxSortOrder(dancerId) + 1;
        for (String url : urls) {
            String clean = TextSanitizer.sanitize(url, 500);
            if (clean.isEmpty() || !clean.startsWith("http")) {
                throw new BusinessException(1001, "照片地址不合法");
            }
            DancerPhoto photo = new DancerPhoto();
            photo.setDancerId(dancerId);
            photo.setUrl(clean);
            photo.setStatus(DancerPhotoStatus.PENDING);
            photo.setCreatedBy(userId);
            photo.setSortOrder(nextOrder++);
            photoRepository.save(photo);
        }
        return fetchPhotos(dancerId, true);
    }

    /** 舞伴本人/管理员删除照片（软删；普通用户不可调用） */
    @Transactional
    public void removePhoto(Long userId, Long dancerId, Long photoId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canManage(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "仅舞伴本人或管理员可删除照片");
        }
        DancerPhoto photo = photoRepository.findByIdAndDeletedFalse(photoId)
                .orElseThrow(() -> new BusinessException(1001, "照片不存在"));
        if (!photo.getDancerId().equals(dancerId)) {
            throw new BusinessException(1001, "照片不属于该舞伴");
        }
        photo.setDeleted(true);
        photoRepository.save(photo);
    }

    // ─── 管理端照片审核 ────────────────────────────────────────────────────────

    /**
     * 管理端照片审核列表（仅 ADMIN，含全部状态，按上传时间倒序——新照片优先审核）。
     * status 可选过滤（缺省全部，管理员从「待审核」筛选进入待办）。
     */
    @Transactional(readOnly = true)
    public Page<AdminDancerPhotoResponse> listAdminPhotos(DancerPhotoStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Object[]> rows = photoRepository.findAdminPage(status == null ? null : status.name(), pageable);
        List<AdminDancerPhotoResponse> content = rows.getContent().stream()
                .map(r -> new AdminDancerPhotoResponse(
                        (Long) r[0], (String) r[1], DancerPhotoStatus.valueOf((String) r[2]),
                        (Long) r[3], r[4] != null ? (String) r[4] : PHOTO_OWNER_GONE_NAME, (String) r[5], (String) r[6],
                        (LocalDateTime) r[7]))
                .toList();
        return new PageImpl<>(content, pageable, rows.getTotalElements());
    }

    /**
     * 管理端照片审核（仅 ADMIN）：PENDING → PUBLIC（通过，公开）/ PENDING → REJECTED（驳回）。
     * reason 可选（驳回说明，仅服务端审计日志——舞伴本人在编辑页可见 REJECTED 状态后
     * 自行删除重传，不新增站内信，见 AGENTS.md 决策记录）。已审核照片重复提交幂等返回。
     */
    @Transactional
    public void updatePhotoStatus(Long adminId, Long photoId, DancerPhotoStatus status, String reason) {
        DancerPhoto photo = photoRepository.findByIdAndDeletedFalse(photoId)
                .orElseThrow(() -> new BusinessException(1001, "照片不存在"));
        if (photo.getStatus() == status) {
            return; // 幂等：目标状态相同直接返回
        }
        if (photo.getStatus() != DancerPhotoStatus.PENDING) {
            throw new BusinessException(1003, "仅待审核照片可审核");
        }
        photo.setStatus(status);
        photoRepository.save(photo);
        log.info("管理员 {} 审核舞伴照片 {} → {}（舞伴 {}）{}", adminId, photoId, status,
                photo.getDancerId(), reason == null || reason.isBlank() ? "" : "，说明：" + TextSanitizer.sanitize(reason, 200));
    }

    // ─── 管理端 ────────────────────────────────────────────────────────────────

    /** 注册人占位文案（qwt_users 已软删时回退，审核页仍可辨识来源） */
    private static final String CREATOR_GONE_NAME = "未知用户";

    /**
     * 管理端舞伴列表（仅 ADMIN，含全部状态，按提交时间倒序——新注册优先审核）。
     * status 可选过滤；LEFT JOIN qwt_users 取注册人昵称/头像（用户已删时回退占位）。
     */
    @Transactional(readOnly = true)
    public Page<AdminDancerResponse> listAdminDancers(DancerStatus status, int page, int size) {
        Pageable pageable = PageRequest.of(page, Math.min(size, 50));
        Page<Object[]> rows = dancerRepository.findAdminPage(status == null ? null : status.name(), pageable);
        List<AdminDancerResponse> content = rows.getContent().stream()
                .map(r -> new AdminDancerResponse(
                        (Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                        DancerStatus.valueOf((String) r[6]),
                        r[8] != null ? (String) r[8] : CREATOR_GONE_NAME, (String) r[9],
                        (LocalDateTime) r[7]))
                .toList();
        return new PageImpl<>(content, pageable, rows.getTotalElements());
    }

    /**
     * 管理员状态切换（审核通过 PENDING→NORMAL / 驳回 PENDING→REJECTED / 下架或恢复
     * NORMAL↔HIDDEN）。状态实际变化时向创建人发送站内信（2026-08-08 新增，
     * 见 AGENTS.md「舞伴审核与站内信」）：
     * <ul>
     *   <li>PENDING → NORMAL：审核通过（DANCER_REVIEW）；</li>
     *   <li>PENDING → REJECTED：驳回，附 reason（DANCER_REVIEW）；</li>
     *   <li>NORMAL ↔ HIDDEN：隐藏 / 恢复展示（DANCER_STATUS）。</li>
     * </ul>
     * 站内信收件人 = 舞伴创建人（createdBy），与状态流转同事务（通知不丢失）。
     *
     * @param reason 操作说明（可选）：驳回时建议填写原因，随站内信回传创建人
     */
    @Transactional
    public void updateStatus(Long dancerId, DancerStatus status, String reason) {
        Dancer dancer = findDancerOrThrow(dancerId);
        DancerStatus from = dancer.getStatus();
        if (from == status) {
            return; // 幂等：目标状态相同直接返回（无变更不产生通知）
        }
        dancer.setStatus(status);
        dancerRepository.save(dancer);
        notifyStatusChange(dancer, from, status, reason);
    }

    /**
     * 状态变更站内信（与状态流转同事务，事务失败整体回滚保证通知不丢失）。
     * 文案真实正式、只陈述事实（同「分享内容契约」——禁止营销化描述）。
     */
    private void notifyStatusChange(Dancer dancer, DancerStatus from, DancerStatus to, String reason) {
        String nickname = dancer.getNickname();
        MessageType type;
        String title;
        String content;
        if (to == DancerStatus.NORMAL && from == DancerStatus.PENDING) {
            type = MessageType.DANCER_REVIEW;
            title = "舞伴主页审核通过";
            content = "你的舞伴主页「" + nickname + "」已通过审核，现在可以在舞伴列表中展示。";
        } else if (to == DancerStatus.REJECTED && from == DancerStatus.PENDING) {
            type = MessageType.DANCER_REVIEW;
            title = "舞伴主页未通过审核";
            String reasonText = reason == null || reason.isBlank()
                    ? "" : "，原因：" + TextSanitizer.sanitize(reason, 200);
            content = "你的舞伴主页「" + nickname + "」未通过审核" + reasonText
                    + "。可修改资料后重新提交。";
        } else if (to == DancerStatus.HIDDEN && from != DancerStatus.REJECTED) {
            type = MessageType.DANCER_STATUS;
            title = "舞伴主页已隐藏";
            content = "你的舞伴主页「" + nickname + "」已被隐藏，暂不对其他用户展示。";
        } else if (to == DancerStatus.NORMAL && from != DancerStatus.PENDING) {
            type = MessageType.DANCER_STATUS;
            title = "舞伴主页已恢复展示";
            content = "你的舞伴主页「" + nickname + "」已恢复展示。";
        } else {
            return; // 其余流转（REJECTED→HIDDEN 等）不产生通知
        }
        messageService.create(dancer.getCreatedBy(), type, title, content, "DANCER", dancer.getId());
    }

    // ─── 可见性 / 查询辅助 ──────────────────────────────────────────────────────

    /** 可见性规则：NORMAL 公开；PENDING/HIDDEN 仅创建人本人 + 平台管理员（管理员角色经缓存获取） */
    private boolean canView(Dancer dancer, Long userId, UserRole role) {
        if (dancer.getStatus() == DancerStatus.NORMAL) {
            return true;
        }
        if (role == UserRole.ADMIN) {
            return true;
        }
        return userId != null && dancer.getCreatedBy().equals(userId);
    }

    private Dancer findDancerOrThrow(Long dancerId) {
        return dancerRepository.findByIdAndDeletedFalse(dancerId)
                .orElseThrow(() -> new BusinessException(1001, "舞伴不存在"));
    }

    /** 详情/认可响应用：四窗口统计 + 近7日每日认可（"昨天 +3 前天 +5"动态信息） */
    private DancerRecognitionStats buildStats(Long dancerId) {
        long[] agg = aggregateService.getAggregate(dancerId);
        return new DancerRecognitionStats(agg[0], agg[1], agg[2], agg[3], fetchRecentDaily(dancerId));
    }

    /** 近7日每日认可（含今日，最近在前）——按 recognitionDate 自然日聚合，与窗口统计口径分离 */
    private List<DancerRecognitionStats.DailyRecognitionPoint> fetchRecentDaily(Long dancerId) {
        LocalDate since = LocalDate.now().minusDays(RECENT_DAILY_DAYS - 1L);
        Map<LocalDate, Long> countByDay = new HashMap<>();
        for (Object[] row : recognitionRepository.countByDay(dancerId, since)) {
            countByDay.put((LocalDate) row[0], ((Number) row[1]).longValue());
        }
        List<DancerRecognitionStats.DailyRecognitionPoint> points = new ArrayList<>(RECENT_DAILY_DAYS);
        for (int i = 0; i < RECENT_DAILY_DAYS; i++) {
            LocalDate day = LocalDate.now().minusDays(i);
            points.add(new DancerRecognitionStats.DailyRecognitionPoint(day, countByDay.getOrDefault(day, 0L)));
        }
        return points;
    }

    /** 批量计数：Object[]{countAll, countToday, count7d} by dancerId（我的舞伴主页用） */
    private Map<Long, long[]> fetchCounts(List<Long> dancerIds) {
        LocalDateTime now = LocalDateTime.now();
        Map<Long, long[]> result = new HashMap<>();
        for (Object[] row : recognitionRepository.countByDancerIds(dancerIds, LocalDate.now().atStartOfDay(), now.minusDays(7))) {
            result.put((Long) row[0], new long[]{
                    ((Number) row[1]).longValue(), ((Number) row[2]).longValue(), ((Number) row[3]).longValue()});
        }
        return result;
    }

    /** 批量 Top 标签（最多 LIST_TOP_TAGS 个，按计数倒序——聚合 SQL 已排序，服务层只截断） */
    private Map<Long, List<DancerTagStat>> fetchTopTags(List<Long> dancerIds) {
        Map<Long, List<DancerTagStat>> result = new HashMap<>();
        for (Object[] row : recognitionTagRepository.aggregateByDancerIds(dancerIds)) {
            Long dancerId = (Long) row[0];
            String tag = (String) row[1];
            long count = ((Number) row[2]).longValue();
            DancerTagCode code = DancerTagCode.valueOf(tag); // 仅字典内代码落库，valueOf 安全
            List<DancerTagStat> list = result.computeIfAbsent(dancerId, k -> new ArrayList<>());
            if (list.size() < LIST_TOP_TAGS) {
                list.add(new DancerTagStat(tag, code.getEmoji(), code.getLabel(), count));
            }
        }
        return result;
    }

    /** 单舞伴全量标签（详情页标签云，全量不截断） */
    private List<DancerTagStat> fetchAllTags(Long dancerId) {
        List<DancerTagStat> result = new ArrayList<>();
        for (Object[] row : recognitionTagRepository.aggregateByDancer(dancerId)) {
            String tag = (String) row[0];
            long count = ((Number) row[1]).longValue();
            DancerTagCode code = DancerTagCode.valueOf(tag);
            result.add(new DancerTagStat(tag, code.getEmoji(), code.getLabel(), count));
        }
        return result;
    }

    /** 批量"常去"舞厅名：取每个舞伴最早一条 HOME 关系（venue briefs 按 created_at 升序） */
    private Map<Long, String> fetchHomeVenueNames(List<Long> dancerIds) {
        Map<Long, String> result = new HashMap<>();
        for (Object[] row : dancerVenueRepository.findVenueBriefsByDancerIds(dancerIds)) {
            Long dancerId = (Long) row[0];
            if (DancerVenueRelation.HOME.name().equals(row[5]) && !result.containsKey(dancerId)) {
                result.put(dancerId, (String) row[2]);
            }
        }
        return result;
    }

    /** 批量个人"今日已认可"舞伴 ID（列表页个人状态，实时查询不缓存；未登录返回空集） */
    private Set<Long> fetchMyTodayIds(List<Long> dancerIds, Long currentUserId) {
        if (currentUserId == null || dancerIds.isEmpty()) {
            return Collections.emptySet();
        }
        return new HashSet<>(recognitionRepository
                .findTodayRecognizedDancerIdsIn(currentUserId, dancerIds, LocalDate.now()));
    }

    /** 详情页场所关系全量（HOME 常去 + APPEARANCE 出现，均按创建时间升序） */
    private List<DancerVenueInfo> fetchVenues(Long dancerId) {
        List<DancerVenueInfo> result = new ArrayList<>();
        for (Object[] row : dancerVenueRepository.findVenueBriefsByDancerIds(List.of(dancerId))) {
            result.add(new DancerVenueInfo(
                    (Long) row[1], (String) row[2], (String) row[3], (String) row[4],
                    DancerVenueRelation.valueOf((String) row[5]), (String) row[6]));
        }
        return result;
    }

    // ─── 相册辅助 ──────────────────────────────────────────────────────────────

    /**
     * 舞伴相册照片（按 sortOrder 升序 = 上传序）。
     *
     * @param showAll true = 本人/管理员视角（全量含 PENDING/REJECTED，编辑页回显状态）；
     *                false = 公开视角（仅 PUBLIC）。
     */
    private List<DancerPhotoResponse> fetchPhotos(Long dancerId, boolean showAll) {
        List<DancerPhotoResponse> result = new ArrayList<>();
        for (DancerPhoto p : photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(dancerId)) {
            if (!showAll && p.getStatus() != DancerPhotoStatus.PUBLIC) {
                continue;
            }
            result.add(new DancerPhotoResponse(p.getId(), p.getUrl(), p.getStatus(), p.getSortOrder(), p.getCreatedAt()));
        }
        return result;
    }

    /** 批量封面照片：每个舞伴展示顺序最小的一张 PUBLIC（列表页/我的舞伴主页，N+1 规避） */
    private Map<Long, String> fetchCoverPhotoUrls(List<Long> dancerIds) {
        Map<Long, String> result = new HashMap<>();
        for (Object[] row : photoRepository.findCoverUrlsByDancerIds(dancerIds)) {
            result.put((Long) row[0], (String) row[1]);
        }
        return result;
    }

    /** 当前最大展示顺序（新照片 sortOrder = max + 1，维持上传序；无照片返回 0） */
    private int maxSortOrder(Long dancerId) {
        return photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(dancerId)
                .stream().mapToInt(DancerPhoto::getSortOrder).max().orElse(0);
    }

    /**
     * 管理权限：本人（createdBy 匹配）或平台管理员。
     * 与 canView 的差异：编辑/照片上传/删除等写操作仅面向本人与管理员，
     * 普通用户对舞伴唯一可写公开影响 = 认可 + 字典标签（隐私边界，见类 javadoc）。
     */
    private boolean canManage(Dancer dancer, Long userId, UserRole role) {
        if (role == UserRole.ADMIN) {
            return true;
        }
        return userId != null && dancer.getCreatedBy().equals(userId);
    }
}
