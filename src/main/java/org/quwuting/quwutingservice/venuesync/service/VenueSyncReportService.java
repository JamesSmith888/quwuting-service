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
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.entity.VenueStatusLog;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venue.repository.VenueStatusLogRepository;
import org.quwuting.quwutingservice.venuesync.dto.request.UploadSyncReportRequest;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportDetailResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.SyncReportListItemResponse;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueReversalRecord;
import org.quwuting.quwutingservice.venuesync.dto.response.VenueStatusInfo;
import org.quwuting.quwutingservice.venuesync.entity.VenueSyncReport;
import org.quwuting.quwutingservice.venuesync.repository.VenueSyncReportRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 门店同步报告服务（2026-08-31，Web 管理后台「门店同步」页数据源）。
 * <p>
 * 数据流：管线（CLI）跑完 → --upload-report 上报 → 本表存档 →
 * Web 后台读报告 → 确认写库（apply 复用 DailyOpeningService.applyBatch）。
 * <b>2026-09-01 快照机制退出</b>：写库 = 仅状态反转（不再落每日快照）；
 * 「更新记录」改读 VenueStatusLog（系统反转审计日志，changedBy IS NULL）。
 * <ul>
 *   <li>upload：幂等 upsert（同渠道同报告日覆盖）；</li>
 *   <li>list/detail：读报告展示（summary/items 为 JSON 文本，进出经 ObjectMapper）；
 *       detail 为条目注入 apply_state（would_reverse，后端权威计算）；</li>
 *   <li>apply / applySelected：取报告条目中 venue 已匹配且 confidence ∈ {EXACT, ALIAS}
 *       的项组装 batch 提交（对齐管线 --apply 语义；CONTAINED/UNMATCHED 留人工复核），
 *       反转规则在 DailyOpeningService.applyBatch 内部执行；</li>
 *   <li>listReversals：更新记录 = 系统状态反转日志（changedBy IS NULL 且
 *       CEASED/SUSPENDED → OPEN）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VenueSyncReportService {

    private static final int MAX_LIST_LIMIT = 30;
    private static final int MAX_REVERSAL_LIMIT = 50;

    private final VenueSyncReportRepository reportRepository;
    private final DailyOpeningService dailyOpeningService;
    private final VenueRepository venueRepository;
    private final VenueStatusLogRepository venueStatusLogRepository;
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

    /** 报告详情（统计 + 条目）。条目附带后端权威计算的 apply_state（「可直接更新」视图按此过滤）。 */
    @Transactional(readOnly = true)
    public SyncReportDetailResponse detail(Long id) {
        VenueSyncReport report = requireReport(id);
        List<Map<String, Object>> items = fromJsonList(report.getItems());
        enrichApplyState(items);
        return new SyncReportDetailResponse(
                report.getId(), report.getReportDate(), report.getSourceId(),
                report.getSourceLabel(), report.getReportUrl(), report.getCreatedAt(),
                fromJson(report.getSummary()), items);
    }

    /**
     * 为每条已匹配条目注入 apply_state.would_reverse（后端权威计算，前端「可直接更新」
     * 视图按此过滤）。快照机制 2026-09-01 退出后，写库 = 仅状态反转，需要更新的唯一
     * 判据 = 本次写库会触发反转：资讯 OPEN + 平台<b>当前</b>状态 CEASED/SUSPENDED
     * （用实时值而非报告条目 venue.status 快照，避免误导）。
     * 未匹配条目（venue 为空）无 apply_state（不在写库提交集内）。
     */
    private void enrichApplyState(List<Map<String, Object>> items) {
        if (items.isEmpty()) {
            return;
        }
        Set<Long> matchedVenueIds = items.stream()
                .map(item -> asMap(item.get("venue")))
                .filter(v -> v != null && v.get("venue_id") != null)
                .map(v -> ((Number) v.get("venue_id")).longValue())
                .collect(Collectors.toSet());
        if (matchedVenueIds.isEmpty()) {
            return;
        }

        // 平台当前状态（反转判定用实时值，非报告快照）
        Map<Long, Venue> venuesById = venueRepository.findByIdInAndDeletedFalse(new ArrayList<>(matchedVenueIds))
                .stream()
                .collect(Collectors.toMap(Venue::getId, v -> v, (a, b) -> a));

        for (Map<String, Object> item : items) {
            Map<?, ?> venue = asMap(item.get("venue"));
            if (venue == null || venue.get("venue_id") == null) {
                continue;
            }
            long venueId = ((Number) venue.get("venue_id")).longValue();
            Venue current = venuesById.get(venueId);
            boolean wouldReverse = DailyOpeningStatus.OPEN.name().equals(asString(item.get("status")))
                    && current != null
                    && (current.getStatus() == VenueStatus.CEASED || current.getStatus() == VenueStatus.SUSPENDED);
            Map<String, Object> state = new LinkedHashMap<>();
            state.put("would_reverse", wouldReverse);
            item.put("apply_state", state);
        }
    }

    /**
     * 更新记录（2026-09-01，快照机制退出后改读状态审计日志）：系统自动反转门店的
     * 详情记录——VenueStatusLog 中 changedBy IS NULL（系统/Agent 来源，人工编辑=userId）
     * 且 CEASED/SUSPENDED → OPEN 的变更，按时间倒序。带平台门店名/城市（门店已删不返回）。
     */
    @Transactional(readOnly = true)
    public List<VenueReversalRecord> listReversals(int limit) {
        int size = Math.max(1, Math.min(limit, MAX_REVERSAL_LIMIT));
        List<VenueStatusLog> logs = venueStatusLogRepository
                .findByChangedByIsNullAndToStatusAndFromStatusInOrderByCreatedAtDesc(
                        VenueStatus.OPEN, List.of(VenueStatus.CEASED, VenueStatus.SUSPENDED),
                        PageRequest.of(0, size));
        if (logs.isEmpty()) {
            return List.of();
        }
        List<Long> venueIds = logs.stream().map(VenueStatusLog::getVenueId).toList();
        Map<Long, Venue> venuesById = venueRepository.findByIdInAndDeletedFalse(venueIds).stream()
                .collect(Collectors.toMap(Venue::getId, v -> v, (a, b) -> a));
        return logs.stream()
                .map(l -> {
                    Venue v = venuesById.get(l.getVenueId());
                    return new VenueReversalRecord(
                            l.getVenueId(),
                            v == null ? null : v.getName(),
                            v == null ? null : v.getCity(),
                            l.getFromStatus() == null ? null : l.getFromStatus().name(),
                            l.getToStatus().name(),
                            l.getCreatedAt(),
                            l.getChangeSource());
                })
                .toList();
    }

    /**
     * 按报告确认写库：提交 EXACT/ALIAS 且已匹配平台门店的条目。
     * 返回 batch 结果（反转数/明细），与管线 --apply 一致（2026-09-01 快照机制退出，仅反转）。
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
                    status, confidence, false, "ADMIN"));
        }

        if (applyItems.isEmpty()) {
            return new BatchApplyResult(0, 0, 0, List.of());
        }
        log.info("[venue-sync] apply report id={} date={} items={}",
                id, report.getReportDate(), applyItems.size());
        return dailyOpeningService.applyBatch(new ApplyDailyOpeningBatchRequest(applyItems));
    }

    /**
     * 单条写库（2026-08-31，条目级「写库」按钮）：仅对指定条目（venueId + sourceName
     * 定位）执行与批量 apply 相同的语义——状态反转（2026-09-01 快照机制退出后仅反转：
     * 仅 OPEN 且来源可信才反转停业→营业，单向，不会把营业中的门店标停业）。
     */
    @Transactional
    public BatchApplyResult applyItem(Long reportId, Long venueId, String sourceName) {
        VenueSyncReport report = requireReport(reportId);
        List<Map<String, Object>> items = fromJsonList(report.getItems());

        Map<String, Object> target = null;
        for (Map<String, Object> item : items) {
            Map<?, ?> venue = asMap(item.get("venue"));
            if (venue == null || venue.get("venue_id") == null) {
                continue;
            }
            if (((Number) venue.get("venue_id")).longValue() == venueId
                    && sourceName.equals(asString(item.get("source_name")))) {
                target = item;
                break;
            }
        }
        if (target == null) {
            throw new BusinessException(1001, "报告中不存在该条目（门店已变更或来源店名不同）");
        }

        String confidenceStr = asString(target.get("confidence"));
        DailyOpeningConfidence confidence;
        try {
            confidence = DailyOpeningConfidence.valueOf(confidenceStr);
        } catch (Exception e) {
            throw new BusinessException(1001, "该条目置信度不可写库");
        }
        // 已匹配平台门店即可单条写库（2026-09-01 放宽：CONTAINED/FUZZY 也允许手动放行）。
        // 反转语义由 applyBatch 内部保证：仅 OPEN + 来源可信触发反转
        //（EXACT/ALIAS 自动；CONTAINED/FUZZY 靠下方 forceReversal 人工确认放行）。
        // 未匹配（UNMATCHED）条目 venue 为 null，在定位阶段已被跳过，到不了这里。

        String statusStr = asString(target.get("status"));
        DailyOpeningStatus status;
        try {
            status = DailyOpeningStatus.valueOf(statusStr);
        } catch (Exception e) {
            throw new BusinessException(1001, "该条目营业状态异常，无法写库");
        }

        // 单条写库 = 管理员人工确认放行：forceReversal=true，低置信（CONTAINED/FUZZY）
        // 也允许反转（若资讯 OPEN 且平台 CEASED/SUSPENDED）。
        ApplyDailyOpeningRequest item = new ApplyDailyOpeningRequest(
                venueId, report.getReportDate(), report.getSourceId(), status, confidence, true, "ADMIN");
        log.info("[venue-sync] apply-item report id={} venueId={} source={} status={} conf={} (admin confirmed)",
                reportId, venueId, sourceName, status, confidence);
        return dailyOpeningService.applyBatch(new ApplyDailyOpeningBatchRequest(List.of(item)));
    }

    /**
     * 「可直接更新」tab 专属批量写库（2026-09-01）：仅提交 venue 命中且
     * confidence ∈ {EXACT, ALIAS} 的选中条目，语义与 apply（整报告）完全一致
     * （forceReversal=false；CONTAINED/FUZZY 低置信仍走 apply-item 单条人工放行）。
     * 前端调用方 = 「可直接更新」视图（已按 would_reverse 过滤），重复/未匹配
     * venue id 自动跳过。
     */
    @Transactional
    public BatchApplyResult applySelected(Long id, List<Long> venueIds) {
        VenueSyncReport report = requireReport(id);
        Set<Long> selected = venueIds == null ? Set.of() : new HashSet<>(venueIds);
        if (selected.isEmpty()) {
            throw new BusinessException(1001, "请选择要写库的门店");
        }
        List<Map<String, Object>> items = fromJsonList(report.getItems());

        List<ApplyDailyOpeningRequest> applyItems = new ArrayList<>();
        for (Map<String, Object> item : items) {
            Map<?, ?> venue = asMap(item.get("venue"));
            if (venue == null || venue.get("venue_id") == null) {
                continue; // 未匹配（UNMATCHED）
            }
            long venueId = ((Number) venue.get("venue_id")).longValue();
            if (!selected.contains(venueId)) {
                continue;
            }
            // 对齐批量 apply 提交集：仅 EXACT/ALIAS（低置信须单条人工放行）
            DailyOpeningConfidence confidence;
            try {
                confidence = DailyOpeningConfidence.valueOf(asString(item.get("confidence")));
            } catch (Exception e) {
                continue;
            }
            if (confidence != DailyOpeningConfidence.EXACT
                    && confidence != DailyOpeningConfidence.ALIAS) {
                continue;
            }
            DailyOpeningStatus status;
            try {
                status = DailyOpeningStatus.valueOf(asString(item.get("status")));
            } catch (Exception e) {
                continue;
            }
            applyItems.add(new ApplyDailyOpeningRequest(
                    venueId, report.getReportDate(), report.getSourceId(),
                    status, confidence, false, "ADMIN"));
        }

        if (applyItems.isEmpty()) {
            throw new BusinessException(1001, "所选条目均不可批量写库（仅精确/别名且已匹配平台门店的条目可写）");
        }
        log.info("[venue-sync] apply-selected report id={} date={} selected={} items={}",
                id, report.getReportDate(), selected.size(), applyItems.size());
        return dailyOpeningService.applyBatch(new ApplyDailyOpeningBatchRequest(applyItems));
    }

    /**
     * 门店实时状态批量查询（2026-09-01）：条目对比条需要平台<b>当前</b>状态而非报告快照。
     * 单次往返（findByIdInAndDeletedFalse），缺失/已删门店不返回（前端回退快照值）。
     */
    @Transactional(readOnly = true)
    public Map<Long, VenueStatusInfo> batchVenueStatus(List<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        List<Venue> venues = venueRepository.findByIdInAndDeletedFalse(venueIds);
        Map<Long, VenueStatusInfo> result = new LinkedHashMap<>();
        for (Venue v : venues) {
            result.put(v.getId(), new VenueStatusInfo(
                    v.getStatus().name(), v.getStatus().getDisplayName()));
        }
        return result;
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
