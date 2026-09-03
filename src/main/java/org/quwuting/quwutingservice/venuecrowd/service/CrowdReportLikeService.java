package org.quwuting.quwutingservice.venuecrowd.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.message.enums.MessageType;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdLikeResponse;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReportLike;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportLikeRepository;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 今晚热度上报行级点赞「有用」（2026-09-03，docs/agents/27-venue-crowd-report.md「行级点赞」）。
 * <p>
 * 定位：人际认可层——任何用户（含本人，自赞放开但不自动点亮）对单条上报行随手一票
 * 「这条有用」，零成本、即时、人人可得；与确认后积分（系统认可）正交补全激励闭环。
 * <p>
 * 核心机制：
 * <ul>
 *   <li><b>防刷</b>：全量唯一 (liker_id, report_id) + 软删 toggle（对齐 qwt_favorites），
 *       每人每行至多 1 票、再点取消；toggle ON 幂等由
 *       {@code INSERT ... ON DUPLICATE KEY UPDATE} 唯一键冲突兜底并发，无应用层锁；</li>
 *   <li><b>被赞通知去重</b>：仅当 toggle 返回受影响行数 == 1（该对<b>首次赞</b>）且
 *       <b>非自赞</b>时发送 MessageType.CROWD_REPORT_LIKED——取消后再赞（恢复行）或
 *       重复赞不重发；判定全由 DB 派生，无额外标志列（YAGNI）；</li>
 *   <li><b>窗口锁定</b>：like/unlike 仅允许 6h 窗口内行（{@link #requireLikeableReport}
 *       校验 createdAt，业务码 1020）——过期信息不可用即不可赞，封死「赞远古行」刷法；
 *       历史页整页只读（赞数纯展示，前端不渲染按钮）；</li>
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

    private final VenueCrowdReportLikeRepository likeRepository;
    private final VenueCrowdReportRepository crowdReportRepository;
    private final VenueRepository venueRepository;
    private final MessageService messageService;

    /**
     * 赞（幂等）：已赞 → 返回当前态（不重复发通知）；取消后再赞 → 恢复（不重发通知）。
     * 首次赞且非自赞 → 同事务给上报者发 CROWD_REPORT_LIKED 站内信（不点名赞者）。
     */
    @Transactional
    public CrowdLikeResponse like(Long venueId, Long reportId) {
        Long likerId = UserContext.requireAuth();
        VenueCrowdReport report = requireLikeableReport(venueId, reportId);
        LocalDateTime now = LocalDateTime.now();
        int affected = likeRepository.like(reportId, likerId, now, now);
        // 受影响行数 1 = 首次赞（唯一触发通知的机会）；2/0 = 恢复或重复赞（义务已履行/不适用）
        if (affected == 1 && !likerId.equals(report.getUserId())) {
            messageService.create(report.getUserId(), MessageType.CROWD_REPORT_LIKED,
                    "收到热度点赞",
                    "你在「" + venueName(report.getVenueId()) + "」的今晚热度上报收到 1 个赞，感谢分享真实情况",
                    RELATED_TYPE_VENUE, report.getVenueId());
        }
        return new CrowdLikeResponse(likeCountOf(reportId), true);
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
            counts.put((Long) row[0], ((Number) row[1]).longValue());
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

    /** 点赞前校验：行存在未删（1019）、归属门店一致（防串店，1019）、6h 窗口内（1020） */
    private VenueCrowdReport requireLikeableReport(Long venueId, Long reportId) {
        VenueCrowdReport report = crowdReportRepository.findById(reportId)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(1019, "上报记录不存在或已删除"));
        if (!report.getVenueId().equals(venueId)) {
            throw new BusinessException(1019, "上报记录不存在或已删除");
        }
        LocalDateTime since = LocalDateTime.now().minusHours(CrowdReportService.CROWD_WINDOW_HOURS);
        if (report.getCreatedAt() == null || report.getCreatedAt().isBefore(since)) {
            throw new BusinessException(1020, "该条热度已过 6 小时有效窗口，暂不可点赞");
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
