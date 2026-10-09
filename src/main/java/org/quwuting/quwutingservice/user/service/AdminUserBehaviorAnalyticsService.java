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
import java.util.Arrays;
import java.util.EnumMap;
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
 * 活跃日，那不是用户不活跃，是观测期不够。故 {@code 可用天数 ≤ BehaviorSegment#NEW_USER_AVAILABLE_DAYS}
 * 的用户单列「新近注册」层，不参与比例分档。
 * <p>
 * 七层互斥且完备（所有真实用户恰好落入一层，人数之和 = 总用户数）——这个等式让页面上的
 * 分层图可以被当场验算，也防止将来有人新增一层时漏掉一支。
 * <p>
 * <b>2026-10-09</b>：分类器抽成 {@link BehaviorSegment}（与用户详情页共用，阈值只有一处）；
 * 新增「仅用扩展功能」层——把「只看快讯 / 只用计时记账」的真实用户从「仅打开无行为」（审核/巡检形态）
 * 里分离出来，其人数同时就是「若把扩展功能纳入活跃口径，会新增多少活跃用户」的评审依据。
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
        Set<Long> extendedUsers = new HashSet<>();                  // 窗口内有扩展行为的用户
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
            if (event.nature() == UserBehaviorEvent.Nature.EXTENDED) {
                extendedUsers.add(row.getUserId());
            }
        }

        // 下线功能（舞伴 / 招工）只在窗口内仍有历史记录时下发：恒为 0 的下线类型留在清单里只是噪音，
        // 但有记录时必须保留——事实不因功能下线而消失（UserBehaviorEvent#isRetired）
        List<AdminUserBehaviorAnalysisResponse.TypeStat> byType = UserBehaviorEvent.all().stream()
                .filter(event -> !event.isRetired() || typeAgg.containsKey(event.code()))
                .map(event -> {
                    long[] agg = typeAgg.getOrDefault(event.code(), new long[2]);
                    return new AdminUserBehaviorAnalysisResponse.TypeStat(
                            event.code(), event.label(), event.category().label(),
                            event.nature().name(), event.nature().label(), event.nature().hint(),
                            event.isRetired(), agg[0], agg[1]);
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
                segments(users, window, today, activeDaysByUser, openDaysByUser, extendedUsers),
                widthBuckets(activeTypesByUser),
                boxed(hourly),
                boxed(hourlyUsers),
                Math.max(0L, activeEvents - timedActiveEvents),
                scopeAudit());
    }

    // ── 活跃分层（七层互斥完备，判据即口径；分类器见 BehaviorSegment） ──────────────

    private static List<AdminUserBehaviorAnalysisResponse.Segment> segments(
            List<UserBehaviorRepository.UserJoinedRow> users, int window, LocalDate today,
            Map<Long, Long> activeDaysByUser, Map<Long, Long> openDaysByUser,
            Set<Long> extendedUsers) {
        Map<BehaviorSegment, Long> counts = new EnumMap<>(BehaviorSegment.class);
        for (UserBehaviorRepository.UserJoinedRow user : users) {
            long availableDays = window;
            if (user.getJoinedDay() != null) {
                availableDays = Math.min(window,
                        ChronoUnit.DAYS.between(user.getJoinedDay(), today) + 1L);
            }
            // 扩展行为天数此处只需「有/无」（分层判据只看 >0），故以集合成员折算为 1/0
            long extendedDays = extendedUsers.contains(user.getUserId()) ? 1L : 0L;
            BehaviorSegment segment = BehaviorSegment.classify(availableDays,
                    activeDaysByUser.getOrDefault(user.getUserId(), 0L),
                    openDaysByUser.getOrDefault(user.getUserId(), 0L),
                    extendedDays);
            counts.merge(segment, 1L, Long::sum);
        }
        // 枚举声明序 = 展示序（高频 → … → 新近注册）；每层都下发（0 也保留），保证清单稳定
        return Arrays.stream(BehaviorSegment.values())
                .map(seg -> new AdminUserBehaviorAnalysisResponse.Segment(
                        seg.name(), seg.label(), seg.hint(), counts.getOrDefault(seg, 0L)))
                .toList();
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
