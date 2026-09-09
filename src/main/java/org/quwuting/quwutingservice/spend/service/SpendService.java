package org.quwuting.quwutingservice.spend.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.spend.dto.SpendEntriesResponse;
import org.quwuting.quwutingservice.spend.dto.SpendEntryItem;
import org.quwuting.quwutingservice.spend.dto.SpendEntryResponse;
import org.quwuting.quwutingservice.spend.dto.SpendOverviewResponse;
import org.quwuting.quwutingservice.spend.dto.SpendSyncRequest;
import org.quwuting.quwutingservice.spend.dto.SpendSyncResponse;
import org.quwuting.quwutingservice.spend.entity.SpendEntryEntity;
import org.quwuting.quwutingservice.spend.enums.SpendCategory;
import org.quwuting.quwutingservice.spend.enums.SpendSource;
import org.quwuting.quwutingservice.spend.repository.SpendEntryRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

/**
 * 消费账本服务（V1 上云版，2026-09-09，docs/agents/44-spend-ledger.md §12）。
 * <p>
 * 职责边界：本服务是 qwt_spend_entries 的唯一写入口（sync 幂等 upsert）与聚合
 * 读出口（overview 一次往返）；门店关联由客户端在计时主链路完成（定位匹配 +
 * 可见可改），服务端只存快照不校验归属真实性（venueId 语义引用，无 FK——与全库
 * 约定一致）。隐私边界：接口全部 user-scoped，userId 恒取登录态，产品层永不
 * 对外分发单用户明细（人均消费是 V2 的匿名聚合独立设计）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpendService {

    /** 单次同步条目上限（客户端 FIFO 分批上报；防御异常超大批次） */
    private static final int SYNC_BATCH_LIMIT = 200;

    /** 增量拉取单页上限（单人账目量级下 500 足够，翻页走游标） */
    private static final int INCREMENTAL_PAGE_SIZE = 500;

    /** 门店 TOP 条数（44 号文档 §14：横条 ≤5） */
    private static final int VENUE_TOP_LIMIT = 5;

    /** 趋势月份数（近 6 个月含请求月） */
    private static final int TREND_MONTHS = 6;

    private static final int CODE_INVALID_ENTRY = 1030;
    private static final int CODE_INVALID_MONTH = 1031;

    private final SpendEntryRepository spendEntryRepository;

    // ── 同步（本地为源、云端为镜） ──────────────────────

    /**
     * 批量幂等同步：逐条校验（非法条目跳过计数，不整体回滚——客户端下次整批
     * 重放仍幂等，合法条目已收敛重放无副作用）；同 (userId, clientEntryId) 存在
     * 即整体更新（last-write-wins，客户端本地为最新事实源），不存在则插入。
     * 软删同步恢复同路径：deleted=false 的重放会把此前误删的行恢复。
     */
    @Transactional
    public SpendSyncResponse sync(Long userId, SpendSyncRequest request) {
        List<SpendEntryItem> items = request == null || request.entries() == null
                ? List.of()
                : request.entries();
        if (items.size() > SYNC_BATCH_LIMIT) {
            throw new BusinessException(CODE_INVALID_ENTRY,
                    "单次同步最多 " + SYNC_BATCH_LIMIT + " 条");
        }
        int accepted = 0;
        int rejected = 0;
        for (SpendEntryItem item : items) {
            if (!validate(item)) {
                rejected++;
                continue;
            }
            upsert(userId, item);
            accepted++;
        }
        return new SpendSyncResponse(accepted, rejected);
    }

    private boolean validate(SpendEntryItem item) {
        if (item == null
                || item.clientEntryId() == null || item.clientEntryId().isBlank()
                || item.clientEntryId().length() > 32
                || item.amount() == null || item.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        try {
            SpendCategory.valueOf(item.category());
            SpendSource.valueOf(item.source());
        } catch (IllegalArgumentException e) {
            return false;
        }
        return item.ts() > 0;
    }

    private void upsert(Long userId, SpendEntryItem item) {
        SpendEntryEntity entity = spendEntryRepository
                .findByUserIdAndClientEntryId(userId, item.clientEntryId())
                .orElseGet(() -> {
                    SpendEntryEntity fresh = new SpendEntryEntity();
                    fresh.setUserId(userId);
                    fresh.setClientEntryId(item.clientEntryId());
                    return fresh;
                });
        entity.setTs(toLocalDateTime(item.ts()));
        entity.setAmount(item.amount());
        entity.setCategory(SpendCategory.valueOf(item.category()));
        entity.setSource(SpendSource.valueOf(item.source()));
        entity.setSourceRefId(item.sourceRefId());
        entity.setVenueId(item.venueId());
        entity.setVenueName(item.venueName());
        entity.setDurationSeconds(item.durationSeconds());
        entity.setDeleted(Boolean.TRUE.equals(item.deleted()));
        spendEntryRepository.save(entity);
    }

    // ── 总览（统计页一次聚合） ──────────────────────────

    /**
     * 月度总览：summary + 固定 6 类占比（缺类补零）+ 门店 TOP5 + 未关联桶 +
     * 近 6 月趋势（无数据月补零）。month 格式 "yyyy-MM"（缺省 = 当前月）。
     */
    @Transactional(readOnly = true)
    public SpendOverviewResponse overview(Long userId, String month) {
        YearMonth target = parseMonth(month);
        LocalDateTime monthStart = target.atDay(1).atStartOfDay();
        LocalDateTime monthEnd = target.plusMonths(1).atDay(1).atStartOfDay();
        LocalDateTime trendStart = target.minusMonths(TREND_MONTHS - 1).atDay(1).atStartOfDay();

        var summaryRow = spendEntryRepository.sumByMonth(userId, monthStart, monthEnd);
        BigDecimal total = summaryRow.getTotal() == null ? BigDecimal.ZERO : summaryRow.getTotal();
        long sessionCount = summaryRow.getSessionCount();
        BigDecimal avgPerSession = sessionCount > 0
                ? total.divide(BigDecimal.valueOf(sessionCount), 2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        // 固定 6 类全量返回（枚举权威，前端零补零逻辑）
        var sliceRows = spendEntryRepository.sumByCategory(userId, monthStart, monthEnd);
        List<SpendOverviewResponse.CategorySlice> byCategory = new ArrayList<>();
        for (SpendCategory c : SpendCategory.values()) {
            BigDecimal sliceTotal = BigDecimal.ZERO;
            long sliceCount = 0;
            for (var row : sliceRows) {
                if (c.name().equals(row.getCategory())) {
                    sliceTotal = row.getTotal() == null ? BigDecimal.ZERO : row.getTotal();
                    sliceCount = row.getEntryCount();
                    break;
                }
            }
            byCategory.add(new SpendOverviewResponse.CategorySlice(c.name(), sliceTotal, sliceCount));
        }

        List<SpendOverviewResponse.VenueSlice> byVenueTop = new ArrayList<>();
        for (var row : spendEntryRepository.sumByVenueTop(userId, monthStart, monthEnd, VENUE_TOP_LIMIT)) {
            byVenueTop.add(new SpendOverviewResponse.VenueSlice(
                    row.getVenueId(), row.getVenueName(), row.getTotal()));
        }

        BigDecimal unlinkedTotal = spendEntryRepository.sumUnlinked(userId, monthStart, monthEnd);

        List<SpendOverviewResponse.MonthPoint> trendMonths =
                buildTrend(userId, trendStart, monthEnd, target);

        return new SpendOverviewResponse(
                new SpendOverviewResponse.Summary(total, summaryRow.getEntryCount(),
                        sessionCount, avgPerSession),
                byCategory,
                byVenueTop,
                unlinkedTotal == null ? BigDecimal.ZERO : unlinkedTotal,
                trendMonths);
    }

    /** 趋势补零：SQL 只返回有数据月，服务端按月序列补 0（前端零补零逻辑） */
    private List<SpendOverviewResponse.MonthPoint> buildTrend(Long userId,
                                                              LocalDateTime trendStart,
                                                              LocalDateTime trendEnd,
                                                              YearMonth target) {
        var rows = spendEntryRepository.sumByMonthTrend(userId, trendStart, trendEnd);
        List<SpendOverviewResponse.MonthPoint> points = new ArrayList<>();
        for (int i = TREND_MONTHS - 1; i >= 0; i--) {
            YearMonth m = target.minusMonths(i);
            String key = m.toString();
            BigDecimal monthTotal = BigDecimal.ZERO;
            for (var row : rows) {
                if (key.equals(row.getMonth())) {
                    monthTotal = row.getTotal() == null ? BigDecimal.ZERO : row.getTotal();
                    break;
                }
            }
            points.add(new SpendOverviewResponse.MonthPoint(key, monthTotal));
        }
        return points;
    }

    private YearMonth parseMonth(String month) {
        if (month == null || month.isBlank()) {
            return YearMonth.now();
        }
        try {
            return YearMonth.parse(month.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(CODE_INVALID_MONTH, "月份格式应为 yyyy-MM");
        }
    }

    // ── 增量拉取（跨设备/换机恢复） ─────────────────────

    /**
     * 游标增量：updated_at ≥ cursor（含软删行——客户端据 deleted 删本地副本）。
     * cursor 缺省 = 0（全量首拉）；nextCursor = 本批最大 updatedAt 毫秒，客户端
     * 持久化后下次续传。
     */
    @Transactional(readOnly = true)
    public SpendEntriesResponse entries(Long userId, Long cursor) {
        long cursorMillis = cursor == null || cursor < 0 ? 0L : cursor;
        LocalDateTime cursorTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(cursorMillis), ZoneId.systemDefault());
        List<SpendEntryEntity> rows = spendEntryRepository.findIncremental(
                userId, cursorTime, PageRequest.of(0, INCREMENTAL_PAGE_SIZE));
        List<SpendEntryResponse> items = new ArrayList<>();
        long maxUpdated = cursorMillis;
        for (SpendEntryEntity e : rows) {
            long updatedAtMillis = e.getUpdatedAt() == null
                    ? cursorMillis
                    : e.getUpdatedAt().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
            if (updatedAtMillis > maxUpdated) {
                maxUpdated = updatedAtMillis;
            }
            items.add(new SpendEntryResponse(
                    e.getClientEntryId(),
                    e.getTs() == null ? 0 : e.getTs().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),
                    e.getAmount(),
                    e.getCategory() == null ? null : e.getCategory().name(),
                    e.getSource() == null ? null : e.getSource().name(),
                    e.getSourceRefId(),
                    e.getVenueId(),
                    e.getVenueName(),
                    e.getDurationSeconds(),
                    e.isDeleted(),
                    updatedAtMillis));
        }
        return new SpendEntriesResponse(items, maxUpdated);
    }

    private LocalDateTime toLocalDateTime(long epochMillis) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(epochMillis), ZoneId.systemDefault());
    }
}
