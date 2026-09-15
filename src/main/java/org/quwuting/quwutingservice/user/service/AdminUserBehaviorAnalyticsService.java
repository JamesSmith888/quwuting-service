package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorAnalysisResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserRetentionResponse;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.user.repository.UserBehaviorRepository;
import org.quwuting.quwutingservice.user.repository.UserRetentionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 管理端「用户行为统计分析」服务（2026-09-15，docs/agents/35-dashboard-stats.md
 * 「用户行为轨迹与行为分析」节；仅 ADMIN）。
 *
 * <h2>定位</h2>
 * 回答「整体上用户在做什么 / 什么时候做 / 谁还在做」——平台级行为盘子。
 * 单账号的轨迹与画像见 {@code AdminUserBehaviorService}。两者共用同一份行为事件目录
 * （{@link UserBehaviorEvent}）与同一批事实集常量（{@code UserBehaviorSql}）。
 *
 * <h2>口径（唯一权威 = 目录 + {@code UserStatsSql}）</h2>
 * <ul>
 *   <li><b>用户范围</b>：全部查询 {@code JOIN qwt_users} + {@code USER_SCOPE}——
 *       已剔除 ADMIN 运营号 / {@code test_} 开发联号 / 微信审核账号；</li>
 *   <li><b>「活跃」= 主动行为</b>（{@code Nature#ACTIVE}，12 表），<b>不含登录自动打卡</b>；
 *       打卡在类型分布里以「系统信号」档显式出现，并单独支撑「仅打开无行为」分层，
 *       而<b>不</b>被算进活跃（2026-09-15 修复的那个错误决策即为把打卡当活跃）；</li>
 *   <li><b>比率/人均不下发</b>：只给原始计数（次数 / 天数 / 人数与
 *       {@code activeDaysSum} 之和），占比与人均由前端派生；</li>
 *   <li><b>分层判据在服务端</b>：分层是口径而非展示（阈值见下方常量），前端只渲染——
 *       否则改一次阈值就会让图表之间互相矛盾。</li>
 * </ul>
 *
 * <h2>活跃分层的分母归一（本能力最关键的口径决策，勿回退）</h2>
 * 分层用「活跃天数 <b>占可用天数的比例</b>」而不是绝对天数：{@code 可用天数 =
 * min(窗口天数, 注册至今 + 1)}。理由与留存报表「未到期 ≠ 0%」（2026-09-15）完全同族：
 * 按绝对值切档会把窗口内刚注册的用户系统性判成「沉默/低频」——注册两天的人不可能有八个
 * 活跃日，那不是用户不活跃，是观测期不够。故 {@code 可用天数 ≤ NEW_USER_AVAILABLE_DAYS}
 * 的用户单列「新近注册」层，不参与比例分档。
 * <p>
 * 六层互斥且完备（所有真实用户恰好落入一层，人数之和 = 总用户数）——这个等式让页面上的
 * 分层图可以被当场验算，也防止将来有人新增一层时漏掉一支。
 */
@Service
@RequiredArgsConstructor
public class AdminUserBehaviorAnalyticsService {

    /** 窗口下限（同大盘族） */
    private static final int MIN_DAYS = 7;
    /** 窗口上限（同大盘族） */
    private static final int MAX_DAYS = 90;
    private static final int DEFAULT_DAYS = 30;
    /** 时段直方图格数（0~23 时） */
    private static final int HOURS_PER_DAY = 24;

    /** 可用天数 ≤ 此值 → 「新近注册」层（观测期不足，不参与比例分档） */
    private static final long NEW_USER_AVAILABLE_DAYS = 3;
    /** 活跃天数占可用天数 ≥ 此比例 → 高频活跃 */
    private static final double HIGH_ACTIVE_RATIO = 0.5;
    /** ≥ 此比例（且低于高频阈值）→ 常规活跃；低于则低频活跃 */
    private static final double REGULAR_ACTIVE_RATIO = 0.25;

    /** 行为宽度桶界（含上界）：1 类 / 2 类 / 3-4 类 / 5 类及以上（最后一桶无上限） */
    private static final int WIDTH_MID_MAX = 2;
    private static final int WIDTH_WIDE_MAX = 4;

    private final UserBehaviorRepository behaviorRepository;
    /**
     * 口径自证（账号盘子漏斗）复用留存能力的查询：<b>不</b>在本服务再写一份——
     * 同一等式两处实现必然漂移，而「自证算错」比不显示更糟。
     */
    private final UserRetentionRepository retentionRepository;

    /**
     * 用户行为统计分析（近 N 天窗口，钳制 7~90，缺省 30）。
     * <p>
     * 5 条同参同窗口的聚合 + 1 条口径自证：类型分布、逐用户活跃天数、逐用户打开天数、
     * 时段直方图、可用于分层归一的注册日盘子。全部为只读聚合，无写路径、无迁移。
     */
    @Transactional(readOnly = true)
    public AdminUserBehaviorAnalysisResponse analysis(int days) {
        int window = days <= 0 ? DEFAULT_DAYS : Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
        LocalDate sinceDay = LocalDate.now().minusDays(window - 1L);
        LocalDate today = LocalDate.now();

        List<UserBehaviorRepository.UserJoinedRow> users = behaviorRepository.listRealUserJoinedDays();
        List<UserBehaviorRepository.UserTypeRow> userTypes =
                behaviorRepository.listPlatformUserTypes(sinceDay);
        Map<Long, Long> activeDaysByUser =
                toDayMap(behaviorRepository.listPlatformUserActiveDays(sinceDay));
        Map<Long, Long> openDaysByUser = toDayMap(behaviorRepository.listPlatformUserEventDays(
                sinceDay, UserBehaviorEvent.openSignal().code()));
        List<UserBehaviorRepository.HourRow> hourRows =
                behaviorRepository.listPlatformHourly(sinceDay);

        // ── 类型维度（全目录，0 也保留一行：类型清单要稳定可发现） ──────────────
        Map<String, long[]> typeAgg = new HashMap<>();          // 事件码 → [人数, 条数]
        Map<Long, Set<String>> activeTypesByUser = new HashMap<>(); // 用户 → 主动行为类型集合
        for (UserBehaviorRepository.UserTypeRow row : userTypes) {
            UserBehaviorEvent event = UserBehaviorEvent.byCode(row.getEventType());
            if (event == null) {
                continue;   // 目录与 SQL 由门禁锁定，正常不出现；单条未知类型跳过不炸页
            }
            long cnt = nz(row.getCnt());
            long[] agg = typeAgg.computeIfAbsent(event.code(), k -> new long[2]);
            agg[0]++;
            agg[1] += cnt;
            if (event.nature() == UserBehaviorEvent.Nature.ACTIVE) {
                activeTypesByUser.computeIfAbsent(row.getUserId(), k -> new HashSet<>())
                        .add(event.code());
            }
        }

        List<AdminUserBehaviorAnalysisResponse.TypeStat> byType = UserBehaviorEvent.all().stream()
                .map(event -> {
                    long[] agg = typeAgg.getOrDefault(event.code(), new long[2]);
                    return new AdminUserBehaviorAnalysisResponse.TypeStat(
                            event.code(), event.label(), event.category().label(),
                            event.nature().name(), event.nature().label(), event.nature().hint(),
                            agg[0], agg[1]);
                })
                .toList();

        // ── 时段维度（只统计有精确时刻的主动行为；缺口用 timedOutActiveEvents 显式交代） ──
        long[] hourly = new long[HOURS_PER_DAY];
        long[] hourlyUsers = new long[HOURS_PER_DAY];
        long timedActiveEvents = 0;
        for (UserBehaviorRepository.HourRow row : hourRows) {
            if (row.getHour() == null || row.getHour() < 0 || row.getHour() >= HOURS_PER_DAY) {
                continue;   // SQL 已过滤 event_time IS NOT NULL；此处只做边界防御
            }
            hourly[row.getHour()] = nz(row.getEvents());
            hourlyUsers[row.getHour()] = nz(row.getUsers());
            timedActiveEvents += nz(row.getEvents());
        }

        long activeEvents = byType.stream()
                .filter(t -> UserBehaviorEvent.Nature.ACTIVE.name().equals(t.nature()))
                .mapToLong(AdminUserBehaviorAnalysisResponse.TypeStat::events)
                .sum();
        long activeDaysSum = activeDaysByUser.values().stream().mapToLong(Long::longValue).sum();

        return new AdminUserBehaviorAnalysisResponse(
                window,
                new AdminUserBehaviorAnalysisResponse.Summary(
                        users.size(), activeDaysByUser.size(), activeEvents, activeDaysSum),
                byType,
                segments(users, window, today, activeDaysByUser, openDaysByUser),
                widthBuckets(activeTypesByUser),
                boxed(hourly),
                boxed(hourlyUsers),
                Math.max(0L, activeEvents - timedActiveEvents),
                scopeAudit());
    }

    // ── 活跃分层（六层互斥完备，判据即口径） ────────────────────────────────────

    private static List<AdminUserBehaviorAnalysisResponse.Segment> segments(
            List<UserBehaviorRepository.UserJoinedRow> users, int window, LocalDate today,
            Map<Long, Long> activeDaysByUser, Map<Long, Long> openDaysByUser) {
        long high = 0;
        long regular = 0;
        long low = 0;
        long openOnly = 0;
        long dormant = 0;
        long fresh = 0;
        for (UserBehaviorRepository.UserJoinedRow user : users) {
            long availableDays = window;
            if (user.getJoinedDay() != null) {
                availableDays = Math.min(window,
                        ChronoUnit.DAYS.between(user.getJoinedDay(), today) + 1L);
            }
            if (availableDays <= NEW_USER_AVAILABLE_DAYS) {
                fresh++;
                continue;
            }
            long activeDays = activeDaysByUser.getOrDefault(user.getUserId(), 0L);
            if (activeDays == 0) {
                if (openDaysByUser.getOrDefault(user.getUserId(), 0L) > 0) {
                    openOnly++;
                } else {
                    dormant++;
                }
                continue;
            }
            double ratio = (double) activeDays / availableDays;
            if (ratio >= HIGH_ACTIVE_RATIO) {
                high++;
            } else if (ratio >= REGULAR_ACTIVE_RATIO) {
                regular++;
            } else {
                low++;
            }
        }
        return List.of(
                new AdminUserBehaviorAnalysisResponse.Segment("HIGH", "高频活跃",
                        "活跃天数 ≥ 可用天数的 50%", high),
                new AdminUserBehaviorAnalysisResponse.Segment("REGULAR", "常规活跃",
                        "活跃天数占可用天数 25% ~ 50%", regular),
                new AdminUserBehaviorAnalysisResponse.Segment("LOW", "低频活跃",
                        "窗口内有主动行为，但活跃天数不足可用天数的 25%", low),
                new AdminUserBehaviorAnalysisResponse.Segment("OPEN_ONLY", "仅打开无行为",
                        "窗口内没有主动行为，但有登录自动打卡（审核/巡检号的典型形态）", openOnly),
                new AdminUserBehaviorAnalysisResponse.Segment("DORMANT", "完全沉默",
                        "窗口内既无主动行为也无打开记录", dormant),
                new AdminUserBehaviorAnalysisResponse.Segment("NEW", "新近注册",
                        "可用天数 ≤ " + NEW_USER_AVAILABLE_DAYS + " 天，观测期不足，不参与比例分档", fresh));
    }

    /** 行为宽度桶（只统计窗口内有主动行为的用户；无行为者已在分层里单列） */
    private static List<AdminUserBehaviorAnalysisResponse.WidthBucket> widthBuckets(
            Map<Long, Set<String>> activeTypesByUser) {
        long single = 0;
        long pair = 0;
        long mid = 0;
        long wide = 0;
        for (Set<String> types : activeTypesByUser.values()) {
            int width = types.size();
            if (width <= 1) {
                single++;
            } else if (width <= WIDTH_MID_MAX) {
                pair++;
            } else if (width <= WIDTH_WIDE_MAX) {
                mid++;
            } else {
                wide++;
            }
        }
        return List.of(
                new AdminUserBehaviorAnalysisResponse.WidthBucket("1 类", single),
                new AdminUserBehaviorAnalysisResponse.WidthBucket("2 类", pair),
                new AdminUserBehaviorAnalysisResponse.WidthBucket("3-4 类", mid),
                new AdminUserBehaviorAnalysisResponse.WidthBucket("5 类及以上", wide));
    }

    // ── 口径自证（与留存分析页同一查询，保证两页的等式逐字一致） ────────────────

    private AdminUserRetentionResponse.ScopeAudit scopeAudit() {
        UserRetentionRepository.ScopeAuditRow row = retentionRepository.sumScopeAudit();
        return new AdminUserRetentionResponse.ScopeAudit(
                nz(row.getTotalAccounts()), nz(row.getOpsExcluded()), nz(row.getReviewExcluded()));
    }

    // ── 工具 ──────────────────────────────────────────────────────────────────

    private static Map<Long, Long> toDayMap(List<UserBehaviorRepository.UserDayRow> rows) {
        Map<Long, Long> out = new HashMap<>();
        for (UserBehaviorRepository.UserDayRow row : rows) {
            if (row.getUserId() != null) {
                out.put(row.getUserId(), nz(row.getDays()));
            }
        }
        return out;
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static List<Long> boxed(long[] values) {
        List<Long> out = new ArrayList<>(values.length);
        for (long v : values) {
            out.add(v);
        }
        return out;
    }
}
