package org.quwuting.quwutingservice.venueclaim.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.common.text.TextSanitizer;
import org.quwuting.quwutingservice.config.CacheConfig;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.service.VenueService;
import org.quwuting.quwutingservice.venueclaim.dto.request.CreateVenueClaimRequest;
import org.quwuting.quwutingservice.venueclaim.dto.response.AdminVenueClaimResponse;
import org.quwuting.quwutingservice.venueclaim.dto.response.VenueClaimResponse;
import org.quwuting.quwutingservice.venueclaim.entity.VenueClaim;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.quwuting.quwutingservice.venueclaim.repository.VenueClaimRepository;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 门店认领申请服务（2026-08-11 新增，需求「认领舞厅」）。
 * <p>
 * 职责：
 * <ul>
 *   <li>用户侧：提交认领申请（submitClaim，需登录）——门店工作人员申请成为
 *       该店管理方；我的认领记录（listMyClaims）；撤回待审核申请（withdrawClaim）；</li>
 *   <li>管理侧：认领申请列表（listAdminClaims，状态筛选分页）、审核通过
 *       （approveClaim）/ 拒绝（rejectClaim）——均 requireAdmin。</li>
 * </ul>
 * <p>
 * 状态机：PENDING → APPROVED（置 qwt_venues.claimed_by = 申请人 userId，
 * 申请人自动获得管理权）/ REJECTED（拒绝，可再次提交新申请）；
 * PENDING → WITHDRAWN（申请人主动撤回）。终态固定不可回退。
 * <p>
 * 认领 vs 上报的边界（2026-08-11 决策）：认领是<b>身份归属的权限申请</b>——
 * 通过后变更门店的 claimed_by（长期权限），与 venuefeedback（数据有错的异步
 * 上报）语义不同。认领<b>不修改</b>门店任何信息字段（名称/城市/地址等）——
 * 门店数据由平台维护，认领通过后用户经 venue-create 编辑模式获得修改权。
 * <p>
 * A1 决策（只能一人认领，先到先得）：提交时校验门店未被认领（claimed_by 非空
 * 即拒绝）；审核通过时<b>再次</b>校验（并发竞态：两个管理员同时通过不同申请，
 * 先到先得，后到者报错）。未被认领的门店一旦 claimed_by 落库即锁定，其他人
 * 无法再提交申请（前端 ⋮ 菜单「认领舞厅」据此禁用，后端校验兜底）。
 * <p>
 * 隐私（D1 决策）：realName / contactPhone / contactWechat 仅存本工单表，
 * 不写入 qwt_users；用户侧视图（VenueClaimResponse）不暴露申请材料，仅管理端
 * （AdminVenueClaimResponse）完整返回供审核核对。
 * <p>
 * 文本防注入：note / handleNote / realName / contactWechat 入库前经
 * {@link TextSanitizer} 清洗；contactPhone 经 @Pattern 校验（11 位大陆手机号）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueClaimService {

    private static final String VENUE_GONE_NAME = "已下架场所";

    private final VenueClaimRepository venueClaimRepository;
    private final VenueRepository venueRepository;
    private final UserRepository userRepository;
    private final CacheManager cacheManager;
    private final tools.jackson.databind.ObjectMapper objectMapper;
    /** 详情公共部分缓存失效（2026-08-13：认领审批后 claimed 快照立即重算；无循环依赖——
     *  VenueService 依赖本模块的 repository 而非本 service） */
    private final VenueService venueService;
    /** 图片内容校验（2026-08-12 恶意文件防线：营业执照图片 URL 落库前做内容级校验） */
    private final org.quwuting.quwutingservice.storage.ImageContentValidator imageValidator;

    /**
     * 提交认领申请（需登录）。校验：
     * <ol>
     *   <li>门店存在（逻辑删除的场所不可认领）；</li>
     *   <li>门店未被认领（A1 先到先得，claimed_by 非空即拒绝）；</li>
     *   <li>本人对该门店无 PENDING 申请（库内部分唯一索引 (user_id, venue_id)
     *       WHERE status='PENDING' 兜底并发幂等，冲突时回查已有记录返回）。</li>
     * </ol>
     * 门店基础信息不在认领表单中提交（只读展示）——见类注释「认领 vs 上报的边界」。
     */
    @Transactional
    public VenueClaimResponse submitClaim(Long venueId, CreateVenueClaimRequest request) {
        Long userId = UserContext.requireAuth();
        Venue venue = venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        // A1：只能一人认领，先到先得
        if (venue.getClaimedBy() != null) {
            throw new BusinessException(1019, "该店已被认领");
        }
        String realName = TextSanitizer.sanitize(request.realName());
        String contactPhone = request.contactPhone();
        String contactWechat = TextSanitizer.sanitize(request.contactWechat());
        // 营业执照图片内容校验（URL 白名单前缀 + 尺寸/魔数防解压炸弹，见 11-storage.md）
        imageValidator.validateAll(request.licenseUrls());
        String licenseUrls = serializeStringList(request.licenseUrls());
        String note = TextSanitizer.sanitize(request.note());
        // 确定性原子写入（2026-08-20 根因修复，替代「save + catch 23505 + 同事务回查」：
        // PG 语句失败后事务中止（25P02），catch 内回查必然 HTTP 500）——命中 V12
        // PENDING 部分唯一索引则 DO NOTHING，随后回查幂等返回既有工单
        venueClaimRepository.upsertPending(venueId, userId, realName, contactPhone,
                contactWechat, licenseUrls, note, LocalDateTime.now());
        VenueClaim saved = venueClaimRepository
                .findFirstByUserIdAndVenueIdAndStatusOrderByCreatedAtDesc(userId, venueId, ClaimStatus.PENDING)
                .orElseThrow(() -> new IllegalStateException(
                        "PENDING 唯一索引 upsert 后未找到对应工单: venueId=" + venueId + ", userId=" + userId));
        log.info("venue claim submitted: venueId={}, userId={}, claimId={}", venueId, userId, saved.getId());
        return toResponse(saved, venue);
    }

    /**
     * 我的认领记录（需登录，按提交时间倒序）。返回全部状态记录——
     * 每条都有消费价值（PENDING 待审核 / APPROVED 已获得管理权 / REJECTED
     * 展示拒绝原因 / WITHDRAWN 已撤回），与「我的上报记录」同展示原则。
     * 场所名称批量查询消除 N+1；门店逻辑删除后回退"已下架场所"占位。
     */
    @Transactional(readOnly = true)
    public List<VenueClaimResponse> listMyClaims() {
        Long userId = UserContext.requireAuth();
        List<VenueClaim> claims = venueClaimRepository.findByUserIdOrderByCreatedAtDesc(userId);
        List<Long> venueIds = claims.stream().map(VenueClaim::getVenueId).distinct().toList();
        Map<Long, Venue> venueMap = venueIds.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                        .collect(Collectors.toMap(Venue::getId, v -> v, (a, b) -> a));
        return claims.stream()
                .map(c -> toResponse(c, venueMap.get(c.getVenueId())))
                .toList();
    }

    /**
     * 撤回待审核申请（需登录，本人 PENDING 可撤回 → WITHDRAWN）。
     * 幂等：非 PENDING（终态/他人）直接抛错，不静默——撤回是明确用户动作，
     * 前端只在 PENDING 态展示撤回按钮（本人校验在此兜底）。
     */
    @Transactional
    public void withdrawClaim(Long claimId) {
        Long userId = UserContext.requireAuth();
        VenueClaim claim = venueClaimRepository.findById(claimId)
                .orElseThrow(() -> new BusinessException(1008, "认领申请不存在"));
        if (!claim.getUserId().equals(userId)) {
            throw new BusinessException(1003, "无权限操作该申请");
        }
        if (claim.getStatus() != ClaimStatus.PENDING) {
            throw new BusinessException(1019, "当前状态不可撤回");
        }
        claim.setStatus(ClaimStatus.WITHDRAWN);
        venueClaimRepository.save(claim);
    }

    /**
     * 管理端认领申请列表（需 ADMIN，状态筛选 + 分页倒序）。
     * 管理端上下文完整返回申请材料（真实姓名/手机号/微信号/营业执照/说明）
     * 供审核核对，附申请人真实昵称（不做脱敏，同 AdminStatusReport 约定）。
     */
    @Transactional(readOnly = true)
    public Page<AdminVenueClaimResponse> listAdminClaims(ClaimStatus status, int page, int size) {
        UserContext.requireAdmin();
        Specification<VenueClaim> spec = (root, query, cb) -> status == null
                ? cb.conjunction()
                : cb.equal(root.get("status"), status);
        Page<VenueClaim> result = venueClaimRepository.findAll(spec,
                PageRequest.of(page, Math.min(Math.max(size, 1), 100), Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Long> venueIds = result.getContent().stream().map(VenueClaim::getVenueId).distinct().toList();
        Map<Long, Venue> venueMap = venueIds.isEmpty() ? Map.of()
                : venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                        .collect(Collectors.toMap(Venue::getId, v -> v, (a, b) -> a));
        List<Long> userIds = result.getContent().stream().map(VenueClaim::getUserId).distinct().toList();
        Map<Long, String> nicknameMap = userIds.isEmpty() ? Map.of()
                : userRepository.findAllById(userIds).stream()
                        .collect(Collectors.toMap(User::getId, u -> u.getNickname() == null ? "" : u.getNickname(),
                                (a, b) -> a));
        return result.map(c -> toAdminResponse(c, venueMap.get(c.getVenueId()), nicknameMap.get(c.getUserId())));
    }

    /**
     * 审核通过（需 ADMIN）：PENDING → APPROVED，并<b>置 qwt_venues.claimed_by =
     * 申请人 userId</b>——canManage 判定（认领人或平台管理员）自动生效，申请人
     * 获得该店管理权（编辑信息/发布动态），无需额外授权步骤。
     * <p>
     * 并发竞态（A1 先到先得）：审核时<b>再次</b>校验门店未被认领——两个管理员
     * 同时通过不同申请时，先落库者生效，后到者报错（BusinessException）。
     * <p>
     * 幂等：非 PENDING（终态重复审核）直接返回，不重复置 claimed_by。
     * handleNote 可选，清洗后落库随「我的认领」回传申请人。
     */
    @Transactional
    public void approveClaim(Long claimId, String handleNote) {
        Long adminId = UserContext.requireAdmin();
        VenueClaim claim = venueClaimRepository.findById(claimId)
                .orElseThrow(() -> new BusinessException(1008, "认领申请不存在"));
        if (claim.getStatus() != ClaimStatus.PENDING) {
            return; // 终态幂等
        }
        Venue venue = venueRepository.findByIdAndDeletedFalse(claim.getVenueId())
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
        if (venue.getClaimedBy() != null) {
            // A1 并发兜底：门店已被他人认领（可能刚通过另一申请）
            throw new BusinessException(1019, "该店已被认领");
        }
        // 置认领人 + 更新工单状态（同事务：权限授予与状态流转原子）
        venue.setClaimedBy(claim.getUserId());
        venueRepository.save(venue);
        claim.setStatus(ClaimStatus.APPROVED);
        claim.setHandledBy(adminId);
        claim.setHandledAt(LocalDateTime.now());
        if (StringUtils.hasText(handleNote)) {
            claim.setHandleNote(TextSanitizer.sanitize(handleNote));
        }
        venueClaimRepository.save(claim);
        // 场所实体缓存失效：claimed_by 变更后详情接口 canManage 需立即重算
        // （60s TTL 内旧 claimedBy 会让认领人看到 false 的管理入口）。
        // key = venueId（venue 缓存唯一键约定，见 VenueService @CacheEvict key="#id"）；
        // 显式 CacheManager.evict 而非 @CacheEvict——key 依赖事务内查询结果，
        // 无法在方法签名 SpEL 表达。
        Cache cache = cacheManager.getCache(CacheConfig.CACHE_VENUE);
        if (cache != null) {
            cache.evict(claim.getVenueId());
        }
        // 详情公共部分缓存失效（claimed 快照，2026-08-13）：认领审批后
        // 「认领舞厅」菜单项禁用态需立即生效（30s refresh 兜底太慢，显式失效）
        venueService.invalidateDetailPublic(claim.getVenueId());
        log.info("venue claim approved: claimId={}, venueId={}, newClaimedBy={}, adminId={}",
                claimId, claim.getVenueId(), claim.getUserId(), adminId);
    }

    /**
     * 审核拒绝（需 ADMIN）：PENDING → REJECTED。申请人可再次提交新申请
     * （V12 部分唯一索引只约束 PENDING，终态不阻塞新申请）。
     * 幂等：非 PENDING 直接返回。handleNote 可选（建议填写拒绝原因，随
     * 「我的认领」回传申请人）。
     */
    @Transactional
    public void rejectClaim(Long claimId, String handleNote) {
        Long adminId = UserContext.requireAdmin();
        VenueClaim claim = venueClaimRepository.findById(claimId)
                .orElseThrow(() -> new BusinessException(1008, "认领申请不存在"));
        if (claim.getStatus() != ClaimStatus.PENDING) {
            return; // 终态幂等
        }
        claim.setStatus(ClaimStatus.REJECTED);
        claim.setHandledBy(adminId);
        claim.setHandledAt(LocalDateTime.now());
        if (StringUtils.hasText(handleNote)) {
            claim.setHandleNote(TextSanitizer.sanitize(handleNote));
        }
        venueClaimRepository.save(claim);
    }

    // ── 响应组装 ─────────────────────────────────────────────────────────────

    private VenueClaimResponse toResponse(VenueClaim claim, Venue venue) {
        return new VenueClaimResponse(
                claim.getId(),
                claim.getVenueId(),
                venue != null ? venue.getName() : VENUE_GONE_NAME,
                venue != null && venue.getCity() != null ? venue.getCity() : "",
                claim.getStatus(),
                claim.getStatus().getDisplayName(),
                claim.getHandleNote(),
                claim.getHandledAt(),
                claim.getCreatedAt());
    }

    private AdminVenueClaimResponse toAdminResponse(VenueClaim claim, Venue venue, String nickname) {
        return new AdminVenueClaimResponse(
                claim.getId(),
                claim.getVenueId(),
                venue != null ? venue.getName() : VENUE_GONE_NAME,
                venue != null && venue.getCity() != null ? venue.getCity() : "",
                venue != null ? venue.getAddress() : null,
                claim.getUserId(),
                nickname == null || nickname.isBlank() ? "舞友" : nickname,
                claim.getRealName(),
                claim.getContactPhone(),
                claim.getContactWechat(),
                deserializeStringList(claim.getLicenseUrls()),
                claim.getNote(),
                claim.getStatus(),
                claim.getStatus().getDisplayName(),
                claim.getHandleNote(),
                claim.getHandledAt(),
                claim.getCreatedAt());
    }

    /** 序列化字符串列表为 JSON 数组字符串（licenseUrls 用，与 photos 同模式），空列表存 null */
    private String serializeStringList(List<String> values) {
        if (values == null || values.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("Failed to serialize license urls: {}", values, e);
            return null;
        }
    }

    /** 反序列化 JSON 数组字符串为列表（licenseUrls 展示用），空/非法返回空列表 */
    private List<String> deserializeStringList(String json) {
        if (!StringUtils.hasText(json)) return List.of();
        try {
            List<String> list = objectMapper.readValue(json, STRING_LIST);
            return list == null ? List.of() : list;
        } catch (Exception e) {
            log.warn("Failed to deserialize license urls: {}", json, e);
            return List.of();
        }
    }

    private static final tools.jackson.core.type.TypeReference<List<String>> STRING_LIST =
            new tools.jackson.core.type.TypeReference<>() {};
}
