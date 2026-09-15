package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse.CohortItem;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse.CurvePoint;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse.DailyPoint;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse.ScopeAudit;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse.Summary;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.user.repository.UserRetentionRepository;
import org.quwuting.quwutingservice.user.repository.UserRetentionRepository.ActivitySummaryRow;
import org.quwuting.quwutingservice.user.repository.UserRetentionRepository.CohortRetentionRow;
import org.quwuting.quwutingservice.user.repository.UserRetentionRepository.CohortSizeRow;
import org.quwuting.quwutingservice.user.repository.UserRetentionRepository.ScopeAuditRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 管理端「用户留存分析」服务（2026-09-15，docs/agents/35-dashboard-stats.md；
 * 仅 ADMIN 消费）。
 * <p>
 * <b>定位（回答「老用户还在不在」）</b>：运营大盘只给「当日注册 / 打开 / 真实互动」
 * 三条<b>日总量</b>曲线——新人与存量混在一条线上，早期注册量大时「看着在涨」，
 * 而<b>存量流失被新增掩盖</b>（35 号文档记录的「8-31 注册 53 → 后续真实互动仅 8」
 * 就藏在库里看不出来）。本服务把同一批事实重排成三种视角：
 * <ol>
 *   <li><b>批次留存矩阵</b>：按注册日分批次，看 D1/D3/D7/D14/D30 的当日留存；</li>
 *   <li><b>留存曲线</b>：已到期批次加权的整体衰减曲线（一条线看清留存形态）；</li>
 *   <li><b>新老拆分趋势</b>：逐日把活跃拆成「新活跃」与「老用户活跃」，
 *       存量那条线才是留在平台上的人。</li>
 * </ol>
 * <p>
 * <b>口径单一权威 = {@code UserStatsSql}</b>（真实舞友范围 + 有效活跃事实集）；
 * 本类只做纯算术派生，<b>不重新定义任何口径</b>：比率不下发（前端派生），
 * 只判定「窗口是否到期」这一件事（那是口径，不是展示）。
 * <p>
 * 使用约束：<b>MySQL 8 方言查询</b>（生产 RDS MySQL），勿在 PG 环境执行。
 */
@Service
@RequiredArgsConstructor
public class AdminUserRetentionService {

    /** 窗口钳制：最少 7 天（看周留存）、最多 90 天（同大盘 daily-stats） */
    private static final int MIN_DAYS = 7;
    private static final int MAX_DAYS = 90;

    /** 顶卡「近 7 日活跃」窗口（与大盘顶卡同口径，便于交叉验算） */
    private static final int ACTIVE_DAYS = 7;

    /** 留存矩阵观测偏移（业界通用档位：次日 / 3 日 / 周 / 双周 / 月） */
    private static final int[] RETENTION_OFFSETS = {1, 3, 7, 14, 30};

    /** 留存曲线最大偏移（与矩阵最大档位一致；实际按窗口到期情况自动截断） */
    private static final int MAX_CURVE_OFFSET = 30;

    private final UserRetentionRepository retentionRepository;
    private final UserRepository userRepository;

    /**
     * 留存分析（汇总 + 逐日拆分 + 留存曲线 + 批次矩阵）。
     *
     * @param days 窗口天数（钳制 7~90；缺省 30）
     */
    @Transactional(readOnly = true)
    public AdminUserRetentionResponse retention(int days) {
        int window = Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate today = LocalDate.now();
        LocalDate sinceDay = today.minusDays(window - 1L);

        // 批次规模（分母）与批次 × 偏移留存人数（分子）：同窗口同口径
        Map<LocalDate, Long> cohortSizes = new TreeMap<>();
        for (CohortSizeRow row : retentionRepository.listCohortSizes(sinceDay)) {
            cohortSizes.put(row.getCohortDay(), nz(row.getSize()));
        }
        Map<LocalDate, Map<Integer, Long>> cohortRetained = new HashMap<>();
        for (CohortRetentionRow row : retentionRepository.listCohortRetention(sinceDay)) {
            cohortRetained
                    .computeIfAbsent(row.getCohortDay(), k -> new HashMap<>())
                    .merge(row.getDayOffset(), nz(row.getRetained()), Long::sum);
        }

        return new AdminUserRetentionResponse(
                window,
                buildSummary(today),
                buildScopeAudit(),
                buildDaily(sinceDay),
                buildCurve(today, cohortSizes, cohortRetained),
                buildCohorts(today, cohortSizes, cohortRetained));
    }

    /**
     * 口径自证（账号盘子漏斗）：全部账号 = 有效用户 + 运营/开发号 + 微信审核号（互斥划分）。
     * 让「已剔除管理员与微信审核账号」这条约定在页面上可当场验算，而不是只能靠信任。
     */
    private ScopeAudit buildScopeAudit() {
        ScopeAuditRow row = retentionRepository.sumScopeAudit();
        if (row == null) {
            return new ScopeAudit(0L, 0L, 0L);
        }
        return new ScopeAudit(
                nz(row.getTotalAccounts()), nz(row.getOpsExcluded()), nz(row.getReviewExcluded()));
    }

    /** 近 7 日活跃汇总（窗口内跨日去重；totalUsers 取全量有效用户 = 真实舞友） */
    private Summary buildSummary(LocalDate today) {
        LocalDate activeSince = today.minusDays(ACTIVE_DAYS - 1L);
        ActivitySummaryRow row = retentionRepository.sumActivity(activeSince);
        long totalUsers = userRepository.countRealUsers();
        if (row == null) {
            return new Summary(totalUsers, 0L, 0L);
        }
        return new Summary(totalUsers, nz(row.getActiveUsers()), nz(row.getReturningUsers()));
    }

    /** 近 N 天逐日新老活跃拆分（SQL 已骨架补零，此处只搬运） */
    private List<DailyPoint> buildDaily(LocalDate sinceDay) {
        return retentionRepository.listDailyActivity(sinceDay).stream()
                .map(r -> new DailyPoint(r.getDay(), nz(r.getNewActive()), nz(r.getReturningActive())))
                .toList();
    }

    /**
     * 加权留存曲线：每个偏移的分子 = 各批次该偏移留存人数之和，分母 = 该偏移已到期
     * 批次的人数之和（{@code cohortDay <= today - offset}）。
     * <p>
     * 未到期偏移不出点（不是 0）——曲线天然截止在最后一批已到期数据上。
     * 偏移上限 = min(30, 窗口-1)：窗口内最早的批次最多只能观测到「窗口-1」天。
     */
    private List<CurvePoint> buildCurve(LocalDate today, Map<LocalDate, Long> cohortSizes,
                                        Map<LocalDate, Map<Integer, Long>> cohortRetained) {
        List<CurvePoint> curve = new ArrayList<>();
        int maxOffset = Math.min(MAX_CURVE_OFFSET, maxObservableOffset(today, cohortSizes));
        for (int offset = 1; offset <= maxOffset; offset++) {
            long base = 0L;
            long retained = 0L;
            LocalDate maturedBefore = today.minusDays(offset);
            for (Map.Entry<LocalDate, Long> cohort : cohortSizes.entrySet()) {
                if (cohort.getKey().isAfter(maturedBefore)) {
                    continue; // 该批次到本偏移还没走完，不进分母（也必然无分子数据）
                }
                base += cohort.getValue();
                retained += cohortRetained.getOrDefault(cohort.getKey(), Map.of()).getOrDefault(offset, 0L);
            }
            if (base == 0L) {
                continue; // 无已到期批次 → 不出点（宁缺勿造 0）
            }
            curve.add(new CurvePoint(offset, retained, base));
        }
        return curve;
    }

    /** 窗口内最早批次已走完的最大天数（今日 - 最早批次日）；无批次 = 0 */
    private static int maxObservableOffset(LocalDate today, Map<LocalDate, Long> cohortSizes) {
        if (cohortSizes.isEmpty()) {
            return 0;
        }
        LocalDate earliest = cohortSizes.keySet().iterator().next(); // TreeMap 升序，首个即最早
        return (int) Math.max(0L, ChronoUnit.DAYS.between(earliest, today));
    }

    /**
     * 批次留存矩阵：批次日升序，列 = D1/D3/D7/D14/D30。
     * <p>
     * <b>未到期置 null、到期无人回访置 0</b>——两者在「近期留存崩溃」的错误结论里
     * 是同一副面孔，必须分开（前端渲染 null 为「—」）。
     */
    private List<CohortItem> buildCohorts(LocalDate today, Map<LocalDate, Long> cohortSizes,
                                          Map<LocalDate, Map<Integer, Long>> cohortRetained) {
        List<CohortItem> cohorts = new ArrayList<>(cohortSizes.size());
        for (Map.Entry<LocalDate, Long> cohort : cohortSizes.entrySet()) {
            LocalDate cohortDay = cohort.getKey();
            Map<Integer, Long> retained = cohortRetained.getOrDefault(cohortDay, Map.of());
            Long[] cells = new Long[RETENTION_OFFSETS.length];
            for (int i = 0; i < RETENTION_OFFSETS.length; i++) {
                cells[i] = cell(today, cohortDay, retained, RETENTION_OFFSETS[i]);
            }
            cohorts.add(new CohortItem(cohortDay, cohort.getValue(),
                    cells[0], cells[1], cells[2], cells[3], cells[4]));
        }
        return cohorts;
    }

    /** 单个留存格：已到期 → 留存人数（缺失补 0）；未到期 → null（前端「—」） */
    private static Long cell(LocalDate today, LocalDate cohortDay, Map<Integer, Long> retained, int offset) {
        if (cohortDay.plusDays(offset).isAfter(today)) {
            return null;
        }
        return retained.getOrDefault(offset, 0L);
    }

    /** SQL COALESCE 已补零，此处兜底 null 防御（投影层极端情况，同大盘） */
    private static long nz(Long v) {
        return v == null ? 0L : v;
    }
}
