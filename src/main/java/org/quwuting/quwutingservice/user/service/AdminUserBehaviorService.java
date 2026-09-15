package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorProfileResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorTimelineResponse;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.user.repository.UserBehaviorRepository;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 管理端「单用户行为」服务（2026-09-15，docs/agents/35-dashboard-stats.md
 * 「用户行为轨迹与行为分析」节；仅 ADMIN）。
 *
 * <h2>定位</h2>
 * 回答运营对<b>某一个账号</b>的问题：他做过什么（{@link #timeline 轨迹}）、
 * 做得有多深/多规律（{@link #profile 行为画像}）。平台级「整体行为盘子」见
 * {@code AdminUserBehaviorAnalyticsService}（同一个目录、同一套事实集）。
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>事实集来自行为事件目录 {@link UserBehaviorEvent} 生成的两条常量
 *       （{@code UserBehaviorSql}），本类只做<b>分组、取名、派生展示文案</b>，
 *       <b>不写任何 SQL、不硬编码事件名</b>（事件中文名一律取 {@code label()}）；</li>
 *   <li>口径归属由目录的 {@link UserBehaviorEvent.Nature} 决定：画像里的「活跃天数 /
 *       活跃时段 / 近 7 日」只统计 {@code ACTIVE}；「打开天数」统计 {@code CHECKIN}。
 *       本类<b>不再按事件码 switch 分支</b>——新增事件只需改目录，画像自动获得它
 *       （这正是目录化的收益：消费方不需要为每个新事件改代码）；</li>
 *   <li><b>单用户读取不做用户范围过滤</b>（口径过滤发生在入口列表）：运营需要能对已标记
 *       的审核号做取证式查看，页面另有「微信审核」标记提示其口径归属；</li>
 *   <li>查询次数恒定：轨迹 = 2 条（列表 + 聚合），画像 = 1 条；取名按对象类型批量 IN，
 *       与行数无关（性能第一约束 = 最少 DB 往返，见 29-performance）。</li>
 * </ul>
 *
 * <h2>空值语义</h2>
 * 轨迹时刻为空的历史脏行不丢行、不伪造时刻：{@code timeApprox=true} 表示「只精确到日」；
 * 画像里 {@code firstActiveAt/lastActiveAt = null} 表示<b>窗口内没有主动行为</b>，
 * DTO 已用 {@code @JsonInclude(ALWAYS)} 保证这个 null 能到达前端（勿删）。
 */
@Service
@RequiredArgsConstructor
public class AdminUserBehaviorService {

    /** 窗口下限（同大盘族，7 天以下无行为学意义） */
    private static final int MIN_DAYS = 7;
    /** 窗口上限（同大盘族；再长会让 12 表 UNION 的扫描面显著变大） */
    private static final int MAX_DAYS = 90;
    private static final int DEFAULT_DAYS = 30;
    /** 轨迹返回上限（下界保证一次能看到「一天内的连续操作」，上界防单页拖死） */
    private static final int MIN_LIMIT = 20;
    private static final int MAX_LIMIT = 200;
    private static final int DEFAULT_LIMIT = 50;
    /** 时段直方图格数（0~23 时） */
    private static final int HOURS_PER_DAY = 24;
    /** 「近 7 日 / 此前 7 日」对比窗口 */
    private static final int COMPARE_DAYS = 7;

    private final UserRepository userRepository;
    private final UserBehaviorRepository behaviorRepository;
    private final BehaviorRefNameResolver refNames;

    // ── 轨迹 ──────────────────────────────────────────────────────────────────

    /**
     * 用户行为轨迹（一条时间线，时间倒序）。
     *
     * @param days  窗口天数（钳制 7~90，缺省 30）
     * @param type  事件码过滤（空 = 全部；非法码 → 1007，<b>禁静默忽略</b>——
     *              静默忽略会让运营以为「这个用户没做过」，而事实是参数写错了）
     * @param limit 返回上限（钳制 20~200，缺省 50）
     */
    @Transactional(readOnly = true)
    public AdminUserBehaviorTimelineResponse timeline(Long userId, int days, String type, int limit) {
        requireUser(userId);
        int window = clamp(days, MIN_DAYS, MAX_DAYS, DEFAULT_DAYS);
        int cap = clamp(limit, MIN_LIMIT, MAX_LIMIT, DEFAULT_LIMIT);
        LocalDate sinceDay = sinceDay(window);
        String code = normalizeType(type);

        List<UserBehaviorRepository.TimelineRow> rows =
                behaviorRepository.listTimeline(userId, sinceDay, code, cap);
        // 计数与筛选 chips 的计数来自同一条聚合（与列表同参同窗口 ⇒ 两个数字必然自洽）
        Map<String, TypeCounter> counters = countByType(
                behaviorRepository.listUserEvents(userId, sinceDay));
        long total = code == null
                ? counters.values().stream().mapToLong(c -> c.count).sum()
                : countOf(counters, code);

        Map<UserBehaviorEvent.RefKind, Map<Long, String>> namesByKind = resolveRefNames(rows);

        List<AdminUserBehaviorTimelineResponse.Item> items = new ArrayList<>(rows.size());
        int seq = 0;
        for (UserBehaviorRepository.TimelineRow row : rows) {
            UserBehaviorEvent event = UserBehaviorEvent.byCode(row.getEventType());
            if (event == null) {
                // 目录与 SQL 由门禁逐字锁定，正常不可能走到这里；单条未知事件只跳过，
                // 不让整页失败（展示层兜底原则，同 BehaviorRefNameResolver）
                continue;
            }
            String target = targetOf(event, row.getRefId(), namesByKind);
            items.add(new AdminUserBehaviorTimelineResponse.Item(
                    event.code() + "#" + (++seq),
                    event.code(),
                    event.nature().name(),
                    event.nature().label(),
                    composeTitle(event, target),
                    refNames.dictionary(event.detailDict(), row.getDetailText()),
                    row.getEventDay(),
                    row.getHappenedAt(),
                    row.getEventTime() == null));
        }

        return new AdminUserBehaviorTimelineResponse(
                window,
                code == null ? "" : code,
                total,
                total > items.size(),
                cap,
                typeOptions(counters),
                items);
    }

    // ── 行为画像 ──────────────────────────────────────────────────────────────

    /**
     * 单用户行为画像（窗口内的类型分布 / 活跃天数 / 打开天数 / 活跃时段 / 近两周对比）。
     * <p>
     * 一条聚合查询（{@code 事件类型 × 日 × 小时}）派生全部维度——单用户体量下
     * 多一次往返换不来收益，反而把「活跃天数」这类<b>跨类型去重</b>的语义拆散到多条 SQL 里
     * （去重口径被拆开正是最容易算错的地方）。
     */
    @Transactional(readOnly = true)
    public AdminUserBehaviorProfileResponse profile(Long userId, int days) {
        requireUser(userId);
        int window = clamp(days, MIN_DAYS, MAX_DAYS, DEFAULT_DAYS);
        LocalDate sinceDay = sinceDay(window);
        LocalDate today = LocalDate.now();

        List<UserBehaviorRepository.UserEventRow> rows =
                behaviorRepository.listUserEvents(userId, sinceDay);

        Set<LocalDate> activeDays = new HashSet<>();
        Set<LocalDate> openDays = new HashSet<>();
        long eventTotal = 0;
        long activeEventTotal = 0;
        long timedOutActiveEvents = 0;
        long recent7 = 0;
        long prev7 = 0;
        LocalDate recent7From = today.minusDays(COMPARE_DAYS - 1L);
        LocalDate prev7From = today.minusDays(COMPARE_DAYS * 2L - 1L);
        LocalDate prev7To = today.minusDays(COMPARE_DAYS);
        LocalDateTime firstActiveAt = null;
        LocalDateTime lastActiveAt = null;
        long[] hourly = new long[HOURS_PER_DAY];

        // 逐类型聚合：类型 → (条数, 去重天数, 最近时刻)
        Map<String, TypeCounter> counters = new TreeMap<>();

        for (UserBehaviorRepository.UserEventRow row : rows) {
            UserBehaviorEvent event = UserBehaviorEvent.byCode(row.getEventType());
            if (event == null) {
                continue;
            }
            long cnt = nz(row.getCnt());
            eventTotal += cnt;
            TypeCounter counter = counters.computeIfAbsent(event.code(), k -> new TypeCounter());
            counter.count += cnt;
            counter.days.add(row.getEventDay());
            counter.lastAt = latest(counter.lastAt, row.getLastAt());

            if (event.nature() == UserBehaviorEvent.Nature.SIGNAL) {
                openDays.add(row.getEventDay());
            }
            if (event.nature() != UserBehaviorEvent.Nature.ACTIVE) {
                continue;
            }
            activeDays.add(row.getEventDay());
            activeEventTotal += cnt;
            if (row.getHour() == null) {
                timedOutActiveEvents += cnt; // 无时间戳 ⇒ 不入时段分布（宁缺勿造）
            } else {
                hourly[row.getHour()] += cnt;
            }
            if (!row.getEventDay().isBefore(recent7From)) {
                recent7 += cnt;
            } else if (!row.getEventDay().isBefore(prev7From) && !row.getEventDay().isAfter(prev7To)) {
                prev7 += cnt;
            }
            if (row.getLastAt() != null) {
                firstActiveAt = earlier(firstActiveAt, row.getLastAt());
                lastActiveAt = latest(lastActiveAt, row.getLastAt());
            }
        }

        List<AdminUserBehaviorProfileResponse.TypeCount> breakdown = counters.entrySet().stream()
                .map(entry -> {
                    UserBehaviorEvent event = UserBehaviorEvent.byCode(entry.getKey());
                    TypeCounter counter = entry.getValue();
                    return new AdminUserBehaviorProfileResponse.TypeCount(
                            event.code(), event.label(), event.category().label(),
                            event.nature().name(), event.nature().label(),
                            counter.count, counter.days.size(), counter.lastAt);
                })
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();

        return new AdminUserBehaviorProfileResponse(
                window,
                eventTotal,
                activeEventTotal,
                activeDays.size(),
                openDays.size(),
                recent7,
                prev7,
                timedOutActiveEvents,
                firstActiveAt,
                lastActiveAt,
                breakdown,
                boxed(hourly));
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private void requireUser(Long userId) {
        userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
    }

    /** 事件码归一：空 → null（全部）；非法码 → 1007（禁静默忽略，见 {@link #timeline}） */
    private static String normalizeType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        UserBehaviorEvent event = UserBehaviorEvent.byCode(type);
        if (event == null) {
            throw new BusinessException(1007, "无效的事件类型");
        }
        return event.code();
    }

    /** 聚合行 → 类型计数（轨迹总数与 chips 计数共用；与画像的逐类型聚合同形） */
    private static Map<String, TypeCounter> countByType(
            List<UserBehaviorRepository.UserEventRow> rows) {
        Map<String, TypeCounter> counters = new TreeMap<>();
        for (UserBehaviorRepository.UserEventRow row : rows) {
            long cnt = nz(row.getCnt());
            TypeCounter counter = counters.computeIfAbsent(row.getEventType(), k -> new TypeCounter());
            counter.count += cnt;
            counter.days.add(row.getEventDay());
            counter.lastAt = latest(counter.lastAt, row.getLastAt());
        }
        return counters;
    }

    /**
     * 筛选 chips 的目录（<b>全量下发</b>）：每个事件带窗口内计数——0 条的类型也保留，
     * 让运营能一眼看到「有这一类行为，但这个用户没做过」，而不是以为系统漏了。
     */
    private static List<AdminUserBehaviorTimelineResponse.TypeOption> typeOptions(
            Map<String, TypeCounter> counters) {
        return UserBehaviorEvent.all().stream()
                .map(event -> new AdminUserBehaviorTimelineResponse.TypeOption(
                        event.code(), event.label(),
                        event.category().name(), event.category().label(),
                        event.nature().name(), event.nature().label(), event.nature().hint(),
                        countOf(counters, event.code())))
                .toList();
    }

    private static long countOf(Map<String, TypeCounter> counters, String code) {
        TypeCounter counter = counters.get(code);
        return counter == null ? 0L : counter.count;
    }

    /** 按关联对象类型批量取名（最多 4 次 IN 查询，与行数无关） */
    private Map<UserBehaviorEvent.RefKind, Map<Long, String>> resolveRefNames(
            List<UserBehaviorRepository.TimelineRow> rows) {
        Map<UserBehaviorEvent.RefKind, Set<Long>> idsByKind =
                new EnumMap<>(UserBehaviorEvent.RefKind.class);
        for (UserBehaviorRepository.TimelineRow row : rows) {
            UserBehaviorEvent event = UserBehaviorEvent.byCode(row.getEventType());
            if (event == null || row.getRefId() == null
                    || event.refKind() == UserBehaviorEvent.RefKind.NONE) {
                continue;
            }
            idsByKind.computeIfAbsent(event.refKind(), k -> new LinkedHashSet<>()).add(row.getRefId());
        }
        Map<UserBehaviorEvent.RefKind, Map<Long, String>> out =
                new EnumMap<>(UserBehaviorEvent.RefKind.class);
        idsByKind.forEach((kind, ids) -> out.put(kind, refNames.names(kind, ids)));
        return out;
    }

    /** 关联对象展示名（查不到 = 对象已删/软删 → 中性兜底，不显示「null」也不报错） */
    private static String targetOf(UserBehaviorEvent event, Long refId,
                                   Map<UserBehaviorEvent.RefKind, Map<Long, String>> namesByKind) {
        if (refId == null || event.refKind() == UserBehaviorEvent.RefKind.NONE) {
            return "";
        }
        Map<Long, String> names = namesByKind.getOrDefault(event.refKind(), Map.of());
        String name = names.get(refId);
        if (name != null && !name.isBlank()) {
            return name;
        }
        return switch (event.refKind()) {
            case VENUE -> BehaviorRefNameResolver.VENUE_FALLBACK + " #" + refId;
            case DANCER -> BehaviorRefNameResolver.DANCER_FALLBACK + " #" + refId;
            case RECRUITMENT -> BehaviorRefNameResolver.RECRUITMENT_FALLBACK;
            case ANNOUNCEMENT -> BehaviorRefNameResolver.ANNOUNCEMENT_FALLBACK;
            case NONE -> "";
        };
    }

    /** 主文案 = 事件名 + （有对象时）对象名（服务端权威拼装，前端零拼接） */
    private static String composeTitle(UserBehaviorEvent event, String target) {
        return target.isEmpty() ? event.label() : event.label() + "「" + target + "」";
    }

    private static LocalDate sinceDay(int window) {
        return LocalDate.now().minusDays(window - 1L);
    }

    private static int clamp(int value, int min, int max, int fallback) {
        int v = value <= 0 ? fallback : value;
        return Math.max(min, Math.min(max, v));
    }

    private static long nz(Long value) {
        return value == null ? 0L : value;
    }

    private static LocalDateTime latest(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isAfter(b) ? a : b;
    }

    private static LocalDateTime earlier(LocalDateTime a, LocalDateTime b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static List<Long> boxed(long[] values) {
        List<Long> out = new ArrayList<>(values.length);
        for (long v : values) {
            out.add(v);
        }
        return out;
    }

    /** 类型计数累加器（条数 / 去重天数 / 最近时刻） */
    private static final class TypeCounter {

        private long count;
        private final Set<LocalDate> days = new HashSet<>();
        private LocalDateTime lastAt;
    }
}
