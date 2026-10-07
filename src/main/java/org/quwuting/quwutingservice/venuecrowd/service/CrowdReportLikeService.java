package org.quwuting.quwutingservice.venuecrowd.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikeResponse;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikersResponse;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReportLike;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportLikeRepository;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdPolicy;
import org.quwuting.quwutingservice.venuecrowd.stat.CrowdTimeText;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 今晚热度上报行级点赞「有用」（2026-09-03，docs/agents/27-venue-crowd-report.md「行级点赞」；
 * 2026-10-07 改判见 27 号「改判登记」与 53 号文档）。
 * <p>
 * 定位：人际认可层——任何用户（含本人，<b>自赞放开</b>，2026-10-07 用户再次确认）对单条上报行随手一票
 * 「这条有用」，零成本、即时、人人可得；与确认后积分（系统认可）正交补全激励闭环。
 * <p>
 * 核心机制：
 * <ul>
 *   <li><b>防刷</b>：全量唯一 (liker_id, report_id) + 软删 toggle（对齐 qwt_favorites），
 *       每人每行至多 1 票、再点取消；toggle ON 幂等由
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} 唯一键冲突兜底并发，无应用层锁；</li>
 *   <li><b>被赞通知去重 + 合并</b>：仅当 toggle 返回受影响行数 == 1（该对<b>首次赞</b>）且
 *       <b>非自赞</b>时触达上报者——2026-10-07 起<b>同一条上报的未读被赞通知合并成一条</b>
 *       （「收到 N 个赞」），不再每位新赞者一行；判定全由 DB 派生，无额外标志列；</li>
 *   <li><b>窗口锁定</b>：like/unlike 仅允许 6h 窗口内行（业务码 1020）——过期信息不可用即不可赞，
 *       封死「赞远古行」刷法；</li>
 *   <li><b>谁觉得有用（分层披露）</b>：{@link #likers}——上报者本人 / 管理员看完整名单，
 *       其他人只看分层汇总（推翻 2026-09-03「赞者匿名」，理由见 {@link CrowdLikersResponse}）；</li>
 *   <li>🚫 <b>红线</b>：赞数永不进算法（可信度加权/置信度/列表角标/热度公式）——
 *       自赞可刷，一旦进算法必死；纯展示层、不产生积分。</li>
 * </ul>
 * 读侧（summary/history 行赞数回填）由 {@link CrowdReportService} 消费本类的批量查询，
 * 赞是低频变化的纯展示数据 → 不进 Caffeine 公共缓存（与角标/最新上报行缓存解耦）。
 */
@Service
@RequiredArgsConstructor
public class CrowdReportLikeService {

    /** 站内信 relatedType（VENUE = 深链场所详情页；与 MessageType 注释约定一致） */
    private static final String RELATED_TYPE_VENUE = "VENUE";

    /** 分层汇总里徽标的展示顺序（权重由高到低，店家最后） */
    static final List<String> BADGE_ORDER = List.of(
            CrowdTrustService.BADGE_VETERAN, CrowdTrustService.BADGE_REGULAR,
            CrowdTrustService.BADGE_NORMAL, CrowdTrustService.BADGE_OWNER);

    public static final String NO_LIKES_TEXT = "还没有人觉得有用";

    private final VenueCrowdReportLikeRepository likeRepository;
    private final VenueCrowdReportRepository crowdReportRepository;
    private final VenueRepository venueRepository;
    private final MessageService messageService;
    private final UserRepository userRepository;
    private final CrowdTrustService crowdTrustService;

    /**
     * 赞（幂等）：已赞 → 返回当前态（不重复发通知）；取消后再赞 → 恢复（不重发通知）。
     * 首次赞且非自赞 → 同事务触达上报者（合并未读被赞通知，不点名赞者）。
     */
    @Transactional
    public CrowdLikeResponse like(Long venueId, Long reportId) {
        Long likerId = UserContext.requireAuth();
        VenueCrowdReport report = requireLikeableReport(venueId, reportId);
        LocalDateTime now = LocalDateTime.now();
        int affected = likeRepository.like(reportId, likerId, now, now);
        int likeCount = likeCountOf(reportId);
        // 受影响行数 1 = 首次赞（唯一触发通知的机会）；2/0 = 恢复或重复赞（义务已履行/不适用）
        if (affected == 1 && !likerId.equals(report.getUserId())) {
            // 合并窗口 = 这条上报创建之后：此前的未读被赞通知属于更早的上报（前一夜），不能并进来
            messageService.createOrMergeUnread(report.getUserId(), MessageType.CROWD_REPORT_LIKED,
                    "收到热度点赞",
                    "您在「" + venueName(report.getVenueId()) + "」的今晚热度上报收到 " + likeCount
                            + " 个赞，感谢分享真实情况",
                    RELATED_TYPE_VENUE, report.getVenueId(), report.getCreatedAt());
        }
        return new CrowdLikeResponse(likeCount, true);
    }

    /**
     * 取消赞（幂等）：未赞过 → 返回当前态不报错。窗口外行同 like 一律拒绝（1020，
     * 与赞对称——过期后本无展示/按钮场景，无需放行「撤销过期赞」的旁路）。
     */
    @Transactional
    public CrowdLikeResponse unlike(Long venueId, Long reportId) {
        Long likerId = UserContext.requireAuth();
        requireLikeableReport(venueId, reportId);
        likeRepository.unlike(reportId, likerId, LocalDateTime.now());
        return new CrowdLikeResponse(likeCountOf(reportId), false);
    }

    /**
     * 谁觉得有用（2026-10-07，公开读）：观察者是上报者本人或管理员 ⇒ 完整名单；其余 ⇒ 分层汇总。
     * <p>
     * 不要求行在 6h 窗口内——名单是只读信息，历史页的「有用 N」同样可点开；但行必须存在且未被
     * 管理员删除（1019）。分层口径与行徽标同源（{@link CrowdTrustService#badgeFor}）。
     */
    @Transactional(readOnly = true)
    public CrowdLikersResponse likers(Long venueId, Long reportId) {
        VenueCrowdReport report = requireReport(venueId, reportId);
        Long viewerId = UserContext.getCurrentUserId();
        boolean full = viewerId != null
                && (viewerId.equals(report.getUserId()) || UserContext.getCurrentRole() == UserRole.ADMIN);
        String mode = full ? CrowdLikersResponse.MODE_FULL : CrowdLikersResponse.MODE_SUMMARY;

        List<VenueCrowdReportLike> likes =
                likeRepository.findByReportIdAndDeletedFalseOrderByUpdatedAtDesc(reportId);
        if (likes.isEmpty()) {
            return new CrowdLikersResponse(mode, 0, NO_LIKES_TEXT, List.of());
        }
        Set<Long> likerIds = likes.stream().map(VenueCrowdReportLike::getLikerId).collect(Collectors.toSet());
        Map<Long, Double> weights = crowdTrustService.weights(likerIds);
        Long ownerId = venueRepository.findById(report.getVenueId()).map(Venue::getClaimedBy).orElse(null);

        if (!full) {
            Map<String, Long> badgeCounts = likes.stream()
                    .collect(Collectors.groupingBy(
                            l -> CrowdTrustService.badgeFor(l.getLikerId(), weights, ownerId),
                            Collectors.counting()));
            return new CrowdLikersResponse(mode, likes.size(), summaryText(likes.size(), badgeCounts, true), List.of());
        }
        Map<Long, User> users = userRepository.findByIdInAndDeletedFalse(likerIds).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now();
        List<CrowdLikersResponse.Liker> likers = new ArrayList<>(likes.size());
        for (VenueCrowdReportLike like : likes) {
            User user = users.get(like.getLikerId());
            likers.add(new CrowdLikersResponse.Liker(
                    like.getLikerId(),
                    user != null && user.getNickname() != null && !user.getNickname().isBlank()
                            ? user.getNickname() : "匿名",
                    user != null && user.getAvatarUrl() != null && !user.getAvatarUrl().isBlank()
                            ? user.getAvatarUrl() : null,
                    CrowdTrustService.badgeFor(like.getLikerId(), weights, ownerId),
                    viewerId.equals(like.getLikerId()),
                    CrowdTimeText.ageText(like.getUpdatedAt(), now)));
        }
        return new CrowdLikersResponse(mode, likes.size(), summaryText(likes.size(), Map.of(), false), likers);
    }

    /**
     * 汇总文案（纯函数，单测直达）：「3 人觉得有用」；分层时追加「 · 其中 1 位资深、2 位普通」。
     * 徽标按 {@link #BADGE_ORDER} 排序，人数为 0 的徽标不出现。
     */
    public static String summaryText(int likeCount, Map<String, Long> badgeCounts, boolean tiered) {
        String head = likeCount + " 人觉得有用";
        if (!tiered) {
            return head;
        }
        Map<String, Long> ordered = new LinkedHashMap<>();
        for (String badge : BADGE_ORDER) {
            long n = badgeCounts.getOrDefault(badge, 0L);
            if (n > 0) {
                ordered.put(badge, n);
            }
        }
        if (ordered.isEmpty()) {
            return head;
        }
        String tiers = ordered.entrySet().stream()
                .map(e -> e.getValue() + " 位" + e.getKey())
                .collect(Collectors.joining("、"));
        return head + " · 其中 " + tiers;
    }

    /**
     * 批量按上报行聚合赞数（summary/history 行数据源）：一次 IN 防 N+1；
     * 未命中行不在结果（调用方默认 0）。赞数纯展示、永不进算法。
     */
    @Transactional(readOnly = true)
    public Map<Long, Long> likeCountsByReportIds(Collection<Long> reportIds) {
        if (reportIds == null || reportIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, Long> counts = new HashMap<>();
        for (Object[] row : likeRepository.countByReportIds(reportIds)) {
            counts.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return counts;
    }

    /**
     * 我赞过的上报行 ID 集（详情页热度卡 likedByMe 回填）；未登录/空入参 → 空集。
     */
    @Transactional(readOnly = true)
    public Set<Long> likedReportIds(Long likerId, Collection<Long> reportIds) {
        if (likerId == null || reportIds == null || reportIds.isEmpty()) {
            return Set.of();
        }
        return likeRepository.findByLikerIdAndReportIdInAndDeletedFalse(likerId, reportIds).stream()
                .map(VenueCrowdReportLike::getReportId)
                .collect(Collectors.toSet());
    }

    /** 行存在未删（1019）且归属门店一致（防串店，1019）——读名单与赞 / 取消赞共用 */
    private VenueCrowdReport requireReport(Long venueId, Long reportId) {
        VenueCrowdReport report = crowdReportRepository.findById(reportId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(1019, "上报记录不存在或已删除"));
        if (!report.getVenueId().equals(venueId)) {
            throw new BusinessException(1019, "上报记录不存在或已删除");
        }
        return report;
    }

    /** 点赞前校验：行存在未删（1019）、归属门店一致（防串店，1019）、6h 窗口内（1020） */
    private VenueCrowdReport requireLikeableReport(Long venueId, Long reportId) {
        VenueCrowdReport report = requireReport(venueId, reportId);
        LocalDateTime since = LocalDateTime.now().minusHours(CrowdPolicy.TONIGHT_WINDOW_HOURS);
        if (report.getCreatedAt() == null || report.getCreatedAt().isBefore(since)) {
            throw new BusinessException(1020, "该条热度已过 " + CrowdPolicy.TONIGHT_WINDOW_HOURS
                    + " 小时有效窗口，暂不可点赞");
        }
        return report;
    }

    /** 单条赞数（like/unlike 响应权威回读） */
    private int likeCountOf(Long reportId) {
        return (int) likeRepository.countByReportIdAndDeletedFalse(reportId);
    }

    /** 门店名（被赞通知正文；门店理论上被软删时兜底占位——行可见即店名可读） */
    private String venueName(Long venueId) {
        return venueRepository.findById(venueId)
                .map(Venue::getName)
                .filter(name -> name != null && !name.isBlank())
                .orElse("该门店");
    }
}
