package org.quwuting.quwutingservice.user.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorProfileResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorTimelineResponse;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.user.repository.UserBehaviorRepository;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
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
 *       与行数无关（性能第一约束 = 最少 DB 往返，见 29-performance）；</li>
 *   <li><b>2026-10-09 更新</b>：① 轨迹同类连发合并成一行（{@link BehaviorTimelineMerger}，
 *       快讯浏览一次写 4~10 行）；② 筛选选项隐去「窗口内 0 条的下线功能」类型
 *       （{@code UserBehaviorEvent#isRetired}，历史数据仍保留）；③ 画像增加扩展行为（不计活跃）
 *       与活跃分层（{@link BehaviorSegment}，与平台页同一分类器）；④ 单对象行下发 {@code refKind/refId}
 *       供前端深链。</li>
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
    /** 合并行明细里最多列出的对象名个数（其余折叠为「等 N 个」） */
    private static final int MAX_TARGET_NAMES = 4;
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

        List<BehaviorTimelineMerger.Raw> raws = new ArrayList<>(rows.size());
        for (UserBehaviorRepository.TimelineRow row : rows) {
            UserBehaviorEvent event = UserBehaviorEvent.byCode(row.getEventType());
            if (event == null) {
                // 目录与 SQL 由门禁逐字锁定，正常不可能走到这里；单条未知事件只跳过，
                // 不让整页失败（展示层兜底原则，同 BehaviorRefNameResolver）
                continue;
            }
            raws.add(new BehaviorTimelineMerger.Raw(event, row.getHappenedAt(), row.getEventDay(),
                    row.getEventTime() == null, row.getRefId(), row.getDetailText()));
        }

        List<AdminUserBehaviorTimelineResponse.Item> items = new ArrayList<>();
        long shown = 0;
        int seq = 0;
        for (BehaviorTimelineMerger.Burst burst : BehaviorTimelineMerger.merge(raws)) {
            items.add(toItem(burst, ++seq, namesByKind));
            shown += burst.count();
        }

        return new AdminUserBehaviorTimelineResponse(
                window,
                code == null ? "" : code,
                total,
                total > shown,
                shown,
                cap,
                typeOptions(counters),
                items);
    }

    /**
     * 批 → 轨迹行。单条行与旧版逐字一致（标题「事件名「对象」」+ 字典明细）；
     * 合并行（count &gt; 1）标题带「×N」：
     * 批内只涉及<b>一个对象</b>时保留对象名（「浏览门店「A」×3」），涉及多个对象时标题只写事件名，
     * 对象名清单下沉到明细（标题太长会在窄屏被截断，而对象清单本就是「展开信息」）。
     */
    private AdminUserBehaviorTimelineResponse.Item toItem(
            BehaviorTimelineMerger.Burst burst, int seq,
            Map<UserBehaviorEvent.RefKind, Map<Long, String>> namesByKind) {
        UserBehaviorEvent event = burst.event();
        BehaviorTimelineMerger.Raw latest = burst.latest();
        int count = burst.count();

        // 批内去重后的对象（保持「最近优先」的出现序）与明细文案
        Map<Long, String> targets = new LinkedHashMap<>();
        Set<String> details = new LinkedHashSet<>();
        for (BehaviorTimelineMerger.Raw raw : burst.members()) {
            if (raw.refId() != null && event.refKind() != UserBehaviorEvent.RefKind.NONE) {
                targets.putIfAbsent(raw.refId(), targetOf(event, raw.refId(), namesByKind));
            }
            String detail = refNames.dictionary(event.detailDict(), raw.detail());
            if (!detail.isBlank()) {
                details.add(detail);
            }
        }

        String title;
        String detail;
        String refKind = null;
        Long refId = null;
        if (count == 1) {
            String target = targets.isEmpty() ? "" : targets.values().iterator().next();
            title = composeTitle(event, target);
            detail = String.join(" · ", details);
        } else if (targets.size() == 1) {
            title = composeTitle(event, targets.values().iterator().next()) + " ×" + count;
            detail = String.join(" · ", details);
        } else {
            title = event.label() + " ×" + count;
            detail = joinTargets(targets.values(), details);
        }
        if (targets.size() == 1) {
            refKind = event.refKind().name();
            refId = targets.keySet().iterator().next();
        }

        return new AdminUserBehaviorTimelineResponse.Item(
                event.code() + "#" + seq,
                event.code(),
                event.nature().name(),
                event.nature().label(),
                event.isRetired(),
                title,
                detail,
                latest.day(),
                latest.happenedAt(),
                latest.timeApprox(),
                count,
                count > 1 ? burst.earliest().happenedAt() : null,
                refKind,
                refId);
    }

    /** 合并行的明细：对象名清单（最多 {@value #MAX_TARGET_NAMES} 个，其余折叠为「等 N 个」）+ 明细字典值 */
    private static String joinTargets(Collection<String> names, Set<String> details) {
        List<String> shown = names.stream().filter(n -> !n.isBlank()).limit(MAX_TARGET_NAMES).toList();
        String targetPart = String.join("、", shown);
        if (names.size() > MAX_TARGET_NAMES) {
            targetPart += " 等 " + names.size() + " 个";
        }
        List<String> parts = new ArrayList<>();
        if (!targetPart.isBlank()) {
            parts.add(targetPart);
        }
        parts.addAll(details);
        return String.join(" · ", parts);
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
        User user = requireUser(userId);
        int window = clamp(days, MIN_DAYS, MAX_DAYS, DEFAULT_DAYS);
        LocalDate sinceDay = sinceDay(window);
        LocalDate today = LocalDate.now();

        List<UserBehaviorRepository.UserEventRow> rows =
                behaviorRepository.listUserEvents(userId, sinceDay);

        Set<LocalDate> activeDays = new HashSet<>();
        Set<LocalDate> openDays = new HashSet<>();
        Set<LocalDate> extendedDays = new HashSet<>();
        long eventTotal = 0;
        long activeEventTotal = 0;
        long extendedEventTotal = 0;
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
            if (event.nature() == UserBehaviorEvent.Nature.EXTENDED) {
                // 扩展行为不进任何「活跃」累加器（时段 / 近 7 日 / 首末次均只认主动行为），
                // 单独计数供页面并排展示——「活跃为 0 但有扩展行为」是真实使用，不是巡检号
                extendedDays.add(row.getEventDay());
                extendedEventTotal += cnt;
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
                            event.code(), event.label(), event.category().name(), event.category().label(),
                            event.nature().name(), event.nature().label(), event.isRetired(),
                            counter.count, counter.days.size(), counter.lastAt);
                })
                .sorted((a, b) -> Long.compare(b.count(), a.count()))
                .toList();

        // 分层与平台「行为分析」页共用同一分类器；可用天数 = min(窗口, 注册至今天数 + 1)
        long availableDays = availableDays(user, window, today);
        BehaviorSegment segment = BehaviorSegment.classify(
                availableDays, activeDays.size(), openDays.size(), extendedDays.size());

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
                extendedEventTotal,
                extendedDays.size(),
                availableDays,
                new AdminUserBehaviorProfileResponse.Segment(
                        segment.name(), segment.label(), segment.hint()),
                breakdown,
                boxed(hourly));
    }

    // ── 内部工具 ──────────────────────────────────────────────────────────────

    private User requireUser(Long userId) {
        return userRepository.findByIdAndDeletedFalse(userId)
                .orElseThrow(() -> new BusinessException(1004, "用户不存在"));
    }

    /** 窗口内可用天数：注册早于窗口起点取整窗口，否则取「注册日至今」（含两端）；无注册时间按整窗口 */
    static long availableDays(User user, int window, LocalDate today) {
        if (user.getCreatedAt() == null) {
            return window;
        }
        long sinceJoin = ChronoUnit.DAYS.between(user.getCreatedAt().toLocalDate(), today) + 1L;
        return Math.max(1L, Math.min(window, sinceJoin));
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
     * 筛选 chips 的目录：<b>全部现役事件</b>下发，每个事件带窗口内计数——0 条的类型也保留，
     * 让运营能一眼看到「有这一类行为，但这个用户没做过」，而不是以为系统漏了。
     * <p>
     * <b>下线功能（舞伴 / 招工）例外</b>：窗口内 0 条就不下发。它们恒为 0，留在 chips 里只会让运营
     * 以为「系统还有这个功能、只是这个用户没用过」；窗口内有历史记录时照常下发并带 {@code retired}，
     * 保证取证时仍能按类型筛出那几天的行为（事实不因功能下线而消失）。
     */
    private static List<AdminUserBehaviorTimelineResponse.TypeOption> typeOptions(
            Map<String, TypeCounter> counters) {
        return UserBehaviorEvent.all().stream()
                .filter(event -> !event.isRetired() || countOf(counters, event.code()) > 0)
                .map(event -> new AdminUserBehaviorTimelineResponse.TypeOption(
                        event.code(), event.label(),
                        event.category().name(), event.category().label(),
                        event.nature().name(), event.nature().label(), event.nature().hint(),
                        event.isRetired(),
                        event.retiredNote() == null ? "" : event.retiredNote(),
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
            case BULLETIN -> BehaviorRefNameResolver.BULLETIN_FALLBACK;
            case CROWD_REPORT -> BehaviorRefNameResolver.CROWD_REPORT_FALLBACK;
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
