package org.quwuting.quwutingservice.venuesync.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningBatchRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.request.ApplyDailyOpeningRequest;
import org.quwuting.quwutingservice.venue.dailyopening.dto.response.BatchApplyResult;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningConfidence;
import org.quwuting.quwutingservice.venue.dailyopening.enums.DailyOpeningStatus;
import org.quwuting.quwutingservice.venue.dailyopening.service.DailyOpeningService;
import org.quwuting.quwutingservice.venuesync.dto.request.UploadSyncReportRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportDetailResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportListItemResponse;
import org.quwuting.quwutingservice.venuesync.entity.VenueSyncReport;
import org.quwuting.quwutingservice.venuesync.repository.VenueSyncReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 门店同步报告服务（2026-08-31，Web 管理后台「门店同步」页数据源）。
 * <p>
 * 数据流：管线（CLI）跑完 → --upload-report 上报 → 本表存档 →
 * Web 后台读报告 → 确认写库（apply 复用 DailyOpeningService.applyBatch）。
 * <ul>
 *   <li>upload：幂等 upsert（同渠道同报告日覆盖）；</li>
 *   <li>list/detail：读报告展示（summary/items 为 JSON 文本，进出经 ObjectMapper）；</li>
 *   <li>apply：取报告条目中 venue 已匹配且 confidence ∈ {EXACT, ALIAS} 的项组装
 *       batch 提交（对齐管线 --apply 语义；CONTAINED/UNMATCHED 留人工复核），
 *       反转规则在 DailyOpeningService.applyBatch 内部执行。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueSyncReportService {

    private static final int MAX_LIST_LIMIT = 30;

    private final VenueSyncReportRepository reportRepository;
    private final DailyOpeningService dailyOpeningService;
    private final ObjectMapper objectMapper;

    /** 管线上报报告（幂等覆盖）。 */
    @Transactional
    public Long upload(UploadSyncReportRequest request) {
        String summaryJson = toJson(request.summary());
        String itemsJson = toJson(request.items());
        LocalDateTime now = LocalDateTime.now(); // 时区红线：Java 传值，禁 DB now()
        reportRepository.upsert(
                request.reportDate(), request.sourceId(),
                nvl(request.sourceLabel()), nvl(request.url()),
                summaryJson, itemsJson, now);
        log.info("[venue-sync] report uploaded: date={} source={} items={}",
                request.reportDate(), request.sourceId(), request.items().size());
        return null; // 幂等覆盖，无需回传 ID（前端按 date+source 查询）
    }

    /** 历史报告列表（倒序）。 */
    @Transactional(readOnly = true)
    public List<SyncReportListItemResponse> list(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_LIST_LIMIT));
        return reportRepository
                .findByDeletedFalseOrderByReportDateDescIdDesc(PageRequest.of(0, size))
                .stream()
                .map(r -> new SyncReportListItemResponse(
                        r.getId(), r.getReportDate(), r.getSourceId(),
                        r.getSourceLabel(), r.getCreatedAt(), fromJson(r.getSummary())))
                .toList();
    }

    /** 报告详情（统计 + 条目）。 */
    @Transactional(readOnly = true)
    public SyncReportDetailResponse detail(Long id) {
        VenueSyncReport report = requireReport(id);
        return new SyncReportDetailResponse(
                report.getId(), report.getReportDate(), report.getSourceId(),
                report.getSourceLabel(), report.getReportUrl(), report.getCreatedAt(),
                fromJson(report.getSummary()), fromJsonList(report.getItems()));
    }

    /**
     * 按报告确认写库：提交 EXACT/ALIAS 且已匹配平台门店的条目。
     * 返回 batch 结果（快照落库数/反转数/明细），与管线 --apply 一致。
     */
    @Transactional
    public BatchApplyResult apply(Long id) {
        VenueSyncReport report = requireReport(id);
        List<Map<String, Object>> items = fromJsonList(report.getItems());

        List<ApplyDailyOpeningRequest> applyItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            String confidenceStr = asString(item.get("confidence"));
            DailyOpeningConfidence confidence;
            try {
                confidence = DailyOpeningConfidence.valueOf(confidenceStr);
            } catch (Exception e) {
                continue; // 未知置信度跳过（不应出现）
            }
            // 对齐管线 --apply：仅 EXACT/ALIAS 自动提交
            if (confidence != DailyOpeningConfidence.EXACT
                    && confidence != DailyOpeningConfidence.ALIAS) {
                continue;
            }
            Map<?, ?> venue = asMap(item.get("venue"));
            if (venue == null || venue.get("venue_id") == null) {
                continue; // 未匹配到平台门店（UNMATCHED）
            }
            String statusStr = asString(item.get("status"));
            DailyOpeningStatus status;
            try {
                status = DailyOpeningStatus.valueOf(statusStr);
            } catch (Exception e) {
                continue;
            }
            applyItems.add(new ApplyDailyOpeningRequest(
                    ((Number) venue.get("venue_id")).longValue(),
                    report.getReportDate(), report.getSourceId(),
                    status, confidence));
        }

        if (applyItems.isEmpty()) {
            return new BatchApplyResult(0, 0, 0, 0, List.of());
        }
        log.info("[venue-sync] apply report id={} date={} items={}",
                id, report.getReportDate(), applyItems.size());
        return dailyOpeningService.applyBatch(new ApplyDailyOpeningBatchRequest(applyItems));
    }

    // ---- 辅助 ----

    private VenueSyncReport requireReport(Long id) {
        return reportRepository.findById(id)
                .filter(r -> !r.isDeleted())
                .orElseThrow(() -> new BusinessException(404, "报告不存在"));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new BusinessException(400, "报告数据序列化失败");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            log.warn("[venue-sync] summary parse failed: {}", e.getMessage());
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> fromJsonList(String json) {
        try {
            return objectMapper.readValue(json, List.class);
        } catch (Exception e) {
            log.warn("[venue-sync] items parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static String nvl(String s) {
        return s == null ? "" : s;
    }

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }
}
