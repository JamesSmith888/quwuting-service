package org.quwuting.quwutingservice.spend.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageStatsResponse;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageStatsResponse.CategorySlice;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageStatsResponse.DailyPoint;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageStatsResponse.Summary;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageStatsResponse.VenueSlice;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUsageUserItem;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUserEntriesResponse;
import org.quwuting.quwutingservice.spend.dto.response.AdminSpendUserEntriesResponse.EntryItem;
import org.quwuting.quwutingservice.spend.repository.SpendStatsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 管理端「计时器 & 计时账本使用情况」统计服务（2026-09-14，
 * docs/agents/35-dashboard-stats.md；仅 ADMIN 消费）。
 * <p>
 * 定位：计时器（2026-09-08 上线）与计时账本（V16 上云）两项新功能的使用盘子——
 * 谁在用（记账用户/计时用户）、用多少（条目/场次）、怎么用（计时结算 vs 手动
 * 补记、分类、门店）。数据源只有 {@code qwt_spend_entries} 一张表：计时器的
 * 云端痕迹 = 结算自动入账（source=DANCE），账目表即使用事实表（无独立上报表，
 * 禁为统计新建第二套数据源）。口径单一权威 = {@link SpendStatsRepository}。
 * <p>
 * 使用约束：<b>MySQL 8 方言查询</b>（生产 RDS MySQL），勿在 PG 环境执行。
 */
@Service
@RequiredArgsConstructor
public class AdminSpendStatsService {

    /** 窗口钳制：最少 7 天（看周趋势）、最多 90 天（同大盘 daily-stats） */
    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 90;

    /** 近 7 日活跃窗口（含今日） */
    private static final int ACTIVE_DAYS = 7;

    /** 门店 TOP 上限 */
    private static final int VENUE_TOP_LIMIT = 10;

    /** 用户流水条数钳制（默认 50，10~200） */
    private static final int MIN_ENTRY_LIMIT = 10;
    private static final int MAX_ENTRY_LIMIT = 200;
    private static final int DEFAULT_ENTRY_LIMIT = 50;

    private final SpendStatsRepository spendStatsRepository;

    /**
     * 计时器 & 账本使用统计（汇总 + 近 N 天按日 + 分类 + 门店 TOP）。
     *
     * @param days 窗口天数（钳制 7~90；缺省 30）
     */
    @Transactional(readOnly = true)
    public AdminSpendUsageStatsResponse usageStats(int days) {
        int window = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now();
        LocalDate sinceDay = today.minusDays(window - 1L);
        LocalDate activeSince = today.minusDays(ACTIVE_DAYS - 1L);

        SpendStatsRepository.SummaryRow summary = spendStatsRepository.sumSummary(activeSince);
        List<DailyPoint> daily = spendStatsRepository.countDaily(sinceDay).stream()
                .map(r -> new DailyPoint(r.getDay(), nz(r.getDanceEntries()), nz(r.getManualEntries()),
                        nz(r.getActiveUsers())))
                .toList();
        List<CategorySlice> byCategory = spendStatsRepository.sumByCategory().stream()
                .map(r -> new CategorySlice(r.getCategory(), nz(r.getEntryCount()), z(r.getTotal())))
                .toList();
        List<VenueSlice> byVenue = spendStatsRepository.sumByVenueTop(VENUE_TOP_LIMIT).stream()
                .map(r -> new VenueSlice(r.getVenueId(), r.getVenueName(), nz(r.getEntryCount()), z(r.getTotal())))
                .toList();

        return new AdminSpendUsageStatsResponse(
                new Summary(
                        nz(summary.getTotalUsers()),
                        nz(summary.getTimerUsers()),
                        nz(summary.getActive7d()),
                        nz(summary.getTotalEntries()),
                        nz(summary.getDanceEntries()),
                        nz(summary.getManualEntries()),
                        z(summary.getExpenseTotal()),
                        z(summary.getIncomeTotal())),
                daily,
                byCategory,
                byVenue);
    }

    /**
     * 记账用户列表（按最近记账降序）：有账目的真实用户 + 逐用户聚合，
     * admin-web「记账用户」列表数据源（行点击下钻用户详情）。
     */
    @Transactional(readOnly = true)
    public List<AdminSpendUsageUserItem> usageUsers() {
        return spendStatsRepository.listUsageUsers().stream()
                .map(r -> new AdminSpendUsageUserItem(
                        r.getUserId() == null ? 0L : r.getUserId(),
                        r.getNickname(),
                        r.getAvatarUrl(),
                        nz(r.getEntryCount()),
                        nz(r.getDanceEntries()),
                        nz(r.getManualEntries()),
                        z(r.getExpenseTotal()),
                        z(r.getIncomeTotal()),
                        r.getLastEntryAt()))
                .toList();
    }

    /**
     * 单用户计时/记账流水（用户详情「计时 · 账本」卡片）：summary = 全量历史
     * 汇总，entries = 按业务时刻降序的最近流水（只回未软删）。
     *
     * @param userId 用户 id（路由参数）
     * @param limit  流水条数（钳制 10~200；缺省 50）
     */
    @Transactional(readOnly = true)
    public AdminSpendUserEntriesResponse userEntries(long userId, int limit) {
        int capped = Math.max(MIN_ENTRY_LIMIT, Math.min(MAX_ENTRY_LIMIT, limit <= 0 ? DEFAULT_ENTRY_LIMIT : limit));
        SpendStatsRepository.UserSummaryRow s = spendStatsRepository.sumUserSummary(userId);
        List<EntryItem> entries = spendStatsRepository.listUserEntries(userId, capped).stream()
                .map(r -> new EntryItem(
                        r.getId() == null ? 0L : r.getId(),
                        r.getTs(),
                        z(r.getAmount()),
                        r.getDirection(),
                        r.getSource(),
                        r.getCategory(),
                        r.getVenueId(),
                        r.getVenueName(),
                        r.getDurationSeconds()))
                .toList();
        AdminSpendUserEntriesResponse.Summary summary = new AdminSpendUserEntriesResponse.Summary(
                nz(s.getEntryCount()),
                nz(s.getDanceEntries()),
                nz(s.getManualEntries()),
                z(s.getExpenseTotal()),
                z(s.getIncomeTotal()));
        return new AdminSpendUserEntriesResponse(summary, entries);
    }

    /** SQL COALESCE 已补零，此处兜底 null 防御（投影层极端情况，同大盘） */
    private static long nz(Long v) {
        return v == null ? 0L : v;
    }

    private static BigDecimal z(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }
}
