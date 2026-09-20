package org.quwuting.quwutingservice.venueactivity.service;

import lombok.RequiredArgsConstructor;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityStateView;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityWindow;
import org.quwuting.quwutingservice.venueactivity.dto.request.ActivityRequest;
import org.quwuting.quwutingservice.venueactivity.dto.response.AdminVenueActivityResponse;
import org.quwuting.quwutingservice.venueactivity.dto.response.VenueActivityBadgeResponse;
import org.quwuting.quwutingservice.venueactivity.dto.response.VenueActivityResponse;
import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.entity.VenueActivityCheckin;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityState;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityStatus;
import org.quwuting.quwutingservice.venueactivity.repository.VenueActivityCheckinRepository;
import org.quwuting.quwutingservice.venueactivity.repository.VenueActivityRepository;
import org.quwuting.quwutingservice.venueactivity.support.ActivityWindows;
import org.quwuting.quwutingservice.venueshare.enums.ShareEventType;
import org.quwuting.quwutingservice.venueshare.repository.VenueShareRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 门店营业活动服务（域设计见 docs/agents/49-venue-activities.md）。
 * <p>
 * <b>三条不变量</b>（改动前先读这三条）：
 * <ol>
 *   <li><b>只 admin 直发</b>：门店老板没有发布权。活动是带优惠承诺的经营信息，
 *       兑现不了投诉落小程序主体，且行业宣传语擦边概率高——老板发海报给运营、
 *       运营审核结构化后录入是唯一通道。</li>
 *   <li><b>时效只有一个权威</b>：{@code status} 由本服务的 30s 调度维护，
 *       查询只认状态；"此刻是否命中"由 {@link ActivityStateResolver} 派生。
 *       任何地方都不许"查询时顺手把过期活动过滤掉"——那是第二份时效真值。</li>
 *   <li><b>打卡不进热度</b>：打卡只服务"与门店对账"，绝不参与热度公式
 *       （热度四问判据的"难伪造"关过不了，见实体注释）。</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
public class VenueActivityService {

    private static final Logger log = LoggerFactory.getLogger(VenueActivityService.class);

    private static final int MAX_PAGE_SIZE = 50;

    /** 错误码：活动不存在 / 已下线（沿用 1001「场所不存在」的同一段位） */
    private static final int CODE_ACTIVITY_NOT_FOUND = 1033;

    /** 错误码：活动参数非法（有效期缺失、日期区间颠倒等） */
    private static final int CODE_ACTIVITY_INVALID = 1034;

    private final VenueActivityRepository activityRepository;
    private final VenueActivityCheckinRepository checkinRepository;
    private final VenueRepository venueRepository;
    private final ActivityStateResolver stateResolver;
    private final ActivityWindows activityWindows;
    /**
     * 分享事件日志（只读用途，2026-09-16，V28）：管理端列表的 shareCount / openCount。
     * <p>
     * ⚠️ 这是活动域**唯一一处跨域读取**（venueactivity → venueshare），刻意保留为"只读计数"：
     * 活动的传播链天然分散在两张表（事件在 venueshare、到店在 venueactivity），
     * 让活动域反向持有事件写入权才会造成真耦合；这里只查不回写，且仅服务于 admin。
     */
    private final VenueShareRepository venueShareRepository;

    /**
     * 管理端一个活动的四个计数，打包传递。
     * <p>
     * 为什么打包而不是给 {@code toAdminResponse} 加四个 long 形参：四个 long 连排
     * 意味着"把 openCount 传成 shareCount"会静默编译通过、只体现为后台一个错数字，
     * 而这类错误在评审里几乎不可能被发现。
     */
    private record ActivityCounters(long checkinToday, long checkinTotal,
                                    long shareCount, long openCount) {

        static final ActivityCounters ZERO = new ActivityCounters(0L, 0L, 0L, 0L);
    }

    // ── 用户端 ────────────────────────────────────────────────

    /**
     * 门店详情页数据源：该门店当前对用户可见的全部活动。
     * <p>
     * 只认 {@code PUBLISHED} 状态，**不过滤时效**——结束态由调度强转，预热态
     * （NOT_STARTED）与今日已过场态（ENDED_TODAY）都要下发给前端，
     * 前者用于"双节活动预告"，后者用于告诉用户"还有明天"。
     */
    @Transactional(readOnly = true)
    public List<VenueActivityResponse> listForVenue(Long venueId) {
        LocalDateTime now = LocalDateTime.now();
        Long userId = UserContext.getCurrentUserId();
        LocalDate today = now.toLocalDate();
        Set<Long> checkedIds = userId == null
                ? Set.of()
                : checkedActivityIds(activityIdsOf(venueId), userId, today);

        List<VenueActivityResponse> result = new ArrayList<>();
        for (VenueActivity activity : activityRepository.findPublishedByVenue(venueId, ActivityStatus.PUBLISHED)) {
            ActivityStateView view = stateResolver.resolve(activity, now);
            // 已彻底过期的残留（调度尚未跑到）不下发，避免出现"永远无效"的卡片
            if (view.state() == ActivityState.ENDED_TODAY && view.nextChangeAt() == null) {
                continue;
            }
            result.add(toResponse(activity, view, userId, checkedIds.contains(activity.getId())));
        }
        return result;
    }

    /**
     * 门店**列表页**批量打标：传入一批 venueId，返回
     * venueId → {@link VenueActivityBadgeResponse} **列表**（**在该店日期范围内的活动**都出现）。
     * <p>
     * <b>下发条件（2026-09-16 口径修订）</b>：活动在**当前日期范围内**就下发，
     * **不再要求"此刻正好命中时段"**——与门店营业状态徽标同源：
     * 「营业中 / 未到营业时间 → X HH:mm 开门」，状态要在，只是**语气降级**。
     * 旧口径（只在命中时出现）会让列表页在一天中的大部分时间里对"这家有活动"完全失声，
     * 而用户真正需要的是"现在没有的话，什么时候有"。
     * <p>
     * 唯一不下发的情形 = **已彻底过期**（有效期结束且无下一次）——那不是降级，
     * 是这条活动已经不存在（正常路径由 30s 调度强转 OFFLINE，此处仅防御）。
     * <p>
     * <b>为什么是"列表"而不是"一条"（2026-09-20 改）</b>：列表页那一行已能轮播多条
     * （同「最新上报」信号行的节奏）。只挑一条等于让第 2 条以后的活动在列表上**根本没有
     * 出口**——用户只能在点进详情页之后才知道有两条。服务端负责**排序**（
     * {@link #compareBadgePriority}，索引 0 = 最该先被看到的那条），客户端负责节奏，
     * 判定权威仍只此一处。
     * <p>
     * 为什么是独立接口而不是塞进门店列表响应：门店列表响应带 30s 公共字段缓存，
     * 而"此刻命中 / 下一次何时到"是**秒级**事实，混在一起会出现"缓存里说进行中、
     * 点进去已结束"；且活动是其他域的数据，挂在门店 DTO 上会让 04/05 号域文档
     * 被迫承载活动口径（跨域耦合）。
     */
    @Transactional(readOnly = true)
    public Map<Long, List<VenueActivityBadgeResponse>> badgeByVenueIds(Collection<Long> venueIds) {
        if (venueIds == null || venueIds.isEmpty()) {
            return Map.of();
        }
        LocalDateTime now = LocalDateTime.now();
        Map<Long, List<VenueActivityBadgeResponse>> badges = new HashMap<>();
        List<VenueActivity> activities =
                activityRepository.findPublishedByVenueIds(venueIds, ActivityStatus.PUBLISHED);
        for (VenueActivity activity : activities) {
            ActivityStateView view = stateResolver.resolve(activity, now);
            if (view.state() == ActivityState.ENDED_TODAY && view.nextChangeAt() == null) {
                continue;
            }
            badges.computeIfAbsent(activity.getVenueId(), key -> new ArrayList<>())
                    .add(new VenueActivityBadgeResponse(
                            activity.getId(),
                            activity.getBadgeLabel(),
                            view.state(),
                            view.state().getDisplayName(),
                            view.nextChangeAt()));
        }
        badges.values().forEach(list -> list.sort(this::compareBadgePriority));
        return badges;
    }

    /**
     * 同店多条活动的**列表页展示顺序**：索引 0 = 最该先被看到的那条。
     * <p>
     * 判据（2026-09-20 由"选一条"扩为"排多条"，规则本身一字未改）：
     * **"此刻命中"优先于"待会儿命中"**（前者直接回答"现在去就有"）；
     * 同为未命中则取**最近一次到来**的（与用户"什么时候去"的问法对齐）；
     * 仍并列用 activityId 兜底，保证顺序确定、不依赖查询或 Map 的顺序。
     * <p>
     * <b>⚠️ 这是"两处镜像"的第 1 处</b>：小程序端
     * {@code miniprogram/utils/venueActivity.ts} 的 {@code compareSharePriority} 是同一套
     * 规则的第二份实现（Java 与 TS 无法共享代码，与别名域 KW_MATCH 三处镜像是同一类
     * 约束）——**改这里必须同步改那里**，否则会出现"列表轮播首先讲的那条"与
     * "分享出去讲的那条"不是同一条这种最难解释的不一致。
     */
    private int compareBadgePriority(VenueActivityBadgeResponse a, VenueActivityBadgeResponse b) {
        if (a.isActive() != b.isActive()) {
            return a.isActive() ? -1 : 1;
        }
        LocalDateTime aAt = a.nextChangeAt();
        LocalDateTime bAt = b.nextChangeAt();
        // null = 不会自然变化 ⇒ 排在"有确定下次时刻"的后面（信息量更少）
        if ((aAt == null) != (bAt == null)) {
            return aAt != null ? -1 : 1;
        }
        if (aAt != null && !aAt.isEqual(bAt)) {
            return aAt.isBefore(bAt) ? -1 : 1;
        }
        return Long.compare(a.activityId(), b.activityId());
    }

    /**
     * 到店打卡（平台侧唯一自有的活动归因信源）。
     * <p>
     * <b>幂等</b>：同一人同一活动同一日重复打卡不报错、不重复计数，直接返回当前态
     * （打卡是"我到了"的声明，不是累计动作；用户连点两次不该看到错误）。
     * 并发由唯一键兜底（查询前置 + 唯一索引，同公告 dedupKey 模式）。
     * <p>
     * {@code activityDate} 由<b>服务端</b>取今天，禁客户端传入——设备时钟可改，
     * 归因数据不能建立在客户端时间上。
     */
    @Transactional
    public VenueActivityResponse checkin(Long venueId, Long activityId) {
        Long userId = UserContext.requireAuth();
        VenueActivity activity = requirePublished(venueId, activityId);

        LocalDate today = LocalDate.now();
        boolean already = checkinRepository
                .existsByActivityIdAndUserIdAndActivityDateAndDeletedFalse(activityId, userId, today);
        if (!already) {
            VenueActivityCheckin checkin = new VenueActivityCheckin();
            checkin.setActivityId(activityId);
            checkin.setVenueId(venueId);
            checkin.setUserId(userId);
            checkin.setActivityDate(today);
            try {
                checkinRepository.saveAndFlush(checkin);
            } catch (DataIntegrityViolationException e) {
                // 并发双写：唯一键兜底，视为已打卡（幂等语义，非错误）
                log.debug("[venue-activity] 打卡并发命中唯一键，按已打卡处理 activityId={} userId={}",
                        activityId, userId);
            }
        }

        ActivityStateView view = stateResolver.resolve(activity, LocalDateTime.now());
        return toResponse(activity, view, userId, true);
    }

    // ── 管理端 ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public Page<AdminVenueActivityResponse> listForAdmin(ActivityStatus status, Long venueId,
                                                         int page, int size) {
        page = Math.max(0, page);
        size = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        Page<VenueActivity> pageData = activityRepository.findPageByFilters(
                status, venueId, PageRequest.of(page, size));

        List<Long> ids = pageData.getContent().stream().map(VenueActivity::getId).toList();
        LocalDate today = LocalDate.now();
        Map<Long, String> venueNames = venueNames(pageData.getContent());
        Map<Long, ActivityCounters> counters = countersFor(ids, today);

        return pageData.map(a -> toAdminResponse(a, venueNames.get(a.getVenueId()),
                counters.getOrDefault(a.getId(), ActivityCounters.ZERO)));
    }

    @Transactional(readOnly = true)
    public AdminVenueActivityResponse getForAdmin(Long id) {
        VenueActivity activity = activityRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(CODE_ACTIVITY_NOT_FOUND, "活动不存在"));
        Map<Long, String> venueNames = venueNames(List.of(activity));
        Map<Long, ActivityCounters> counters = countersFor(List.of(id), LocalDate.now());
        return toAdminResponse(activity, venueNames.get(activity.getVenueId()),
                counters.getOrDefault(id, ActivityCounters.ZERO));
    }

    /**
     * 批量取四个计数（管理端列表/详情共用）。
     * <p>
     * 四条 GROUP BY 查询，每条一次取全部活动（不是每个活动一次）——管理端列表页是
     * 运营天天要看的页面，任何一处 N+1 都会随活动数量线性劣化。
     * <p>
     * 传播两个计数取自 {@code qwt_venue_shares}，按 eventType 分成 SHARE / OPEN 两次查询
     * （与 23 号贡献档案同一形态）；到店计数取自 {@code qwt_venue_activity_checkins}。
     * 无事件的活动不出现在 GROUP BY 结果里 ⇒ 调用方按 {@link ActivityCounters#ZERO} 兜底。
     */
    private Map<Long, ActivityCounters> countersFor(List<Long> activityIds, LocalDate today) {
        Map<Long, Long> checkinToday = toCountMap(checkinRepository.countByActivityIdsOnDate(activityIds, today));
        Map<Long, Long> checkinTotal = toCountMap(checkinRepository.countByActivityIdsTotal(activityIds));
        Map<Long, Long> shares = toCountMap(
                venueShareRepository.countGroupByActivityIdsAndEventType(activityIds, ShareEventType.SHARE));
        Map<Long, Long> opens = toCountMap(
                venueShareRepository.countGroupByActivityIdsAndEventType(activityIds, ShareEventType.OPEN));

        Map<Long, ActivityCounters> result = new HashMap<>();
        for (Long activityId : activityIds) {
            result.put(activityId, new ActivityCounters(
                    checkinToday.getOrDefault(activityId, 0L),
                    checkinTotal.getOrDefault(activityId, 0L),
                    shares.getOrDefault(activityId, 0L),
                    opens.getOrDefault(activityId, 0L)));
        }
        return result;
    }

    @Transactional
    public AdminVenueActivityResponse create(ActivityRequest request) {
        Long venueId = request.venueId();
        if (venueId == null) {
            throw new BusinessException(CODE_ACTIVITY_INVALID, "所属门店不能为空");
        }
        requireVenue(venueId);

        VenueActivity activity = new VenueActivity();
        activity.setVenueId(venueId);
        applyFields(activity, request);
        activity.setStatus(Boolean.TRUE.equals(request.publish())
                ? ActivityStatus.PUBLISHED : ActivityStatus.DRAFT);
        VenueActivity saved = activityRepository.save(activity);
        log.info("[venue-activity] 创建活动 id={} venueId={} status={}",
                saved.getId(), venueId, saved.getStatus());
        return getForAdmin(saved.getId());
    }

    @Transactional
    public AdminVenueActivityResponse update(Long id, ActivityRequest request) {
        VenueActivity activity = activityRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(CODE_ACTIVITY_NOT_FOUND, "活动不存在"));
        applyFields(activity, request);
        if (Boolean.TRUE.equals(request.publish()) && activity.getStatus() != ActivityStatus.PUBLISHED) {
            // 重新发布 = 唯一复活通道（同公告域）：不做静默状态漂移
            activity.setStatus(ActivityStatus.PUBLISHED);
        }
        activityRepository.save(activity);
        log.info("[venue-activity] 更新活动 id={} status={}", id, activity.getStatus());
        return getForAdmin(id);
    }

    /** 手动下线（运营主动撤下；有效期结束的自动下线由 30s 调度负责） */
    @Transactional
    public AdminVenueActivityResponse offline(Long id) {
        VenueActivity activity = activityRepository.findByIdAndDeletedFalse(id)
                .orElseThrow(() -> new BusinessException(CODE_ACTIVITY_NOT_FOUND, "活动不存在"));
        activity.setStatus(ActivityStatus.OFFLINE);
        activityRepository.save(activity);
        log.info("[venue-activity] 手动下线 id={}", id);
        return getForAdmin(id);
    }

    // ── 定时调度：有效期结束 → 自动下线（状态权威） ─────────────

    /**
     * 每 30s 扫一次：有效期已过的 PUBLISHED 活动 → OFFLINE。
     * <p>
     * 判据是 {@code endDate < today}（<b>结束当天仍然有效</b>），且只针对
     * {@code DATE_RANGE} 类型——{@code ALWAYS}（长期有效）不参与。刻意用 outerType
     * 枚举排除而不是判 {@code endDate IS NULL}：枚举是显式契约，判空是隐式的，
     * 后者会在业务演进时被悄悄改掉语义。
     * <p>
     * 与公告域 {@code processScheduledTransitions} 完全同款：批量 UPDATE、
     * 零业务副作用、转换数 >0 才记日志。到点后 ≤30s 内强转，
     * 用户端因此永远不会看到过期活动（"单点状态机"，不做查询时过滤）。
     */
    @Scheduled(fixedDelay = 30_000)
    @Transactional
    public void processScheduledTransitions() {
        int offlined = activityRepository.expireDue(
                ActivityStatus.PUBLISHED, ActivityStatus.OFFLINE,
                List.of(ActivityOuterSchedule.DATE_RANGE), LocalDate.now());
        if (offlined > 0) {
            log.info("[venue-activity] scheduled expire: offlined={}", offlined);
        }
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private void applyFields(VenueActivity activity, ActivityRequest request) {
        activity.setTitle(request.title().trim());
        activity.setBenefitKind(request.benefitKind());
        String badge = request.badgeLabel() == null ? null : request.badgeLabel().trim();
        activity.setBadgeLabel(badge == null || badge.isEmpty()
                ? request.benefitKind().getDefaultBadgeLabel()
                : badge);
        activity.setBenefitSummary(trimToNull(request.benefitSummary()));
        activity.setRedemptionMode(request.redemptionMode());
        activity.setRedemptionHint(trimToNull(request.redemptionHint()));
        activity.setPlatformAddon(trimToNull(request.platformAddon()));
        activity.setOuterType(request.outerType());
        activity.setSortWeight(request.sortWeight() == null ? 0 : request.sortWeight());

        LocalDate start = request.startDate();
        LocalDate end = request.endDate();
        if (request.outerType() == ActivityOuterSchedule.DATE_RANGE) {
            if (start == null && end == null) {
                throw new BusinessException(CODE_ACTIVITY_INVALID, "指定日期的活动必须填写开始或结束日期");
            }
            if (start != null && end != null && end.isBefore(start)) {
                throw new BusinessException(CODE_ACTIVITY_INVALID, "结束日期不能早于开始日期");
            }
        } else {
            // 长期有效：清掉遗留日期，避免"改了类型但旧日期还在库里"造成两套真值
            start = null;
            end = null;
        }
        activity.setStartDate(start);
        activity.setEndDate(end);

        Set<Integer> weekdays = request.weekdays() == null
                ? Set.of() : new LinkedHashSet<>(request.weekdays());
        activity.setWeekdayMask(ActivityWindows.writeWeekdayMask(weekdays));

        List<ActivityWindow> windows = request.windows() == null ? List.of() : request.windows();
        for (ActivityWindow w : windows) {
            if (w.open() == null || w.close() == null) {
                throw new BusinessException(CODE_ACTIVITY_INVALID, "生效时段必须填写开始与结束时间");
            }
        }
        List<ActivityWindow> sorted = new ArrayList<>(windows);
        sorted.sort(Comparator.comparing(ActivityWindow::open));
        activity.setWindows(activityWindows.write(sorted));
    }

    private VenueActivity requirePublished(Long venueId, Long activityId) {
        VenueActivity activity = activityRepository.findByIdAndDeletedFalse(activityId)
                .orElseThrow(() -> new BusinessException(CODE_ACTIVITY_NOT_FOUND, "活动不存在"));
        if (!activity.getVenueId().equals(venueId) || !activity.getStatus().isPublished()) {
            throw new BusinessException(CODE_ACTIVITY_NOT_FOUND, "活动不存在");
        }
        return activity;
    }

    private void requireVenue(Long venueId) {
        venueRepository.findByIdAndDeletedFalse(venueId)
                .orElseThrow(() -> new BusinessException(1001, "场所不存在"));
    }

    private List<Long> activityIdsOf(Long venueId) {
        return activityRepository.findPublishedByVenue(venueId, ActivityStatus.PUBLISHED)
                .stream().map(VenueActivity::getId).toList();
    }

    private Set<Long> checkedActivityIds(Collection<Long> activityIds, Long userId, LocalDate date) {
        Set<Long> checked = new LinkedHashSet<>();
        for (Long id : activityIds) {
            if (checkinRepository.existsByActivityIdAndUserIdAndActivityDateAndDeletedFalse(id, userId, date)) {
                checked.add(id);
            }
        }
        return checked;
    }

    private Map<Long, String> venueNames(List<VenueActivity> activities) {
        Set<Long> ids = new LinkedHashSet<>();
        activities.forEach(a -> ids.add(a.getVenueId()));
        Map<Long, String> names = new HashMap<>();
        for (Long id : ids) {
            venueRepository.findByIdAndDeletedFalse(id)
                    .map(Venue::getName)
                    .ifPresent(name -> names.put(id, name));
        }
        return names;
    }

    private Map<Long, Long> toCountMap(List<Object[]> rows) {
        Map<Long, Long> map = new HashMap<>();
        for (Object[] row : rows) {
            map.put(((Number) row[0]).longValue(), ((Number) row[1]).longValue());
        }
        return map;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private AdminVenueActivityResponse toAdminResponse(VenueActivity activity, String venueName,
                                                       ActivityCounters counters) {
        List<Integer> weekdays = new ArrayList<>(ActivityWindows.parseWeekdayMask(activity.getWeekdayMask()));
        weekdays.sort(Comparator.naturalOrder());
        return new AdminVenueActivityResponse(
                activity.getId(),
                activity.getVenueId(),
                venueName,
                activity.getTitle(),
                activity.getBenefitKind(),
                activity.getBenefitKind().getDisplayName(),
                activity.getBadgeLabel(),
                activity.getBenefitSummary(),
                activity.getRedemptionMode(),
                activity.getRedemptionMode().getDisplayName(),
                activity.getRedemptionHint(),
                activity.getPlatformAddon(),
                activity.getOuterType(),
                activity.getOuterType().getDisplayName(),
                activity.getStartDate(),
                activity.getEndDate(),
                weekdays,
                activityWindows.parse(activity.getWindows()),
                activity.getStatus(),
                activity.getStatus().getDisplayName(),
                activity.getSortWeight(),
                counters.checkinToday(),
                counters.checkinTotal(),
                counters.shareCount(),
                counters.openCount(),
                activity.getCreatedAt(),
                activity.getUpdatedAt()
        );
    }

    private VenueActivityResponse toResponse(VenueActivity activity, ActivityStateView view,
                                             Long userId, boolean checkedInToday) {
        return new VenueActivityResponse(
                activity.getId(),
                activity.getVenueId(),
                activity.getTitle(),
                activity.getBenefitKind(),
                activity.getBenefitKind().getDisplayName(),
                activity.getBadgeLabel(),
                activity.getBenefitSummary(),
                activity.getRedemptionMode(),
                activity.getRedemptionMode().getDisplayName(),
                activity.getRedemptionHint(),
                activity.getPlatformAddon(),
                activityWindows.displayText(activity.getWindows()),
                validityText(activity),
                view.state(),
                view.state().getDisplayName(),
                view.nextChangeAt(),
                // 可打卡 = 已登录 且 该活动确实能产生到店证据（核销方式可归因）
                userId != null && activity.getRedemptionMode().isAttributable(),
                checkedInToday
        );
    }

    /**
     * 有效期展示文案。刻意在服务端拼装（而不是下发原始日期让前端格式化）：
     * "9月25日-10月8日"与"长期有效"是同一件事的两种呈现，两端各写一套必然分叉。
     * 日期为分钟级精度、无年份——活动都是近期行为，带年份反而增加噪音。
     */
    private String validityText(VenueActivity activity) {
        if (activity.getOuterType() == ActivityOuterSchedule.ALWAYS) {
            return "长期有效";
        }
        LocalDate start = activity.getStartDate();
        LocalDate end = activity.getEndDate();
        if (start != null && end != null && start.isEqual(end)) {
            return fmtDate(start);
        }
        if (start != null && end != null) {
            return fmtDate(start) + " - " + fmtDate(end);
        }
        if (start != null) {
            return fmtDate(start) + " 起";
        }
        if (end != null) {
            return "至 " + fmtDate(end);
        }
        return "长期有效";
    }

    private static String fmtDate(LocalDate date) {
        return date.getMonthValue() + "月" + date.getDayOfMonth() + "日";
    }

    /** 供测试与防御路径使用：取某活动的派生态（不查库） */
    Optional<ActivityStateView> previewState(VenueActivity activity, LocalDateTime now) {
        return Optional.of(stateResolver.resolve(activity, now));
    }
}
