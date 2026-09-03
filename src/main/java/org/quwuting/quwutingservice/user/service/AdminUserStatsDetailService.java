package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DancerFavorite;
import org.quwuting.quwutingservice.dancer.entity.DancerRecognition;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.quwuting.quwutingservice.dancer.enums.DemandStatus;
import org.quwuting.quwutingservice.dancer.repository.DancerFavoriteRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRecognitionRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.dancershare.entity.DancerShare;
import org.quwuting.quwutingservice.dancershare.repository.DancerShareRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.points.entity.DailyCheckin;
import org.quwuting.quwutingservice.points.entity.PointsTransaction;
import org.quwuting.quwutingservice.points.enums.PointsSourceType;
import org.quwuting.quwutingservice.points.repository.DailyCheckinRepository;
import org.quwuting.quwutingservice.points.repository.PointsTransactionRepository;
import org.quwuting.quwutingservice.user.dto.response.AdminUserStatsRow;
import org.quwuting.quwutingservice.user.enums.AdminUserStatsType;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venueclaim.entity.VenueClaim;
import org.quwuting.quwutingservice.venueclaim.enums.ClaimStatus;
import org.quwuting.quwutingservice.venueclaim.repository.VenueClaimRepository;
import org.quwuting.quwutingservice.venuefeedback.entity.VenueFeedback;
import org.quwuting.quwutingservice.venuefeedback.enums.FeedbackType;
import org.quwuting.quwutingservice.venuefeedback.enums.ReportStatus;
import org.quwuting.quwutingservice.venuefeedback.repository.VenueFeedbackRepository;
import org.quwuting.quwutingservice.venueshare.entity.VenueShare;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.quwuting.quwutingservice.venueshare.repository.VenueShareRepository;
import org.quwuting.quwutingservice.venuestatusreport.entity.VenueStatusReport;
import org.quwuting.quwutingservice.venuestatusreport.enums.AdminAction;
import org.quwuting.quwutingservice.venuestatusreport.enums.ReportType;
import org.quwuting.quwutingservice.venuestatusreport.repository.StatusReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 管理端用户统计明细服务（2026-08-28，GET /admin/users/{id}/stats-detail，docs/agents/23；
 * 仅 ADMIN）——用户详情页<b>每条统计数据可点击下钻</b>：查看该统计的每条详细列表。
 * <p>
 * 维度 = {@link AdminUserStatsType} 八类：积分流水（POINTS，可选 mode=EARN/GIFT 过滤；
 * 上报采纳 = REPORT_REWARD = 积分流水中 source_type ∈ 采纳来源）/ 打卡（CHECKIN）/
 * 认可舞伴（RECOGNITION）/ 认领（CLAIM，可选 status 过滤）/ 分享（SHARE，门店+舞伴
 * 合并）/ 收藏舞伴（FAVORITE）/ 需求单（DEMAND，可选 status 过滤）/ 上报（REPORT，
 * 信息反馈 + 暂停营业报告合并，可选 status 过滤）。
 * <p>
 * 行 = 统一 {@link AdminUserStatsRow}（title/subtitle/time/badgeText/badgeCls），前端
 * 零分支渲染；徽标配色镜像前端 buildDemandStatusBadge / CLAIM_STATUS_LABELS 字典
 * （badge--warning/success/muted 全局类）。openId 绝不下发。
 * <p>
 * 性能：单用户低频查询，各类型一次查询；名称（舞伴/门店）批量 IN 取回，规避 N+1。
 */
@Service
@RequiredArgsConstructor
public class AdminUserStatsDetailService {

    private final UserRepository userRepository;
    private final PointsTransactionRepository transactionRepository;
    private final DailyCheckinRepository checkinRepository;
    private final DancerRecognitionRepository recognitionRepository;
    private final DancerFavoriteRepository favoriteRepository;
    private final VenueClaimRepository claimRepository;
    private final VenueShareRepository venueShareRepository;
    private final DancerShareRepository dancerShareRepository;
    private final DemandRecordRepository demandRecordRepository;
    private final VenueFeedbackRepository feedbackRepository;
    private final StatusReportRepository statusReportRepository;
    private final DancerRepository dancerRepository;
    private final VenueRepository venueRepository;

    /** 无昵称舞伴占位（与舞伴列表 NICKNAME_FALLBACK 同口径） */
    private static final String DANCER_FALLBACK = "舞伴";

    /**
     * 用户统计明细（GET /admin/users/{id}/stats-detail，仅 ADMIN）：按 type 分派查询
     * 该用户该统计的<b>每条详细列表</b>，时间倒序。status = 可选状态过滤
     * （CLAIM/DEMAND/REPORT）；mode = POINTS 的收支方向（ALL 默认/EARN/GIFT）。
     * 用户不存在/已软删 → 1004。
     */
    @Transactional(readOnly = true)
    public List<AdminUserStatsRow> detail(Long userId, AdminUserStatsType type,
                                          String status, String mode) {
        userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
        return switch (type) {
            case POINTS -> pointsRows(userId, mode);
            case REPORT_REWARD -> reportRewardRows(userId);
            case CHECKIN -> checkinRows(userId);
            case RECOGNITION -> recognitionRows(userId);
            case CLAIM -> claimRows(userId, status);
            case SHARE -> shareRows(userId);
            case FAVORITE -> favoriteRows(userId);
            case DEMAND -> demandRows(userId, status);
            case REPORT -> reportRows(userId, status);
        };
    }

    // ── 积分流水（全部 / EARN 挣取 / GIFT 消费） ────────────────────────────────

    private List<AdminUserStatsRow> pointsRows(Long userId, String mode) {
        List<PointsTransaction> txs = transactionRepository.findAllByUserIdForAdminDetail(userId);
        return txs.stream()
                .filter(tx -> mode == null || "ALL".equals(mode)
                        || ("EARN".equals(mode) && tx.getDelta() > 0)
                        || ("GIFT".equals(mode) && tx.getDelta() < 0))
                .map(tx -> new AdminUserStatsRow(
                        tx.getId(),
                        sourceTypeDisplay(tx.getSourceType()),
                        (tx.getDelta() > 0 ? "+" : "") + tx.getDelta() + " 积分 · 余额 " + tx.getBalanceAfter(),
                        tx.getCreatedAt(), "", ""))
                .toList();
    }

    /** 上报采纳流水（贡献档案「上报采纳」维度：积分流水中采纳来源） */
    private List<AdminUserStatsRow> reportRewardRows(Long userId) {
        return transactionRepository.findAllByUserIdForAdminDetail(userId).stream()
                .filter(tx -> tx.getSourceType() == PointsSourceType.FEEDBACK_REWARD
                        || tx.getSourceType() == PointsSourceType.STATUS_REPORT_REWARD)
                .map(tx -> new AdminUserStatsRow(
                        tx.getId(),
                        sourceTypeDisplay(tx.getSourceType()),
                        "+" + tx.getDelta() + " 积分 · 余额 " + tx.getBalanceAfter(),
                        tx.getCreatedAt(), "", ""))
                .toList();
    }

    // ── 打卡（日期倒序） ──────────────────────────────────────────────────────

    private List<AdminUserStatsRow> checkinRows(Long userId) {
        return checkinRepository.findByUserIdForAdminDetail(userId).stream()
                .map(c -> new AdminUserStatsRow(
                        c.getId(), "每日打卡",
                        c.getCheckinDate().toString(),
                        c.getCreatedAt(), "", ""))
                .toList();
    }

    // ── 认可舞伴（未软删，时间倒序；舞伴名批量取回） ───────────────────────────

    private List<AdminUserStatsRow> recognitionRows(Long userId) {
        List<DancerRecognition> recs = recognitionRepository.findByUserIdForAdminDetail(userId);
        if (recs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = dancerNames(recs.stream()
                .map(DancerRecognition::getDancerId).toList());
        return recs.stream()
                .map(r -> new AdminUserStatsRow(
                        r.getId(),
                        "认可「" + names.getOrDefault(r.getDancerId(), DANCER_FALLBACK) + "」",
                        "每日认可",
                        r.getCreatedAt(), "", ""))
                .toList();
    }

    // ── 认领（时间倒序；status 可选过滤；门店名批量取回） ──────────────────────

    private List<AdminUserStatsRow> claimRows(Long userId, String status) {
        ClaimStatus st = parseOrNull(ClaimStatus.class, status);
        List<VenueClaim> claims = st != null
                ? claimRepository.findByUserIdAndStatusOrderByCreatedAtDesc(userId, st)
                : claimRepository.findByUserIdOrderByCreatedAtDesc(userId);
        if (claims.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = venueNames(claims.stream()
                .map(VenueClaim::getVenueId).toList());
        return claims.stream()
                .map(c -> {
                    ClaimStatus cs = c.getStatus() == null ? ClaimStatus.PENDING : c.getStatus();
                    return new AdminUserStatsRow(
                            c.getId(),
                            names.getOrDefault(c.getVenueId(), "门店"),
                            "认领申请",
                            c.getCreatedAt(), cs.getDisplayName(), claimBadgeCls(cs));
                })
                .toList();
    }

    // ── 分享（门店 + 舞伴合并，仅 SHARE 事件，时间倒序） ───────────────────────

    private List<AdminUserStatsRow> shareRows(Long userId) {
        List<AdminUserStatsRow> rows = new ArrayList<>();
        List<VenueShare> venueShares = venueShareRepository
                .findByUserIdAndEventTypeOrderByCreatedAtDesc(userId, ShareEventType.SHARE);
        if (!venueShares.isEmpty()) {
            Map<Long, String> names = venueNames(venueShares.stream()
                    .map(VenueShare::getVenueId).toList());
            venueShares.forEach(s -> rows.add(new AdminUserStatsRow(
                    s.getId(), "分享门店「" + names.getOrDefault(s.getVenueId(), "门店") + "」",
                    channelText(s.getChannel()), s.getCreatedAt(), "", "")));
        }
        List<DancerShare> dancerShares = dancerShareRepository
                .findByUserIdAndEventTypeOrderByCreatedAtDesc(userId, ShareEventType.SHARE);
        if (!dancerShares.isEmpty()) {
            Map<Long, String> names = dancerNames(dancerShares.stream()
                    .map(DancerShare::getDancerId).toList());
            dancerShares.forEach(s -> rows.add(new AdminUserStatsRow(
                    s.getId(), "分享舞伴「" + names.getOrDefault(s.getDancerId(), DANCER_FALLBACK) + "」",
                    channelText(s.getChannel()), s.getCreatedAt(), "", "")));
        }
        rows.sort(Comparator.comparing(AdminUserStatsRow::time,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // ── 收藏舞伴（未软删，时间倒序；舞伴名批量取回） ───────────────────────────

    private List<AdminUserStatsRow> favoriteRows(Long userId) {
        List<DancerFavorite> favs = favoriteRepository.findByUserIdForAdminDetail(userId);
        if (favs.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = dancerNames(favs.stream()
                .map(DancerFavorite::getDancerId).toList());
        return favs.stream()
                .map(f -> new AdminUserStatsRow(
                        f.getId(),
                        "收藏「" + names.getOrDefault(f.getDancerId(), DANCER_FALLBACK) + "」",
                        "舞伴收藏",
                        f.getCreatedAt(), "", ""))
                .toList();
    }

    // ── 需求单（id 倒序；status 可选过滤——存量 NULL 归 APPROVED；舞伴名批量取回） ─

    private List<AdminUserStatsRow> demandRows(Long userId, String status) {
        List<DemandRecord> demands = demandRecordRepository
                .findByUserIdForAdminDetail(userId, status);
        if (demands.isEmpty()) {
            return List.of();
        }
        Map<Long, String> names = dancerNames(demands.stream()
                .map(DemandRecord::getDancerId).toList());
        return demands.stream()
                .map(d -> {
                    DemandStatus ds = DemandStatus.parseOrNull(d.getStatus());
                    boolean fulfilled = d.getFulfilledAt() != null;
                    String badgeText = ds != null ? ds.label() : "已发放";
                    String badgeCls = ds != null ? demandBadgeCls(ds) : "badge--success";
                    String sub = fulfilled ? "已确认履约" : "邀约";
                    return new AdminUserStatsRow(
                            d.getId(),
                            "邀约「" + names.getOrDefault(d.getDancerId(), DANCER_FALLBACK) + "」",
                            sub,
                            d.getCreatedAt(), badgeText, badgeCls);
                })
                .toList();
    }

    // ── 上报（信息反馈 + 暂停营业报告合并，时间倒序；status=PENDING 跨表匹配） ───

    private List<AdminUserStatsRow> reportRows(Long userId, String status) {
        List<AdminUserStatsRow> rows = new ArrayList<>();
        ReportStatus rs = status == null ? null : parseOrNull(ReportStatus.class, status);
        List<VenueFeedback> feedbacks = feedbackRepository.findByUserIdForAdminDetail(userId, rs);
        if (!feedbacks.isEmpty()) {
            Map<Long, String> names = venueNames(feedbacks.stream()
                    .map(VenueFeedback::getVenueId).toList());
            feedbacks.forEach(f -> {
                ReportStatus fs = f.getStatus() == null ? ReportStatus.PENDING : f.getStatus();
                rows.add(new AdminUserStatsRow(
                        f.getId(),
                        "上报「" + names.getOrDefault(f.getVenueId(), "门店") + "」",
                        feedbackTypeText(f.getType()),
                        f.getCreatedAt(), fs.getDisplayName(), reportBadgeCls(fs)));
            });
        }
        // 暂停营业报告：status=PENDING 时只取未处置（admin_action IS NULL）
        List<VenueStatusReport> reports = statusReportRepository.findByUserIdForAdminDetail(userId);
        if (!reports.isEmpty()) {
            Map<Long, String> names = venueNames(reports.stream()
                    .map(VenueStatusReport::getVenueId).toList());
            reports.stream()
                    .filter(r -> status == null
                            || ("PENDING".equals(status) && r.getAdminAction() == null))
                    .forEach(r -> {
                        AdminAction action = r.getAdminAction();
                        boolean pending = action == null;
                        rows.add(new AdminUserStatsRow(
                                r.getId(),
                                "报告「" + names.getOrDefault(r.getVenueId(), "门店") + "」",
                                reportTypeText(r.getType()),
                                r.getCreatedAt(),
                                pending ? "待处理" : adminActionText(action),
                                pending ? "badge--warning" : reportBadgeCls(action)));
                    });
        }
        rows.sort(Comparator.comparing(AdminUserStatsRow::time,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return rows;
    }

    // ── 名称批量取回（规避 N+1） ──────────────────────────────────────────────

    private Map<Long, String> dancerNames(List<Long> ids) {
        return dancerRepository.findByIds(ids).stream()
                .collect(Collectors.toMap(Dancer::getId, Dancer::getNickname,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    private Map<Long, String> venueNames(List<Long> ids) {
        return venueRepository.findByIdInAndDeletedFalse(ids).stream()
                .collect(Collectors.toMap(Venue::getId, Venue::getName,
                        (a, b) -> a, java.util.LinkedHashMap::new));
    }

    // ── 文案与徽标（服务端权威，镜像前端字典，见类注释） ────────────────────────

    /** 积分来源中文（与 PointsService.sourceTypeDisplay 同源，镜像防跨模块耦合） */
    private static String sourceTypeDisplay(PointsSourceType type) {
        return switch (type) {
            case DAILY_CHECK_IN -> "每日打卡";
            case FEEDBACK_REWARD -> "上报被采纳";
            case STATUS_REPORT_REWARD -> "暂停报被采纳";
            case ADMIN_ADJUST -> "平台调整";
            case GIFT -> "赠送";
            case UNLOCK -> "解锁";
            case UNLOCK_REFUND -> "解锁返还";
            case APP_FEEDBACK_REWARD -> "意见被采纳";
            case CROWD_CONFIRMED -> "热度被确认";
        };
    }

    /** 分享渠道中文（channel：BUTTON/MENU/TIMELINE；空 = 未知渠道） */
    private static String channelText(String channel) {
        if (channel == null) return "";
        return switch (channel) {
            case "BUTTON" -> "按钮分享";
            case "MENU" -> "菜单分享";
            case "TIMELINE" -> "朋友圈分享";
            default -> "分享";
        };
    }

    /** 信息反馈类型中文（FeedbackType displayName；空 = 其他） */
    private static String feedbackTypeText(FeedbackType type) {
        return type == null ? "其他问题" : type.getDisplayName();
    }

    /** 暂停营业报告类型中文（ReportType displayName；空 = 暂停营业） */
    private static String reportTypeText(ReportType type) {
        return type == null ? "暂停营业" : type.getDisplayName();
    }

    /** 状态报告处置中文（AdminAction；null 已在上游按待处理处理） */
    private static String adminActionText(AdminAction action) {
        return switch (action) {
            case ADOPTED -> "已采纳";
            case KEPT -> "已保留";
            case REMOVED -> "已移除";
        };
    }

    /** 认领状态徽标配色（镜像前端 CLAIM_STATUS_LABELS 语义） */
    private static String claimBadgeCls(ClaimStatus status) {
        return switch (status) {
            case PENDING -> "badge--warning";
            case APPROVED -> "badge--success";
            case REJECTED, WITHDRAWN -> "badge--muted";
        };
    }

    /** 需求单状态徽标配色（镜像前端 buildDemandStatusBadge） */
    private static String demandBadgeCls(DemandStatus status) {
        return switch (status) {
            case PENDING -> "badge--warning";
            case APPROVED, AUTO_RELEASED -> "badge--success";
            case REJECTED, EXPIRED -> "badge--muted";
        };
    }

    /** 信息反馈状态徽标配色（PENDING 待处理警示 / ADOPTED 采纳成功 / 其余中性） */
    private static String reportBadgeCls(ReportStatus status) {
        return switch (status) {
            case PENDING -> "badge--warning";
            case ADOPTED -> "badge--success";
            case ADOPTED_NO_REWARD, RESOLVED, DISMISSED -> "badge--muted";
        };
    }

    /** 状态报告处置徽标配色（ADOPTED 采纳成功 / 其余中性） */
    private static String reportBadgeCls(AdminAction action) {
        return switch (action) {
            case ADOPTED -> "badge--success";
            case KEPT, REMOVED -> "badge--muted";
        };
    }

    /** 枚举安全解析（非法/空 → null；历史脏数据防御，同 DemandStatus.parseOrNull 模式） */
    private static <E extends Enum<E>> E parseOrNull(Class<E> type, String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(type, code);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
