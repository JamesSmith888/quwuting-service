package org.quwuting.quwutingservice.dancer.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.config.DancerAdProperties;
import org.quwuting.quwutingservice.dancer.DancerTagCode;
import org.quwuting.quwutingservice.dancer.dto.request.AddDancerVideosRequest;
import org.quwuting.quwutingservice.dancer.dto.request.RecognizeDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerRequest;
import org.quwuting.quwutingservice.dancer.dto.request.UpsertDancerServiceRequest;
import org.quwuting.quwutingservice.dancer.dto.response.*;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerCity;
import org.quwuting.quwutingservice.dancer.entity.DancerPhoto;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognition;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognitionTag;
import org.quwuting.quwutingservice.dancer.entity.DancerVenue;
import org.quwuting.quwutingservice.dancer.entity.DancerVerificationLog;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoKind;
import org.quwuting.quwutingservice.dancer.enums.DancerPhotoStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerServiceSubCategory;
import org.quwuting.quwutingservice.dancer.enums.DancerStatus;
import org.quwuting.quwutingservice.dancer.enums.DancerVenueRelation;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationAction;
import org.quwuting.quwutingservice.dancer.enums.DancerVerificationStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerAdViewRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerCityRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerFavoriteRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerPhotoRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionTagRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerServiceRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVenueRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerVerificationLogRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.points.enums.PointsGateTargetType;
import org.quwuting.quwutingservice.storage.ImageContentValidator;
import org.quwuting.quwutingservice.tagdict.dto.response.TagItemResponse;
import org.quwuting.quwutingservice.tagdict.service.TagDictService;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

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

    /** 单次上传照片数上限（与前端 image-upload maxCount=9 对齐——后端独立校验防绕过，2026-08-19） */
    private static final int MAX_PHOTOS_PER_UPLOAD = 9;

    /** 单次上传视频数上限（2026-08-22：短视频少量场景，与 AddDancerVideosRequest 对齐） */
    private static final int MAX_VIDEOS_PER_UPLOAD = 3;

    /** 管理端照片列表中舞伴已软删时的昵称占位（审核页仍可辨识来源，同 AdminDancerResponse） */
    private static final String PHOTO_OWNER_GONE_NAME = "未知舞伴";

    private final DancerRepository dancerRepository;
    private final DancerCityRepository dancerCityRepository;
    private final DancerVenueRepository dancerVenueRepository;
    private final DancerRecognitionRepository recognitionRepository;
    private final DancerRecognitionTagRepository recognitionTagRepository;
    private final DancerPhotoRepository photoRepository;
    private final DancerAdViewRepository adViewRepository;
    /** 舞伴服务范围（2026-08-24：admin 录入的黄页内容——详情服务卡/需求弹层/列表服务类别筛选） */
    private final DancerServiceRepository dancerServiceRepository;
    /** 信息核验审计日志（2026-08-14 官方认证：全部状态变迁的唯一历史事实源） */
    private final DancerVerificationLogRepository verificationLogRepository;
    /** 舞伴收藏（2026-08-14：qwt_dancer_favorites，见 V27 迁移与 AGENTS.md「舞伴收藏」） */
    private final DancerFavoriteRepository dancerFavoriteRepository;
    /**
     * 详情公共部分聚合缓存（2026-08-19 引入）：用户无关聚合（认可统计/标签/场所/城市/
     * 收礼/收到积分/广告计数/联系方式门槛）整体缓存，详情接口 DB 往返 ~15 次 → ~6 次。
     * 本服务的认可/收藏写路径与统计强相关，真实写入后必须经它失效（唯一失效入口，级联
     * 失效内层 DancerAggregateService 与 DancerStatsService），见「写路径缓存失效」约定。
     */
    private final DancerDetailCacheService detailCacheService;
    /**
     * 列表公共部分聚合缓存（2026-08-22 引入）：主查询行 + 用户无关 enrichments
     * （Top 标签/常驻舞厅名/封面/累计浏览量）整体缓存，列表接口 DB 往返 ~7 次 → ~2 次。
     * 改变列表行内容/排序的写路径（认可/收藏/编辑/照片增删审/状态流转/认证/新建）必须
     * 经 {@link #invalidateListCache()} 失效（唯一失效入口，全清——条目数小可接受），
     * 浏览量/分享/广告浏览不入失效矩阵（弱信息 + 高频写，refresh 兜底），见类 javadoc。
     */
    private final DancerListCacheService listCacheService;
    private final VenueLookupService venueLookupService;
    private final MessageService messageService;
    private final org.quwuting.quwutingservice.points.service.PointsService pointsService;
    /** 图片内容校验（2026-08-12 恶意文件防线：业务提交时对图片 URL 做内容级校验） */
    private final ImageContentValidator imageValidator;
    /** 创作者收益计划配置（2026-08-14：激励视频广告位 ID，后端下发前端零硬编码） */
    private final DancerAdProperties dancerAdProperties;
    /** 运营配置（2026-08-15 认可「每日一票」开关：dancer.recognition.daily.single） */
    private final OpsConfigService opsConfigService;
    /** 通用标签字典（2026-08-24：资料标签序列化/反序列化 + 字典解析） */
    private final TagDictService tagDictService;

    // ─── 创建（唯一通道：管理员后台创建 → NORMAL；用户主动注册已下线） ────────

    /**
     * 创建舞伴资料（<b>唯一调用方 = AdminDancerController</b>，2026-08-21 起）。
     * <p>
     * ⚠️ 合规约束：个人主体小程序「收集、存储用户身份信息」审核驳回后，用户主动
     * 注册通道（原 POST /dancers，adminApproved=false）已下线，舞伴资料 = 平台发布
     * 的黄页内容（同门店/动态/照片的平台代发模型，见 AGENTS.md「小程序类目合规
     * UGC 红线」）。本方法仅经 adminApproved=true 调用（status=NORMAL 直通公开，
     * createdBy = 管理员 ID）；adminApproved=false 分支保留仅为历史兼容，前端无
     * 任何普通用户入口。
     *
     * @param adminApproved true = 后台创建（管理员，status=NORMAL 直接公开）；false = 遗留分支（不再使用）
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
        String avatarUrl = TextSanitizer.sanitize(request.avatarUrl(), 500);
        imageValidator.validate(avatarUrl);
        dancer.setAvatarUrl(avatarUrl);
        dancer.setBio(TextSanitizer.sanitize(request.bio(), 300));
        dancer.setGender(TextSanitizer.sanitize(request.gender(), 20));
        // 多城市（2026-08-14）：cities 优先（去空去重，最多 3 个）；主城市 = 首个
        List<String> cities = resolveCities(request);
        dancer.setCity(primaryCity(cities));
        dancer.setContactImageUrl(sanitizeContactImageUrl(request.contactImageUrl()));
        dancer.setContact(TextSanitizer.sanitize(request.contact(), 100));
        // 联系方式遮挡开关（2026-08-14：null = 默认遮挡 true，向后兼容旧客户端）
        dancer.setHideContact(request.hideContact() == null || request.hideContact());
        dancer.setEarningsEnabled(request.earningsEnabled() != null && request.earningsEnabled());
        // 资料标签（2026-08-24 字典化：id 数组 JSON；去重/去空/存在性校验后落库）
        dancer.setProfileTags(tagDictService.serializeIds(normalizeProfileTags(request.profileTags())));
        dancer.setStatus(adminApproved ? DancerStatus.NORMAL : DancerStatus.PENDING);
        dancer.setCreatedBy(userId);
        dancer = dancerRepository.save(dancer);
        replaceDancerCities(dancer.getId(), cities);

        if (request.homeVenueId() != null) {
            attachHomeVenue(dancer.getId(), request.homeVenueId());
        }
        // 管理端直通公开（adminApproved=true → NORMAL）：新舞伴立即出现在公开列表 → 列表缓存失效
        invalidateListCache();
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

    // ─── 多城市（2026-08-14，V29 迁移） ────────────────────────────────────────

    /** 一个舞伴最多可选的城市数（产品规则：常驻/活跃城市收敛，防堆砌） */
    public static final int MAX_CITIES = 3;

    /**
     * 解析城市列表（创建/编辑共用，单一权威）：
     * cities 优先（逐个清洗、去空、去重，最多 {@link #MAX_CITIES} 个，保序）；
     * cities 为空/全空时回退 city 单值（旧客户端兼容——新版前端恒传 cities）。
     *
     * @return 去重有序列表（可为空 = 未填城市）
     */
    private List<String> resolveCities(UpsertDancerRequest request) {
        List<String> raw = request.cities() == null ? Collections.emptyList() : request.cities();
        List<String> result = new ArrayList<>(MAX_CITIES);
        for (String c : raw) {
            String clean = TextSanitizer.sanitize(c, 50);
            if (clean.isEmpty() || result.contains(clean)) {
                continue;
            }
            result.add(clean);
            if (result.size() >= MAX_CITIES) {
                break;
            }
        }
        if (result.isEmpty()) {
            String legacy = TextSanitizer.sanitize(request.city(), 50);
            if (!legacy.isEmpty()) {
                result.add(legacy);
            }
        }
        return result;
    }

    /** 主城市（= cities 首个，冗余同步 dancer.city 展示位；空列表 → null） */
    private String primaryCity(List<String> cities) {
        return cities.isEmpty() ? null : cities.get(0);
    }

    /**
     * 资料标签归一（2026-08-24，创建/编辑共用单一权威）：
     * 去 null、去重（LinkedHashSet 保序）、剔除字典中不存在的 id——
     * 标签由管理员从字典选择，正常不出现脏 id，此处为纵深防御（防绕过前端
     * 的任意 id 落库）；返回空列表 = 无标签（serializeIds 存 null）。
     */
    private List<Long> normalizeProfileTags(List<Long> tags) {
        if (tags == null || tags.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = new ArrayList<>(new LinkedHashSet<>(tags));
        ids.removeIf(id -> id == null);
        if (ids.isEmpty()) {
            return Collections.emptyList();
        }
        Set<Long> existing = tagDictService.resolveByIds(ids).keySet();
        ids.removeIf(id -> !existing.contains(id));
        return ids;
    }

    /**
     * 全量替换舞伴城市子表（编辑 = 软删旧 + 插入新，幂等去重由 resolveCities 保证；
     * 与 DancerVenue HOME 关系替换先例一致，无唯一约束防软删行冲突）。
     */
    private void replaceDancerCities(Long dancerId, List<String> cities) {
        List<DancerCity> old = dancerCityRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(dancerId);
        for (DancerCity c : old) {
            c.setDeleted(true);
            dancerCityRepository.save(c);
        }
        for (int i = 0; i < cities.size(); i++) {
            DancerCity dc = new DancerCity();
            dc.setDancerId(dancerId);
            dc.setCity(cities.get(i));
            dc.setSortOrder(i);
            dancerCityRepository.save(dc);
        }
    }

    /** 联系方式图片 URL（可空；非空时清洗 + 内容校验——08-12 安全加固约定：新增图片 URL 落库字段必须挂载校验） */
    private String sanitizeContactImageUrl(String url) {
        String clean = TextSanitizer.sanitize(url, 500);
        if (clean.isEmpty()) {
            return null;
        }
        imageValidator.validate(clean);
        return clean;
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
        String avatarUrl = TextSanitizer.sanitize(request.avatarUrl(), 500);
        imageValidator.validate(avatarUrl);
        dancer.setAvatarUrl(avatarUrl);
        dancer.setBio(TextSanitizer.sanitize(request.bio(), 300));
        dancer.setGender(TextSanitizer.sanitize(request.gender(), 20));
        // 多城市（2026-08-14）：全量替换语义（同 homeVenueId HOME 关系——编辑是
        // "城市变更"而非"追加"，防止多次编辑累积超过 3 个城市）
        List<String> cities = resolveCities(request);
        dancer.setCity(primaryCity(cities));
        dancer.setContactImageUrl(sanitizeContactImageUrl(request.contactImageUrl()));
        dancer.setContact(TextSanitizer.sanitize(request.contact(), 100));
        dancer.setHideContact(request.hideContact() == null || request.hideContact());
        dancer.setEarningsEnabled(request.earningsEnabled() != null && request.earningsEnabled());
        // 资料标签（2026-08-24：全量覆盖语义——传 null/空 = 清除全部标签）
        dancer.setProfileTags(tagDictService.serializeIds(normalizeProfileTags(request.profileTags())));
        // REJECTED 资料编辑后重新送审（管理员直改仍由管理员后续流转，此处不覆盖）
        if (dancer.getStatus() == DancerStatus.REJECTED) {
            dancer.setStatus(DancerStatus.PENDING);
        }
        // 信息核验降级护栏（2026-08-14 官方认证：防"认证挂在过期信息上"）：
        // 舞伴本人编辑资料 → 已认证（VERIFIED）或曾认证（撤销后再次编辑）→ 自动降级
        // PENDING_REVIEW 待 admin 复核；管理员直改不触发（管理者已在该资料上进行管理
        // 动作，避免制造待办噪音——见 AGENTS.md「舞伴官方认证」状态机）。
        if (currentRole != UserRole.ADMIN && needsVerificationReview(dancer)) {
            transitionVerification(dancer, DancerVerificationStatus.PENDING_REVIEW,
                    userId, "本人修改资料，认证待复核");
        } else {
            dancerRepository.save(dancer);
        }
        replaceHomeVenue(dancerId, request.homeVenueId());
        // 多城市子表全量替换（与 homeVenueId 同"编辑 = 变更而非追加"语义）
        replaceDancerCities(dancerId, cities);
        // 城市子表是详情公共缓存（cities）的输入——编辑后失效（2026-08-19 详情缓存约定）
        detailCacheService.invalidate(dancerId);
        // 编辑改变列表行内容（昵称/常去/城市/简介）——列表缓存失效（2026-08-22）
        invalidateListCache();
        return getDetail(dancerId, userId, currentRole);
    }

    /**
     * 编辑是否触发认证待复核（2026-08-14）：当前已认证（VERIFIED），或曾认证
     * （日志存在 to_status=VERIFIED——撤销后再次编辑 → 重新核验闭环，"被撤销 →
     * 修改资料 → 待复核 → 复核恢复"）。
     */
    private boolean needsVerificationReview(Dancer dancer) {
        if (dancer.getVerificationStatus() == DancerVerificationStatus.VERIFIED) {
            return true;
        }
        return dancer.getVerificationStatus() == DancerVerificationStatus.UNVERIFIED
                && verificationLogRepository.existsByDancerIdAndToStatus(
                        dancer.getId(), DancerVerificationStatus.VERIFIED.name());
    }

    /**
     * HOME 关系完整替换：先软删全部旧 HOME，再按需建立新 HOME（null = 清除常驻舞厅）。
     * <p>
     * 2026-08-19 根因修复：旧实现循环内「目标 HOME 已存在 → return」提前退出——注释声称
     * 「其余旧 HOME 罕见情况由下方兜底清掉」，但 return 跳过了兜底，数据异常（同舞伴历史
     * 遗留多条 HOME）时无法自愈。修复为「跳过目标行、删除其余行」的完整替换语义。
     */
    private void replaceHomeVenue(Long dancerId, Long newVenueId) {
        List<DancerVenue> existingHomes = dancerVenueRepository.findByDancerIdAndRelationAndDeletedFalse(
                dancerId, DancerVenueRelation.HOME);
        boolean keepTarget = newVenueId != null
                && existingHomes.stream().anyMatch(dv -> dv.getVenueId().equals(newVenueId));
        for (DancerVenue dv : existingHomes) {
            if (keepTarget && dv.getVenueId().equals(newVenueId)) {
                continue; // 目标 HOME 保留（其余旧 HOME 一并清除，完整替换）
            }
            dv.setDeleted(true);
            dancerVenueRepository.save(dv);
        }
        if (!keepTarget && newVenueId != null) {
            attachHomeVenue(dancerId, newVenueId);
        }
    }

    /**
     * 列表公共缓存失效（写路径统一入口，2026-08-22 列表缓存配套）：
     * 改变公开列表行内容/排序的写操作（认可/编辑/照片增删审/状态流转/认证/新建）
     * 后调用——对齐 {@link #detailCacheService} 失效约定：内联失效（写事务内清缓存，
     * 保证响应后读者回源最新）+ afterCommit/afterCompletion 兜底（防并发读者在内联
     * 失效与提交之间回源缓存旧值 / 事务回滚污染缓存）。
     * 收藏 add·remove 不改公开列表内容，豁免（见 DancerListCacheService javadoc）；
     * 浏览量/分享/广告浏览为弱信息 + 高频写，同样豁免（60s refresh 兜底）。
     */
    private void invalidateListCache() {
        listCacheService.invalidateAll();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    listCacheService.invalidateAll();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        listCacheService.invalidateAll();
                    }
                }
            });
        }
    }

    // ─── 列表 / 详情 ───────────────────────────────────────────────────────────

    /**
     * 公开舞伴列表（仅 NORMAL），按近7天认可倒序分页。
     * <p>
     * 2026-08-22 性能根因修复：用户无关部分（主查询行 + Top 标签/常驻舞厅/封面/浏览量
     * enrichments）整体走 {@link DancerListCacheService}（60s refresh-ahead + 写路径失效），
     * 列表接口 DB 往返 ~7 次 → ~2 次；个人态（今日已认可 ID / 今日投票标签）恒实时查询
     * （严禁进用户无关缓存，列表卡片 chips 活跃态数据源）。写路径（认可/收藏/编辑/照片
     * 增删审/状态流转/认证/新建）经 {@link #invalidateListCache()} 显式失效。
     */
    @Transactional(readOnly = true)
    public Page<DancerSummaryResponse> listPublic(String city, String serviceCategory,
                                                  int page, int size, Long currentUserId) {
        Pageable pageable = sanePage(page, size);
        // 服务类别筛选（2026-08-24 需求优先匹配）：非法类别 code → 1001（枚举解析防御）
        DancerServiceCategory category = null;
        if (serviceCategory != null && !serviceCategory.isBlank()) {
            category = DancerServiceCategory.parse(serviceCategory);
        }
        // 用户无关公共部分（缓存：单飞 + refresh-ahead，见 DancerListCacheService）
        DancerListCacheService.ListPublicPart part = listCacheService.get(city,
                category != null ? category.name() : null, pageable);
        List<Object[]> rows = part.rows();
        if (rows.isEmpty()) {
            return new PageImpl<>(Collections.emptyList(), pageable, part.totalElements());
        }
        List<Long> ids = rows.stream().map(r -> (Long) r[0]).toList();
        // 个人态实时查询（不缓存）：今日已认可 ID + 今日投票标签（chips 活跃态数据源）
        Set<Long> myTodayIds = fetchMyTodayIds(ids, currentUserId);
        Map<Long, List<String>> myTagsById = fetchMyTodayTags(ids, currentUserId);
        // 媒体预览解锁态（2026-08-24 晚：列表卡片多图预览——付费媒体按当前用户实时
        // 解锁态选择清晰/薄码；用户相关态不进列表缓存，同 myTodayIds 边界）
        Set<Long> unlockedMediaIds = fetchUnlockedMediaIds(part.enrichments(), currentUserId);
        return new PageImpl<>(buildSummaries(rows, part.enrichments(), myTodayIds, myTagsById,
                unlockedMediaIds, false),
                pageable, part.totalElements());
    }

    /** 分页参数归一（2026-08-19 加固：page<0 / size<1 会令 PageRequest 抛 IllegalArgumentException → HTTP 500；
     *  与 listTransactions 的 Math.max(size,1) 同先例，统一在此收敛） */
    private static Pageable sanePage(int page, int size) {
        return PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50));
    }

    // ─── 收藏（2026-08-14 舞伴收藏：列表/详情/详情星标，见 AGENTS.md「舞伴收藏」） ─────

    /**
     * 当前用户收藏的舞伴列表（按收藏时间倒序；仅当前公开 NORMAL 舞伴——HIDDEN 自动
     * 淡出，见 V27 迁移注释）。返回与公开列表同构的 DancerSummaryResponse，前端
     * 复用同一套卡片 ViewModel 派生（toCardVM）。
     */
    @Transactional(readOnly = true)
    public List<DancerSummaryResponse> listFavorites(Long userId) {
        List<Object[]> rows = dancerFavoriteRepository.findFavoriteDancersByUserId(
                userId, LocalDate.now().atStartOfDay(), LocalDateTime.now().minusDays(7));
        if (rows.isEmpty()) {
            return Collections.emptyList();
        }
        List<Long> ids = rows.stream().map(r -> (Long) r[0]).toList();
        // 用户无关 enrichments（单一权威 = DancerListCacheService；收藏列表个性化不进缓存，
        // 但 enrichments 计算口径与公开列表一致）；个人态（今日认可/投票标签/媒体解锁）恒实时查询
        DancerListCacheService.ListEnrichments en = listCacheService.computeEnrichments(ids);
        Set<Long> myTodayIds = fetchMyTodayIds(ids, userId);
        Map<Long, List<String>> myTagsById = fetchMyTodayTags(ids, userId);
        Set<Long> unlockedMediaIds = fetchUnlockedMediaIds(en, userId);
        return buildSummaries(rows, en, myTodayIds, myTagsById, unlockedMediaIds, false);
    }

    /**
     * 收藏舞伴（需登录；幂等——已收藏则忽略，软删行 restore 复用）。
     * <p>
     * 可见性防御：仅公开舞伴（NORMAL）可被收藏——HIDDEN/PENDING/REJECTED 属于
     * 不可见/未公开资料，收藏入口本就不可达（详情页可见性校验先行），此处校验为
     * 纵深防御（防绕过）。并发竞态由原子 upsert（ON CONFLICT DO UPDATE）在库内兜底——
     * 2026-08-19 根因修复：原「find 判断 → save + 23505 异常吞掉」在 Hibernate flush
     * 失败后事务可能已被标记 rollback-only，并发幂等返回实际变为 HTTP 500；原子 upsert
     * 恒 1 次往返、零异常，插入/restore/幂等三分支语义完整覆盖（见 repository javadoc）。
     */
    @Transactional
    public void addFavorite(Long userId, Long dancerId) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (dancer.getStatus() != DancerStatus.NORMAL) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        dancerFavoriteRepository.upsertFavorite(userId, dancerId, LocalDateTime.now());
        detailCacheService.invalidate(dancerId); // 收藏趋势输入（favoriteTrend）真实写入后失效
    }

    /**
     * 取消收藏（需登录；幂等——未收藏则忽略）。软删（deleted=true）行保留——
     * 被收藏舞伴 HIDDEN 下架后行留存，恢复 NORMAL 后自动重现（见 V27 迁移注释）。
     * 无取消时刻列/无频控：舞伴收藏趋势为"新增"单序列（无门店式 unfavorited_at
     * 取消线），取消不输入趋势图，无膨胀风险（见 DancerFavorite 注释）。
     */
    @Transactional
    public void removeFavorite(Long userId, Long dancerId) {
        dancerFavoriteRepository.findByUserIdAndDancerId(userId, dancerId)
                .filter(fav -> !fav.isDeleted())
                .ifPresent(fav -> {
                    fav.setDeleted(true);
                    dancerFavoriteRepository.save(fav);
                    // 列表/详情收藏态变化（统计缓存无实质影响，保持写路径一致约定）
                    detailCacheService.invalidate(dancerId);
                });
    }

    /**
     * 行内 Object[]（{id, nickname, avatar_url, bio, gender, city, cnt_all, cnt_today,
     * cnt7, verification_status}）+ 批量 enrichments → 卡片摘要列表。
     * 公开列表（listPublic，enrichments 走列表缓存）、收藏列表（listFavorites）与
     * 我的舞伴主页（listMyDancers）共用——三处查询返回同构行，enrichments 计算单一权威
     * （DancerListCacheService#computeEnrichments），摘要构建逻辑也单一权威
     * （2026-08-14 抽取；2026-08-22 enrichments 参数化：列表缓存命中时零查询直组装）。
     * 2026-08-24 晚：新增 unlockedMediaIds / showAllMedia 两参——媒体预览（列表卡片
     * 多图）解锁态按当前用户实时组装（用户相关态不进列表缓存，同 myTodayIds 边界）。
     */
    private List<DancerSummaryResponse> buildSummaries(
            List<Object[]> content,
            DancerListCacheService.ListEnrichments en,
            Set<Long> myTodayIds,
            Map<Long, List<String>> myTagsById,
            Set<Long> unlockedMediaIds,
            boolean showAllMedia) {
        // 行内计数：Object[]{id, ..., count_all(6), count_today(7), count_7d(8)}
        Map<Long, long[]> countsById = new HashMap<>();
        for (Object[] row : content) {
            countsById.put((Long) row[0], new long[]{
                    ((Number) row[6]).longValue(), ((Number) row[7]).longValue(), ((Number) row[8]).longValue()});
        }

        List<DancerSummaryResponse> summaries = new ArrayList<>(content.size());
        for (Object[] row : content) {
            Long id = (Long) row[0];
            long[] counts = countsById.get(id);
            summaries.add(new DancerSummaryResponse(
                    id, (String) row[1], (String) row[2], (String) row[3], (String) row[4], (String) row[5],
                    en.profileTagsById().getOrDefault(id, Collections.emptyList()),
                    DancerStatus.NORMAL, toVerificationStatus(row[9]),
                    en.homeVenueNameById().get(id), en.coverPhotoUrlById().get(id),
                    counts[0], counts[2], counts[1],
                    myTodayIds.contains(id),
                    myTagsById.getOrDefault(id, Collections.emptyList()),
                    en.tagsById().getOrDefault(id, Collections.emptyList()),
                    en.viewCounts().getOrDefault(id, 0L),
                    buildMediaPreviews(en.mediaPreviewsById().get(id), unlockedMediaIds, showAllMedia),
                    en.onlineServiceDancerIds().contains(id)));
        }
        return summaries;
    }

    /**
     * 媒体预览组装（2026-08-24 晚：列表卡片多图预览）：按当前用户解锁态选择展示图——
     * 免费（cost=0）/已解锁 → 清晰（照片原图 / 视频封面帧）；付费未解锁 → <b>仅下发
     * blurUrl 薄码（url 置 null 防内容绕过）</b>；本人/管理员视角（showAllMedia）恒清晰。
     * 与详情页 fetchPhotos 同一门槛口径（cost 语义 / DANCER_PHOTO·DANCER_VIDEO 分型）。
     */
    private List<DancerMediaPreviewResponse> buildMediaPreviews(
            List<DancerListCacheService.DancerMediaBrief> briefs,
            Set<Long> unlockedMediaIds,
            boolean showAllMedia) {
        if (briefs == null || briefs.isEmpty()) {
            return Collections.emptyList();
        }
        List<DancerMediaPreviewResponse> out = new ArrayList<>(briefs.size());
        for (DancerListCacheService.DancerMediaBrief b : briefs) {
            boolean unlocked = showAllMedia || b.cost() == 0 || unlockedMediaIds.contains(b.id());
            boolean isVideo = b.kind() == DancerPhotoKind.VIDEO;
            out.add(new DancerMediaPreviewResponse(
                    b.id(), b.kind(),
                    unlocked ? (isVideo ? b.coverUrl() : b.url()) : null,
                    unlocked ? null : b.blurUrl(),
                    b.cost(), unlocked, b.durationSeconds()));
        }
        return out;
    }

    /**
     * 批量当前用户已解锁的媒体 ID（2026-08-24 晚：列表卡片媒体预览解锁态）。
     * 从 enrichments 媒体简报收集全部媒体 id，按类型分组批量查解锁（照片
     * DANCER_PHOTO / 视频 DANCER_VIDEO，N+1 规避，同 fetchPhotos 口径）；
     * 匿名（userId=null）→ 空集（付费媒体恒薄码展示）。
     */
    private Set<Long> fetchUnlockedMediaIds(DancerListCacheService.ListEnrichments en, Long currentUserId) {
        if (currentUserId == null) {
            return Collections.emptySet();
        }
        List<Long> photoIds = new ArrayList<>();
        List<Long> videoIds = new ArrayList<>();
        for (List<DancerListCacheService.DancerMediaBrief> briefs : en.mediaPreviewsById().values()) {
            for (DancerListCacheService.DancerMediaBrief b : briefs) {
                (b.kind() == DancerPhotoKind.VIDEO ? videoIds : photoIds).add(b.id());
            }
        }
        Set<Long> unlocked = new HashSet<>();
        if (!photoIds.isEmpty()) {
            unlocked.addAll(pointsService.unlockedIds(currentUserId, PointsGateTargetType.DANCER_PHOTO, photoIds));
        }
        if (!videoIds.isEmpty()) {
            unlocked.addAll(pointsService.unlockedIds(currentUserId, PointsGateTargetType.DANCER_VIDEO, videoIds));
        }
        return unlocked;
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
     * <p>
     * 2026-08-19 性能根因修复：用户无关聚合（认可统计/标签/场所/城市/收礼/收到积分/
     * 广告计数/联系方式门槛）整体走 {@link DancerDetailCacheService}（60s refresh-ahead +
     * 写路径失效），详情接口 DB 往返 ~15 次 → ~6 次；用户相关态（isMine/今日认可/收藏/
     * 解锁/相册过滤）恒实时查询不缓存。
     */
    @Transactional(readOnly = true)
    public DancerDetailResponse getDetail(Long dancerId, Long currentUserId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, currentUserId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        boolean isMine = currentUserId != null && dancer.getCreatedBy().equals(currentUserId);
        boolean showAllPhotos = isMine || currentRole == UserRole.ADMIN;
        // 今日认可态 + 携带标签（2026-08-15 单票模型：详情页 chip 活跃态数据源；
        // 与 myRecognizedToday 同一次查询，避免两次命中唯一索引）
        boolean myToday = false;
        List<String> myTags = Collections.emptyList();
        if (currentUserId != null) {
            Optional<DancerRecognition> myTodayRec = recognitionRepository
                    .findByUserIdAndDancerIdAndRecognitionDate(currentUserId, dancerId, LocalDate.now());
            myToday = myTodayRec.isPresent();
            myTags = myTodayRec.map(r -> fetchTagsForRecognition(r.getId())).orElseGet(Collections::emptyList);
        }
        // 收藏态（2026-08-14 舞伴收藏：服务端权威，替代 venue 的 URL fav 参数 hack——
        // 分享深链等无参数入口不丢状态；匿名/未收藏恒 false）
        boolean favorite = currentUserId != null && dancerFavoriteRepository
                .findByUserIdAndDancerId(currentUserId, dancerId)
                .filter(f -> !f.isDeleted()).isPresent();
        // 用户无关公共聚合（2026-08-19 详情缓存：stats/tags/venues/cities/gifts/points/adViews/contactCost）
        DancerDetailCacheService.PublicPart pub = detailCacheService.get(dancerId);
        // 联系方式可见性（2026-08-24 晚 改版：联系方式改为「用户获取时才实时查询」）：
        // - 详情接口对普通用户（非本人/管理员）恒不下发真实值（contact/contactImageUrl），
        //   无论是否解锁/无门槛/不遮挡——防内容随详情泄漏；用户点击「获取联系方式」
        //   经 POST /points/unlock 实时查询返回（无门槛恒免费、有门槛每日首免、
        //   已解锁幂等，见 PointsService#unlock）；
        // - 本人/管理员（dancer-edit 编辑回显 + 管理者天然可见）仍随详情下发；
        // - hideContact/contactCost/contactUnlocked 照常下发（前端驱动入口文案/锁态）。
        boolean hideContact = dancer.isHideContact();
        int contactCost = pub.contactCost();
        boolean contactUnlocked = showAllPhotos
                || pointsService.isUnlocked(currentUserId, PointsGateTargetType.DANCER_CONTACT, dancerId);
        String contact = showAllPhotos ? dancer.getContact() : null;
        String contactImageUrl = showAllPhotos ? dancer.getContactImageUrl() : null;
        // 舞伴是否填写了联系方式（contact 或 contactImageUrl 任一非空）——普通用户侧
        // 「联系方式」行入口的权威依据（真实值恒不下发，前端无法自判"是否可获取"）。
        // ⚠️ 必须基于 dancer 原始值计算（contact/contactImageUrl 已按视角置 null，
        // 误用会导致普通用户 hasContact 恒 false、联系方式行入口消失）
        String rawContact = dancer.getContact();
        String rawContactImageUrl = dancer.getContactImageUrl();
        boolean hasContact = (rawContact != null && !rawContact.isBlank())
                || (rawContactImageUrl != null && !rawContactImageUrl.isBlank());
        // 创作者收益计划（2026-08-14）：开关 + 广告位 ID（配置下发，前端零硬编码）+
        // 累计广告支持次数（收益线下结算依据）
        boolean earningsEnabled = dancer.isEarningsEnabled();
        String earningsAdUnitId = earningsEnabled ? dancerAdProperties.adUnitId() : "";
        // 资料标签（2026-08-24：profile_tags 为 dancer 主行字段，实体已加载——
        // 反序列化 + 字典解析（text + description，长按/点击弹说明的权威文案））
        List<TagItemResponse> profileTags =
                tagDictService.resolveOrdered(tagDictService.deserializeIds(dancer.getProfileTags()));
        return new DancerDetailResponse(
                dancer.getId(), dancer.getNickname(), dancer.getAvatarUrl(), dancer.getBio(),
                dancer.getGender(), dancer.getCity(), pub.cities(), profileTags, dancer.getStatus(),
                dancer.getVerificationStatus(), dancer.getVerifiedAt(),
                isMine, myToday, myTags, favorite, pub.stats(),
                pub.pointsReceivedTotal(), pub.pointsReceived30d(), pub.giftsReceived(),
                fetchPhotos(dancerId, showAllPhotos, currentUserId),
                pub.tags(), pub.venues(), pub.services(),
                hasContact, contact, contactImageUrl, hideContact, contactCost, contactUnlocked,
                earningsEnabled, earningsAdUnitId, pub.adViews());
    }

    // ─── 服务范围（2026-08-24：admin 录入的黄页内容；详情公开读 + 管理端写） ─────

    /**
     * 单舞伴在用服务列表（GET /dancers/{id}/services，公开软鉴权——与详情同可见性校验）。
     * 详情页服务卡数据已随详情响应下发（pub.services，公共缓存），本端点供独立
     * 场景/前端降级直查（口径一致）。
     */
    @Transactional(readOnly = true)
    public List<DancerServiceResponse> listServices(Long dancerId, Long currentUserId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, currentUserId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        return dancerServiceRepository.findByDancerIdAndDeletedFalseAndActiveTrueOrderBySortOrderAscIdAsc(dancerId)
                .stream().map(this::toServiceResponse).toList();
    }

    /** admin 新增服务范围（POST /admin/dancers/{id}/services，平台代发黄页内容——合规见 AGENTS.md） */
    @Transactional
    public DancerServiceResponse addService(Long adminId, Long dancerId, UpsertDancerServiceRequest request) {
        Dancer dancer = findDancerOrThrow(dancerId);
        org.quwuting.quwutingservice.dancer.entity.DancerService service =
                new org.quwuting.quwutingservice.dancer.entity.DancerService();
        service.setDancerId(dancerId);
        applyServiceFields(service, request, dancerServiceRepository.findMaxSortOrder(dancerId) + 1);
        return saveService(adminId, dancer, service);
    }

    /** admin 更新服务范围（POST /admin/dancers/{id}/services/{serviceId}；全量覆盖可编辑字段） */
    @Transactional
    public DancerServiceResponse updateService(Long adminId, Long dancerId, Long serviceId,
                                               UpsertDancerServiceRequest request) {
        Dancer dancer = findDancerOrThrow(dancerId);
        org.quwuting.quwutingservice.dancer.entity.DancerService service =
                dancerServiceRepository.findByIdAndDeletedFalse(serviceId)
                        .orElseThrow(() -> new BusinessException(1001, "服务不存在"));
        if (!service.getDancerId().equals(dancerId)) {
            throw new BusinessException(1001, "服务不属于该舞伴");
        }
        applyServiceFields(service, request, service.getSortOrder());
        return saveService(adminId, dancer, service);
    }

    /**
     * admin 下架服务（POST /admin/dancers/{id}/services/{serviceId}/remove，软删）。
     * 软删保留历史需求关联（qwt_demand_records.service_ids 存服务 id，删除后仍可审计）；
     * 同舞伴同标签的服务软删后不占唯一索引位，可重建。
     */
    @Transactional
    public void removeService(Long adminId, Long dancerId, Long serviceId) {
        Dancer dancer = findDancerOrThrow(dancerId);
        org.quwuting.quwutingservice.dancer.entity.DancerService service =
                dancerServiceRepository.findByIdAndDeletedFalse(serviceId)
                        .orElseThrow(() -> new BusinessException(1001, "服务不存在"));
        if (!service.getDancerId().equals(dancerId)) {
            throw new BusinessException(1001, "服务不属于该舞伴");
        }
        service.setDeleted(true);
        service.setActive(false);
        dancerServiceRepository.save(service);
        detailCacheService.invalidate(dancerId); // 服务范围在详情公共缓存内，写路径显式失效
        invalidateListCache(); // 服务类别筛选的列表成员资格变化（全清，条目数小可接受）
        log.info("管理员 {} 下架舞伴 {} 的服务「{}」", adminId, dancerId, service.getLabel());
    }

    /**
     * 可编辑字段全量覆盖（trim 归一；缺省 sortOrder = 传入值——新增 = max+1，更新 = 原值）。
     * 2026-08-24 晚：category=PACKAGE 时子类别必填；2026-08-25 晚二轮：子类别<b>多选</b>
     * （subCategories 列表 → 逗号连接的 code 串落库），其余类别忽略恒置 null；
     * 2026-08-26：label 改<b>服务端权威派生</b>（buildServiceLabel）+ negotiable
     * 回头客/熟人可谈（缺省 true）。
     */
    private void applyServiceFields(org.quwuting.quwutingservice.dancer.entity.DancerService service,
                                    UpsertDancerServiceRequest request, int defaultSortOrder) {
        List<DancerServiceSubCategory> subs = request.category() == DancerServiceCategory.PACKAGE
                ? normalizeSubCategories(request.subCategories()) : null;
        service.setLabel(buildServiceLabel(request.category(), subs, request.label()));
        service.setCategory(request.category());
        service.setSubCategory(subs != null
                ? subs.stream().map(DancerServiceSubCategory::name).collect(Collectors.joining(","))
                : null);
        service.setPriceText(norm(request.priceText()));
        service.setLocationScope(norm(request.locationScope()));
        service.setAdvanceNotice(norm(request.advanceNotice()));
        service.setRules(norm(request.rules()));
        service.setNegotiable(request.negotiable() == null || request.negotiable());
        service.setSortOrder(request.sortOrder() != null ? request.sortOrder() : defaultSortOrder);
        service.setActive(true);
    }

    /**
     * label 服务端权威派生（2026-08-26：表单删除「服务标签」输入——包时 =
     * 子类别名顿号连接+「包时」，舞厅跳舞/线上陪聊 = 类别名，仅「其他」类别
     * admin 手动录入「服务内容」（必填，如「户外露营」）。
     * 存量自定义 label（含 OTHER）原样保留：请求 label 非空 → 直接采用；
     * 空 → 按类别派生（OTHER 无默认名 → 1001 提示录入服务内容）。
     */
    private static String buildServiceLabel(DancerServiceCategory category,
                                            List<DancerServiceSubCategory> subs,
                                            String rawLabel) {
        String manual = rawLabel == null ? "" : rawLabel.trim();
        if (!manual.isEmpty()) {
            return manual;
        }
        if (category == DancerServiceCategory.PACKAGE) {
            return subs.stream().map(DancerServiceSubCategory::defaultLabel)
                    .collect(Collectors.joining("、")) + "包时";
        }
        if (category == DancerServiceCategory.OTHER) {
            throw new BusinessException(1001, "请填写服务内容");
        }
        return category.defaultLabel();
    }

    /** 包时子类别归一：非空校验 + 去重保序（PACKAGE 必选 ≥1，2026-08-25 晚二轮多选） */
    private static List<DancerServiceSubCategory> normalizeSubCategories(List<DancerServiceSubCategory> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new BusinessException(1001, "包时请至少选择 1 个子类别（酒吧/舞厅/私影/KTV/其他）");
        }
        return raw.stream().distinct().toList();
    }

    /** 可空字段归一（null → 空串；trim） */
    private static String norm(String v) {
        return v == null ? "" : v.trim();
    }

    /** 保存服务：同舞伴同标签唯一预检 + 库内唯一索引兜底（SQLState 23505 → 1001）+ 详情缓存失效 */
    private DancerServiceResponse saveService(Long adminId, Dancer dancer,
                                              org.quwuting.quwutingservice.dancer.entity.DancerService service) {
        dancerServiceRepository.findByDancerIdAndLabelAndDeletedFalse(service.getDancerId(), service.getLabel())
                .filter(existing -> !existing.getId().equals(service.getId()))
                .ifPresent(existing -> {
                    throw new BusinessException(1001, "该舞伴已有同名服务");
                });
        try {
            dancerServiceRepository.saveAndFlush(service);
        } catch (DataIntegrityViolationException e) {
            // 并发竞态兜底（admin 单人写入场景罕见，库内部分唯一索引为准）
            throw new BusinessException(1001, "该舞伴已有同名服务");
        }
        detailCacheService.invalidate(dancer.getId()); // 服务范围在详情公共缓存内
        invalidateListCache(); // 服务类别筛选的列表成员资格变化（全清，条目数小可接受）
        log.info("管理员 {} 保存舞伴 {} 服务「{}」（{}）", adminId, dancer.getId(), service.getLabel(), service.getCategory());
        return toServiceResponse(service);
    }

    /** 实体 → 响应：subCategory 逗号串 → subCategories 列表（按枚举声明序，兼容旧单值） */
    private DancerServiceResponse toServiceResponse(org.quwuting.quwutingservice.dancer.entity.DancerService s) {
        List<DancerServiceSubCategory> subs = parseSubCategories(s.getSubCategory());
        return new DancerServiceResponse(s.getId(), s.getCategory(), s.getCategory().defaultLabel(),
                subs, subs.stream().map(DancerServiceSubCategory::defaultLabel).toList(), s.getLabel(),
                s.getPriceText(), s.getLocationScope(), s.getAdvanceNotice(), s.getRules(),
                s.isNegotiable());
    }

    /** 逗号连接的子类别 code 串 → 枚举列表（空串/null → 空列表；非法 code 防御性忽略） */
    private static List<DancerServiceSubCategory> parseSubCategories(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(","))
                .filter(code -> !code.isBlank())
                .map(code -> {
                    try {
                        return DancerServiceSubCategory.valueOf(code);
                    } catch (IllegalArgumentException e) {
                        return null; // 脏数据防御（历史/手工改动），忽略非法项
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    // ─── 创作者收益计划（2026-08-14：激励视频广告观看上报） ─────────────────────

    /**
     * 广告观看完成上报（POST /dancers/{id}/ad-views）——激励视频完整观看后由前端调用，
     * 计入该舞伴的收益记录（线下结算依据）。
     * <ul>
     *   <li>目标须对当前用户可见（canView）且开启收益计划；</li>
     *   <li><b>本人不可观看自己的广告</b>（自刷收益红线，同自赠检测语义）；</li>
     *   <li>同一用户同舞伴<b>每天至多一次</b>（UNIQUE(user,dancer,view_date)）——2026-08-19
     *       根因修复：原子 upsert（ON CONFLICT DO NOTHING，恒 1 次往返、零异常）替代
     *       「先查后插 + 23505 异常吞掉」（Hibernate flush 失败后事务可能已被标记
     *       rollback-only，幂等返回实际变为 HTTP 500 且响应内查询行为不可靠）；
     *       affected=0 = 当日已支持，幂等返回 recorded=false，不重复计收益。</li>
     * </ul>
     *
     * @return recorded（是否计入收益）+ 该舞伴累计广告支持次数
     */
    @Transactional
    public AdViewResponse recordAdView(Long userId, Long dancerId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        if (!dancer.isEarningsEnabled()) {
            throw new BusinessException(1001, "该舞伴未开启创作者收益计划");
        }
        if (dancer.getCreatedBy().equals(userId)) {
            throw new BusinessException(1001, "不能观看自己舞伴的广告");
        }
        LocalDate today = LocalDate.now();
        int inserted = adViewRepository.upsertAdView(dancerId, userId, today, LocalDateTime.now());
        long total = adViewRepository.countByDancerId(dancerId);
        if (inserted > 0) {
            log.info("用户 {} 观看广告支持舞伴 {}（累计 {} 次）", userId, dancerId, total);
        }
        return new AdViewResponse(inserted > 0, total);
    }

    /** 单舞伴标签聚合（GET /dancers/{id}/tags，公开软鉴权；先校验舞伴可见性）。
     *  2026-08-19：响应扩展为 {@link DancerTagsResponse}——tags 走详情公共缓存
     *  （认可行为聚合，与详情页标签云同源同口径，认可写路径已失效缓存，60s
     *  refresh-ahead 兜底）；myTags = 当前用户今日认可携带的标签（个人态恒实时查询，
     *  严禁进用户无关缓存，对齐 getDetail 的 myTags 查询范式——同一唯一索引单次命中）。 */
    @Transactional(readOnly = true)
    public DancerTagsResponse getTags(Long dancerId, Long currentUserId, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canView(dancer, currentUserId, currentRole)) {
            throw new BusinessException(1003, "该舞伴资料暂不可见");
        }
        List<String> myTags = Collections.emptyList();
        if (currentUserId != null) {
            Optional<DancerRecognition> myTodayRec = recognitionRepository
                    .findByUserIdAndDancerIdAndRecognitionDate(currentUserId, dancerId, LocalDate.now());
            myTags = myTodayRec.map(r -> fetchTagsForRecognition(r.getId())).orElseGet(Collections::emptyList);
        }
        return new DancerTagsResponse(detailCacheService.get(dancerId).tags(), myTags);
    }

    // ─── 认可（每日一记 toggle；2026-08-15 单票换票 + 可配置多选，对齐 Reaction 语义） ───

    /**
     * 切换认可状态（2026-08-15 交互模型变更：认可从「标签选择器确认（0-3 个标签）」改造为
     * Reaction 风格的表情 chip 单票——点按即 toggle；每日一票由运营开关
     * {@link OpsConfigService#KEY_DANCER_RECOGNITION_DAILY_SINGLE} 控制（默认开））：
     * <ul>
     *   <li><b>新模型（请求携带单个 tag）+ 每日一票开（默认）</b>：今日未认可 → 参与
     *       （写入该标签）；今日同标签 → 取消（批量删标签 + 批量删认可）；今日异标签 →
     *       <b>原子换票</b>（旧标签批量删除 + 新标签写入，replacedFrom=旧标签）；
     *       同键并发由 pg_advisory_xact_lock 串行化（对齐 VenueReactionService）；</li>
     *   <li><b>新模型 + 每日一票关（多选）</b>：每枚表情独立 toggle——未选 → 累加；
     *       已选 → 移除（批量删该标签）；今日标签清空 → 删除认可记录；</li>
     *   <li><b>旧模型（tag 缺省，tags 列表 0-3 个）</b>：未认可 → 参与；已认可 → 取消
     *       ——旧客户端兼容路径；</li>
     *   <li>并发幂等：认可插入 23505 → 复用既有记录；标签插入 23505（UNIQUE(recognitionId, tag)）
     *       → 幂等忽略；删除一律 @Modifying 批量删除（不存在行 = 0 行影响，无
     *       StaleObjectStateException——2026-08-15 根因修复，见 repository javadoc）。</li>
     * </ul>
     * 缓存失效：内联失效保证响应统计（buildStats）事务内重算为最新（响应值 = 操作后真相）+
     * afterCommit/afterCompletion 兜底（防并发读者缓存旧值 / 事务回滚污染缓存——对齐
     * VenueReactionService 根因修复，见「写路径缓存失效」约定）。
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
        // 新模型单标签（tag 字段非空且命中字典）；缺省 = 旧客户端 tags 列表语义
        String singleTag = resolveSingleTag(request);
        boolean dailySingle = opsConfigService.isEnabled(OpsConfigService.KEY_DANCER_RECOGNITION_DAILY_SINGLE, true);
        boolean recognized;
        String replacedFrom = null;
        List<String> myTags = Collections.emptyList();
        if (singleTag != null) {
            if (dailySingle) {
                // ── 每日一票（默认）：参与 / 同票取消 / 异票原子换票（咨询锁串行化） ──
                recognitionRepository.lockDailyTicket("recognition:" + userId + ":" + dancerId + ":" + today);
                Optional<DancerRecognition> existing = recognitionRepository
                        .findByUserIdAndDancerIdAndRecognitionDate(userId, dancerId, today);
                if (existing.isPresent()) {
                    List<String> todayTags = fetchTagsForRecognition(existing.get().getId());
                    if (todayTags.contains(singleTag)) {
                        // 取消当日认可：批量删标签 + 批量删认可（幂等、无实体删除竞态）
                        recognitionTagRepository.deleteByRecognitionId(existing.get().getId());
                        recognitionRepository.deleteRecognitionById(existing.get().getId());
                        recognized = false;
                    } else {
                        // 换票：旧标签批量删除 + 新标签写入（认可记录本身不删，四窗口计数不变）
                        recognitionTagRepository.deleteByRecognitionId(existing.get().getId());
                        insertRecognitionTags(existing.get().getId(), dancerId, userId, List.of(singleTag));
                        recognized = true;
                        myTags = List.of(singleTag);
                        // 单票模型旧票唯一可表达；旧多标签历史记录无法以单值表达 → null（前端以 tags 绝对快照收敛）
                        replacedFrom = todayTags.size() == 1 ? todayTags.get(0) : null;
                    }
                } else {
                    long recognitionId = insertRecognition(userId, dancerId, today);
                    insertRecognitionTags(recognitionId, dancerId, userId, List.of(singleTag));
                    recognized = true;
                    myTags = List.of(singleTag);
                }
            } else {
                // ── 多选模式（开关关）：每枚表情独立 toggle（累加 / 移除；清空 → 删认可） ──
                Optional<DancerRecognition> existing = recognitionRepository
                        .findByUserIdAndDancerIdAndRecognitionDate(userId, dancerId, today);
                if (existing.isPresent()) {
                    List<String> todayTags = fetchTagsForRecognition(existing.get().getId());
                    if (todayTags.contains(singleTag)) {
                        recognitionTagRepository.deleteByRecognitionIdAndTag(existing.get().getId(), singleTag);
                        List<String> next = new ArrayList<>(todayTags);
                        next.remove(singleTag);
                        recognized = false;
                        myTags = next;
                        if (next.isEmpty()) {
                            // 今日全部表情移除 = 取消认可（认可记录整体删除）
                            recognitionRepository.deleteRecognitionById(existing.get().getId());
                        }
                    } else {
                        insertRecognitionTags(existing.get().getId(), dancerId, userId, List.of(singleTag));
                        recognized = true;
                        myTags = new ArrayList<>(todayTags);
                        myTags.add(singleTag);
                    }
                } else {
                    long recognitionId = insertRecognition(userId, dancerId, today);
                    insertRecognitionTags(recognitionId, dancerId, userId, List.of(singleTag));
                    recognized = true;
                    myTags = List.of(singleTag);
                }
            }
        } else {
            // ── 旧客户端：tags 列表（0-3）兼容路径 ──
            Optional<DancerRecognition> existing = recognitionRepository
                    .findByUserIdAndDancerIdAndRecognitionDate(userId, dancerId, today);
            if (existing.isPresent()) {
                recognitionTagRepository.deleteByRecognitionId(existing.get().getId());
                recognitionRepository.deleteRecognitionById(existing.get().getId());
                recognized = false;
            } else {
                List<String> tags = validateAndDedupeTags(request);
                long recognitionId = insertRecognition(userId, dancerId, today);
                insertRecognitionTags(recognitionId, dancerId, userId, tags);
                recognized = true;
                myTags = tags;
            }
        }
        // 内联失效：使响应统计（detailCacheService 内 stats/tags）在事务内重算为最新
        // （响应值 = 操作后真相；detailCacheService.invalidate 级联失效内层
        // DancerAggregateService 与 DancerStatsService——单一失效入口，2026-08-19）
        detailCacheService.invalidate(dancerId);
        // 认可数变化直接改变公开列表排序（近7天认可倒序）与卡片 chips——列表缓存失效
        invalidateListCache();
        // 事务边界兜底（对齐 VenueReactionService 根因修复）：
        // - afterCommit 再失效：并发读者在内联失效与提交之间回源可能缓存旧值 → 提交后清除；
        // - afterCompletion(非提交) 失效：事务回滚时清除内联失效后写入缓存的"未提交值"（防幻影）。
        // 单元测试无事务（isSynchronizationActive=false）时跳过注册——生产恒在事务内。
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    detailCacheService.invalidate(dancerId);
                    listCacheService.invalidateAll();
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != STATUS_COMMITTED) {
                        detailCacheService.invalidate(dancerId);
                        listCacheService.invalidateAll();
                    }
                }
            });
        }
        DancerDetailCacheService.PublicPart pub = detailCacheService.get(dancerId);
        return new RecognizeResponse(recognized, replacedFrom, myTags, pub.stats(), pub.tags());
    }

    /** 解析新模型单标签：tag 字段非空且命中字典 → 返回；缺省 → null（旧列表语义）；无效 → 业务错 */
    private String resolveSingleTag(RecognizeDancerRequest request) {
        if (request == null || request.tag() == null || request.tag().isBlank()) {
            return null;
        }
        if (!DancerTagCode.isValid(request.tag())) {
            throw new BusinessException(1001, "无效的舞伴标签");
        }
        return request.tag();
    }

    /** 单认可记录携带的标签列表（换票判定 / 响应 myTags 用） */
    private List<String> fetchTagsForRecognition(Long recognitionId) {
        List<String> result = new ArrayList<>();
        for (Object[] row : recognitionTagRepository.findTagsByRecognitionIds(List.of(recognitionId))) {
            result.add((String) row[1]);
        }
        return result;
    }

    /**
     * 插入今日认可记录，返回记录 ID。
     * <p>
     * 2026-08-20 确定性化（根因修复，替代「save + catch 23505 + clear + 同事务回查」：
     * PG 语句失败后事务中止（25P02），catch 内回查必然 HTTP 500）：先经
     * {@code upsertRecognition} 原子 upsert（命中 UNIQUE(user, dancer, date) 则
     * DO NOTHING，恒 1 次往返零异常），再按唯一键回查复用——新插入或并发赢家行
     * 均可取得，语义与旧 catch 分支完全一致但无异常路径。每日一票主路径已由
     * {@code lockDailyTicket} 咨询锁串行化（2026-08-15），本 upsert 收口多选/旧
     * 客户端路径的并发首写。
     */
    private long insertRecognition(Long userId, Long dancerId, LocalDate today) {
        recognitionRepository.upsertRecognition(dancerId, userId, today, LocalDateTime.now());
        return recognitionRepository.findByUserIdAndDancerIdAndRecognitionDate(userId, dancerId, today)
                .orElseThrow(() -> new IllegalStateException(
                        "认可 upsert 后未找到记录: userId=" + userId + ", dancerId=" + dancerId
                                + ", date=" + today))
                .getId();
    }

    /**
     * 写入认可标签（循环 upsert；2026-08-20 确定性化——撞 UNIQUE(recognitionId, tag)
     * 后事务已中止，旧「catch 23505 + clear + 继续循环」会让后续标签的 save 抛
     * 25P02 → HTTP 500；ON CONFLICT DO NOTHING 恒零异常，重复标签幂等忽略）。
     */
    private void insertRecognitionTags(Long recognitionId, Long dancerId, Long userId, List<String> tags) {
        LocalDateTime now = LocalDateTime.now();
        for (String tag : tags) {
            recognitionTagRepository.upsertRecognitionTag(recognitionId, dancerId, userId, tag, now);
        }
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
        // 用户无关 enrichments（单一权威 = DancerListCacheService）；个人态恒实时查询
        DancerListCacheService.ListEnrichments en = listCacheService.computeEnrichments(ids);
        Set<Long> myTodayIds = fetchMyTodayIds(ids, userId);
        Map<Long, List<String>> myTagsById = fetchMyTodayTags(ids, userId);

        List<DancerSummaryResponse> result = new ArrayList<>(dancers.size());
        for (Dancer d : dancers) {
            long[] counts = countsById.getOrDefault(d.getId(), new long[]{0L, 0L, 0L});
            result.add(new DancerSummaryResponse(
                    d.getId(), d.getNickname(), d.getAvatarUrl(), d.getBio(), d.getGender(), d.getCity(),
                    en.profileTagsById().getOrDefault(d.getId(), Collections.emptyList()),
                    d.getStatus(), d.getVerificationStatus(), en.homeVenueNameById().get(d.getId()), en.coverPhotoUrlById().get(d.getId()),
                    counts[0], counts[2], counts[1],
                    myTodayIds.contains(d.getId()),
                    myTagsById.getOrDefault(d.getId(), Collections.emptyList()),
                    en.tagsById().getOrDefault(d.getId(), Collections.emptyList()),
                    en.viewCounts().getOrDefault(d.getId(), 0L),
                    // 本人视角：所有媒体恒解锁（showAllMedia=true，见 buildMediaPreviews）
                    buildMediaPreviews(en.mediaPreviewsById().get(d.getId()), Collections.emptySet(), true),
                    en.onlineServiceDancerIds().contains(d.getId())));
        }
        return result;
    }

    // ─── 相册（本人上传 → 逐张 PENDING 审核 → 管理员审 PUBLIC/REJECTED） ─────────

    /**
     * 舞伴本人/管理员上传相册照片（POST /dancers/{id}/photos）。
     * 上传即 PENDING（先审后发）；sortOrder 追加（取当前最大 +1），维持上传序。
     * 普通用户不可调用（canManage 校验——对舞伴唯一可写公开影响 = 认可+标签的约束不变）。
     * 返回本人视角全量照片（含刚上传的待审项）。
     * <p>
     * 2026-08-19 加固：① 单次数量上限 {@link #MAX_PHOTOS_PER_UPLOAD}（与前端 image-upload
     * maxCount=9 对齐——后端必须独立校验，防绕过前端的一次性超大请求拖垮相册）；
     * ② 模糊图 URL 与主图同挂 ImageContentValidator 内容校验（08-12 安全约定：
     * 「新增图片 URL 落库字段必须挂载校验」——此前 blurUrl 仅校验 http 前缀，可被
     * 塞入任意外部 URL，破坏「只接受平台存储桶」防线）。
     */
    @Transactional
    public List<DancerPhotoResponse> addPhotos(Long userId, Long dancerId,
                                               List<String> urls, List<String> blurUrls, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canManage(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "仅舞伴本人或管理员可上传照片");
        }
        if (urls == null || urls.isEmpty()) {
            throw new BusinessException(1001, "请至少选择一张照片");
        }
        if (urls.size() > MAX_PHOTOS_PER_UPLOAD) {
            throw new BusinessException(1001, "单次最多上传 " + MAX_PHOTOS_PER_UPLOAD + " 张照片");
        }
        // 模糊图（2026-08-14 需求：收费照片详情页"模糊可见轮廓"占位）——与 urls 按
        // index 一一对应；缺省/为空时该照片无模糊图（详情页未解锁回退纯锁占位）
        List<String> blurList = blurUrls == null ? Collections.emptyList() : blurUrls;
        int nextOrder = maxSortOrder(dancerId) + 1;
        for (int i = 0; i < urls.size(); i++) {
            String clean = TextSanitizer.sanitize(urls.get(i), 500);
            if (clean.isEmpty() || !clean.startsWith("http")) {
                throw new BusinessException(1001, "照片地址不合法");
            }
            imageValidator.validate(clean);
            DancerPhoto photo = new DancerPhoto();
            photo.setDancerId(dancerId);
            photo.setUrl(clean);
            String blurUrl = i < blurList.size() ? TextSanitizer.sanitize(blurList.get(i), 500) : null;
            if (blurUrl != null && !blurUrl.isEmpty() && blurUrl.startsWith("http")) {
                imageValidator.validate(blurUrl); // 08-12 安全约定：图片 URL 落库字段必须挂载内容校验
                photo.setBlurUrl(blurUrl);
            }
            photo.setStatus(DancerPhotoStatus.PENDING);
            photo.setCreatedBy(userId);
            photo.setSortOrder(nextOrder++);
            photoRepository.save(photo);
        }
        // 相册变化可能影响列表封面（封面 = 展示序最小 PUBLIC 照片）——列表缓存失效
        invalidateListCache();
        return fetchPhotos(dancerId, true, userId);
    }

    /**
     * 舞伴短视频上传（2026-08-22 新增；与照片同审核链——插入即 PENDING，逐条审核后公开）。
     * <p>
     * 请求体语义与照片不同（视频 = urls + coverUrls 封面帧 + durations 时长，无 blurUrls——
     * 本期视频不上积分门槛，封面帧图承担视觉占位），故独立接口而非混入 AddDancerPhotosRequest。
     * 合规：个人主体小程序 UGC 红线下视频同照片——仅舞伴本人/管理员可上传（canManage），
     * 内容经逐条 PENDING 审核后才公开（恶意内容双闸门 = 凭证签发扩展名/大小校验 + 人审）。
     */
    @Transactional
    public List<DancerPhotoResponse> addVideos(Long userId, Long dancerId,
                                               AddDancerVideosRequest req, UserRole currentRole) {
        Dancer dancer = findDancerOrThrow(dancerId);
        if (!canManage(dancer, userId, currentRole)) {
            throw new BusinessException(1003, "仅舞伴本人或管理员可上传视频");
        }
        List<String> urls = req.urls();
        if (urls == null || urls.isEmpty()) {
            throw new BusinessException(1001, "请至少选择一个视频");
        }
        if (urls.size() > MAX_VIDEOS_PER_UPLOAD) {
            throw new BusinessException(1001, "单次最多上传 " + MAX_VIDEOS_PER_UPLOAD + " 个视频");
        }
        List<String> coverList = req.coverUrls() == null ? Collections.emptyList() : req.coverUrls();
        List<String> blurList = req.blurUrls() == null ? Collections.emptyList() : req.blurUrls();
        List<Integer> durationList = req.durations() == null ? Collections.emptyList() : req.durations();
        int nextOrder = maxSortOrder(dancerId) + 1;
        for (int i = 0; i < urls.size(); i++) {
            String clean = TextSanitizer.sanitize(urls.get(i), 500);
            if (clean.isEmpty() || !clean.startsWith("http")) {
                throw new BusinessException(1001, "视频地址不合法");
            }
            imageValidator.validateVideoUrl(clean); // 域名白名单 + 扩展名（不下载，见校验器 javadoc）
            DancerPhoto video = new DancerPhoto();
            video.setDancerId(dancerId);
            video.setUrl(clean);
            video.setKind(DancerPhotoKind.VIDEO);
            String coverUrl = i < coverList.size()
                    ? TextSanitizer.sanitize(coverList.get(i), 500) : null;
            if (coverUrl != null && !coverUrl.isEmpty() && coverUrl.startsWith("http")) {
                try {
                    imageValidator.validate(coverUrl); // 封面 = 图片，挂图片内容校验（08-12 安全约定）
                    video.setCoverUrl(coverUrl);
                } catch (BusinessException e) {
                    // 封面帧异常（2026-08-22：前端已做 thumb 立即持久化兜底，此分支仅防御
                    // 个别机型封面损坏/3 字节占位）——降级无封面（未过校验的 URL 不落库，
                    // 安全约定不破坏），不阻断整批视频入库；展示端无封面回退虚焦占位。
                    log.warn("封面帧校验失败，降级无封面：dancerId={} url={} 原因={}",
                            dancerId, clean, e.getMessage());
                }
            }
            // 模糊封面（2026-08-22 视频门槛配套：封面帧降采样模糊版，未解锁遮罩用；
            // 缺省 = 有门槛视频未解锁时纯锁占位）
            String blurUrl = i < blurList.size()
                    ? TextSanitizer.sanitize(blurList.get(i), 500) : null;
            if (blurUrl != null && !blurUrl.isEmpty() && blurUrl.startsWith("http")) {
                imageValidator.validate(blurUrl); // 08-12 安全约定：图片 URL 落库字段必须挂载校验
                video.setBlurUrl(blurUrl);
            }
            int duration = i < durationList.size() && durationList.get(i) != null
                    ? Math.max(0, durationList.get(i)) : 0;
            video.setDurationSeconds(duration);
            video.setStatus(DancerPhotoStatus.PENDING);
            video.setCreatedBy(userId);
            video.setSortOrder(nextOrder++);
            photoRepository.save(video);
        }
        invalidateListCache();
        return fetchPhotos(dancerId, true, userId);
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
        // 照片删除可能影响列表封面——列表缓存失效
        invalidateListCache();
    }

    // ─── 管理端照片审核 ────────────────────────────────────────────────────────

    /**
     * 管理端照片审核列表（仅 ADMIN，含全部状态，按上传时间倒序——新照片优先审核）。
     * status 可选过滤（缺省全部，管理员从「待审核」筛选进入待办）。
     */
    @Transactional(readOnly = true)
    public Page<AdminDancerPhotoResponse> listAdminPhotos(DancerPhotoStatus status, int page, int size) {
        Pageable pageable = sanePage(page, size);
        Page<Object[]> rows = photoRepository.findAdminPage(status == null ? null : status.name(), pageable);
        List<AdminDancerPhotoResponse> content = rows.getContent().stream()
                .map(r -> new AdminDancerPhotoResponse(
                        (Long) r[0], (String) r[1], DancerPhotoStatus.valueOf((String) r[2]),
                        (Long) r[3], r[4] != null ? (String) r[4] : PHOTO_OWNER_GONE_NAME, (String) r[5], (String) r[6],
                        (LocalDateTime) r[7],
                        DancerPhotoKind.valueOf((String) r[8]), (String) r[9],
                        r[10] != null ? ((Number) r[10]).intValue() : 0))
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
        // 照片审核（PENDING → PUBLIC）可能改变列表封面——列表缓存失效
        invalidateListCache();
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
        Pageable pageable = sanePage(page, size);
        Page<Object[]> rows = dancerRepository.findAdminPage(status == null ? null : status.name(), pageable);
        List<AdminDancerResponse> content = rows.getContent().stream()
                .map(r -> new AdminDancerResponse(
                        (Long) r[0], (String) r[1], (String) r[2], (String) r[3], (String) r[4], (String) r[5],
                        DancerStatus.valueOf((String) r[6]),
                        toVerificationStatus(r[10]), (LocalDateTime) r[11],
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
        // 状态流转（PENDING → NORMAL / NORMAL ↔ HIDDEN）改变公开列表可见性——列表缓存失效
        invalidateListCache();
        notifyStatusChange(dancer, from, status, reason);
    }

    // ─── 信息核验（2026-08-14 官方认证：授予 / 撤销 / 复核） ─────────────────────

    /**
     * 管理端信息核验操作（PUT /admin/dancers/{id}/verification，仅 ADMIN）。
     * <ul>
     *   <li>{@link DancerVerificationAction#VERIFY}：授予认证——UNVERIFIED / PENDING_REVIEW
     *       → VERIFIED（PENDING_REVIEW 场景 = 复核后确认恢复）；reason 可选（仅审计）；</li>
     *   <li>{@link DancerVerificationAction#UNVERIFY}：撤销认证——VERIFIED / PENDING_REVIEW
     *       → UNVERIFIED，<b>reason 必填</b>（撤销必须留痕理由，随站内信通知舞伴——
     *       延续"被指涉方有申辩权"：被撤销舞伴可查原因）。</li>
     * </ul>
     * 目标状态相同幂等返回；每次实际变迁写审计日志（qwt_dancer_verification_logs）
     * 并按「状态流转对用户有结果的通知必须走站内信」约定通知创建人（同事务）。
     */
    @Transactional
    public void updateVerification(Long adminId, Long dancerId, DancerVerificationAction action, String reason) {
        Dancer dancer = findDancerOrThrow(dancerId);
        DancerVerificationStatus from = dancer.getVerificationStatus();
        if (action == DancerVerificationAction.VERIFY) {
            if (from == DancerVerificationStatus.VERIFIED) {
                return; // 幂等：已认证
            }
            transitionVerification(dancer, DancerVerificationStatus.VERIFIED,
                    adminId, TextSanitizer.sanitize(reason, 200));
            notifyVerification(dancer, true, null);
        } else {
            if (from == DancerVerificationStatus.UNVERIFIED) {
                return; // 幂等：未认证
            }
            String cleanReason = TextSanitizer.sanitize(reason, 200);
            if (cleanReason.isBlank()) {
                throw new BusinessException(1001, "撤销认证必须填写原因");
            }
            transitionVerification(dancer, DancerVerificationStatus.UNVERIFIED, adminId, cleanReason);
            notifyVerification(dancer, false, cleanReason);
        }
    }

    /**
     * 认证状态迁移（唯一状态变更出口）：更新 dancer 快照（VERIFIED 写入
     * verifiedAt/verifiedBy，其余清空——历史在审计日志）+ 写审计日志（同事务）。
     */
    private void transitionVerification(Dancer dancer, DancerVerificationStatus to,
                                        Long operatorId, String reason) {
        DancerVerificationStatus from = dancer.getVerificationStatus();
        if (from == to) {
            return; // 幂等：无变更不写日志
        }
        dancer.setVerificationStatus(to);
        if (to == DancerVerificationStatus.VERIFIED) {
            dancer.setVerifiedAt(LocalDateTime.now());
            dancer.setVerifiedBy(operatorId);
        } else {
            dancer.setVerifiedAt(null);
            dancer.setVerifiedBy(null);
        }
        dancerRepository.save(dancer);
        DancerVerificationLog audit = new DancerVerificationLog();
        audit.setDancerId(dancer.getId());
        audit.setOperatorId(operatorId);
        audit.setFromStatus(from.name());
        audit.setToStatus(to.name());
        audit.setReason(reason);
        verificationLogRepository.save(audit);
        // 认证流转改变列表行 verification_status——列表缓存失效
        invalidateListCache();
        log.info("舞伴 {} 信息核验 {} → {}（操作人 {}）{}", dancer.getId(), from, to, operatorId,
                reason == null || reason.isBlank() ? "" : "，原因：" + reason);
    }

    /** 认证授予/撤销站内信（与状态流转同事务；文案真实正式、只陈述事实，禁营销化描述） */
    private void notifyVerification(Dancer dancer, boolean granted, String reason) {
        String nickname = dancer.getNickname();
        if (granted) {
            messageService.create(dancer.getCreatedBy(), MessageType.DANCER_VERIFICATION,
                    "信息核验通过",
                    "你的舞伴主页「" + nickname + "」已通过平台信息核验，获得「信息已核验」标识。",
                    "DANCER", dancer.getId());
            return;
        }
        String reasonText = reason == null || reason.isBlank()
                ? "" : "，原因：" + reason;
        messageService.create(dancer.getCreatedBy(), MessageType.DANCER_VERIFICATION,
                "信息核验标识已移除",
                "你的舞伴主页「" + nickname + "」的信息核验标识已被移除" + reasonText
                        + "。可修改资料后重新申请核验。",
                "DANCER", dancer.getId());
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

    /** 认证状态安全转换（native 查询列值防御：null → UNVERIFIED） */
    private DancerVerificationStatus toVerificationStatus(Object value) {
        if (value == null) {
            return DancerVerificationStatus.UNVERIFIED;
        }
        return DancerVerificationStatus.valueOf((String) value);
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

    /** 批量"常去"舞厅名：取每个舞伴最早一条 HOME 关系（venue briefs 按 created_at 升序）。
     *  ⚠️ 仅我的认可记录（listMyRecognitions）消费——列表/收藏/我的舞伴主页的 enrichments
     *  已统一走 {@link DancerListCacheService#computeEnrichments}（2026-08-22 单一权威） */
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

    /**
     * 批量当前用户"今日投票标签"（dancerId → tag code 列表；2026-08-19 新增：
     * 列表 reaction 区域 chip 活跃态数据源）。个人态实时查询不缓存；未登录返回空表。
     * 实现 = 一次 IN 查询今日认可记录 (recognitionId, dancerId) + 一次 IN 查询标签
     * （findTagsByRecognitionIds），两查询拼接——避免按认可记录逐条查标签的 N+1。
     */
    private Map<Long, List<String>> fetchMyTodayTags(List<Long> dancerIds, Long currentUserId) {
        Map<Long, List<String>> result = new HashMap<>();
        if (currentUserId == null || dancerIds.isEmpty()) {
            return result;
        }
        List<Object[]> recs = recognitionRepository
                .findTodayRecognitionIdsByDancerIds(currentUserId, dancerIds, LocalDate.now());
        if (recs.isEmpty()) {
            return result;
        }
        Map<Long, Long> dancerByRecId = new HashMap<>();
        for (Object[] rec : recs) {
            Long recId = (Long) rec[0];
            Long dancerId = (Long) rec[1];
            dancerByRecId.put(recId, dancerId);
            result.computeIfAbsent(dancerId, k -> new ArrayList<>());
        }
        for (Object[] row : recognitionTagRepository.findTagsByRecognitionIds(new ArrayList<>(dancerByRecId.keySet()))) {
            Long recId = (Long) row[0];
            Long dancerId = dancerByRecId.get(recId);
            if (dancerId != null) {
                result.get(dancerId).add((String) row[1]);
            }
        }
        return result;
    }

    // ─── 相册辅助 ──────────────────────────────────────────────────────────────

    /**
     * 舞伴相册照片（按 sortOrder 升序 = 上传序）。
     *
     * @param showAll       true = 本人/管理员视角（全量含 PENDING/REJECTED，编辑页回显状态）；
     *                      false = 公开视角（仅 PUBLIC）。
     * @param currentUserId 当前用户（组装"已解锁"态；匿名 null）
     * @apiNote 积分解锁（2026-08-14 公共模块）：媒体有门槛（cost&gt;0）且当前用户
     * 未解锁 → <b>url 置 null</b>（不下发原内容，防绕过——原内容仅经 POST /points/unlock
     * 解锁成功后返回）；本人/管理员（showAll）恒可看。门槛/解锁态经 PointsService
     * 批量查询组装（N+1 规避）。
     * @apiNote 2026-08-22 视频门槛（媒体无关契约落地）：视频按 kind 查独立门槛类型
     * （DANCER_VIDEO，照片恒 DANCER_PHOTO——gate 表 target_type 区分媒体类型，统计口径
     * 不混杂）。<b>视频封面帧 = 清晰首帧，未解锁时 coverUrl 一并置 null</b>（封面即内容
     * 泄露——照片有 blurUrl 模糊降级，视频无模糊封面，只能纯锁占位）。
     */
    private List<DancerPhotoResponse> fetchPhotos(Long dancerId, boolean showAll, Long currentUserId) {
        List<DancerPhoto> photos = photoRepository.findByDancerIdAndDeletedFalseOrderBySortOrderAscIdAsc(dancerId);
        if (photos.isEmpty()) {
            return Collections.emptyList();
        }
        // 门槛/解锁态按媒体类型分组批量查询（照片 DANCER_PHOTO / 视频 DANCER_VIDEO）
        Map<Long, Integer> costs = new HashMap<>();
        Set<Long> unlockedIds = new HashSet<>();
        List<Long> photoIds = new ArrayList<>();
        List<Long> videoIds = new ArrayList<>();
        for (DancerPhoto p : photos) {
            (p.getKind() == DancerPhotoKind.VIDEO ? videoIds : photoIds).add(p.getId());
        }
        if (!photoIds.isEmpty()) {
            costs.putAll(pointsService.gateCosts(PointsGateTargetType.DANCER_PHOTO, photoIds));
            unlockedIds.addAll(pointsService.unlockedIds(currentUserId, PointsGateTargetType.DANCER_PHOTO, photoIds));
        }
        if (!videoIds.isEmpty()) {
            costs.putAll(pointsService.gateCosts(PointsGateTargetType.DANCER_VIDEO, videoIds));
            unlockedIds.addAll(pointsService.unlockedIds(currentUserId, PointsGateTargetType.DANCER_VIDEO, videoIds));
        }
        List<DancerPhotoResponse> result = new ArrayList<>(photos.size());
        for (DancerPhoto p : photos) {
            if (!showAll && p.getStatus() != DancerPhotoStatus.PUBLIC) {
                continue;
            }
            int cost = costs.getOrDefault(p.getId(), 0);
            boolean unlocked = showAll || cost == 0 || unlockedIds.contains(p.getId());
            boolean isVideo = p.getKind() == DancerPhotoKind.VIDEO;
            result.add(new DancerPhotoResponse(
                    p.getId(), unlocked ? p.getUrl() : null, p.getStatus(), p.getSortOrder(),
                    p.getCreatedAt(), cost, unlocked,
                    // 模糊封面（照片 = 原图降采样模糊图；视频 = 封面帧降采样模糊图，2026-08-22）：
                    // 未解锁时下发作遮罩占位（不泄露内容），恒不为未解锁态置空
                    p.getBlurUrl(),
                    p.getKind(),
                    // 视频未解锁：封面帧 = 清晰首帧，一并置 null（纯锁/模糊占位，防内容泄露）
                    isVideo && !unlocked ? null : p.getCoverUrl(),
                    p.getDurationSeconds()));
        }
        return result;
    }

    /** 当前最大展示顺序（新照片 sortOrder = max + 1，维持上传序；无照片返回 0）。
     *  2026-08-19：单值聚合查询（repository 内 COALESCE(MAX)）替代「全量加载后流式取 max」 */
    private int maxSortOrder(Long dancerId) {
        return photoRepository.findMaxSortOrder(dancerId);
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
