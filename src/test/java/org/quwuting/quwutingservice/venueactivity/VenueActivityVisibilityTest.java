package org.quwuting.quwutingservice.venueactivity;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.security.UserContext;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.venue.change.VenueChangePublisher;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venueactivity.dto.ActivityStateView;
import org.quwuting.quwutingservice.venueactivity.dto.response.VenueActivityResponse;
import org.quwuting.quwutingservice.venueactivity.entity.VenueActivity;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityBenefitKind;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityOuterSchedule;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityRedemptionMode;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityState;
import org.quwuting.quwutingservice.venueactivity.enums.ActivityStatus;
import org.quwuting.quwutingservice.venueactivity.repository.VenueActivityCheckinRepository;
import org.quwuting.quwutingservice.venueactivity.repository.VenueActivityRepository;
import org.quwuting.quwutingservice.venueactivity.service.ActivityStateResolver;
import org.quwuting.quwutingservice.venueactivity.service.VenueActivityService;
import org.quwuting.quwutingservice.venueactivity.support.ActivityWindows;
import org.quwuting.quwutingservice.venueshare.repository.VenueShareRepository;
import org.springframework.data.jpa.repository.Query;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 「门店可服务性 × 活动可见性」不变量（2026-10-08，用户拍板「非 OPEN 全静默」）。
 *
 * <p><b>背景</b>：用户报障「门店停业了，活动还在列表上滚动」（生产实例：帝豪 #122 被置
 * 暂停营业后名下 2 条在期活动照常轮播）。根因不是实现 bug，而是域里缺一条规则——
 * 活动可见性只判活动自身（PUBLISHED + 在期），从不判宿主门店的可用性。
 *
 * <p><b>锁定的不变量（四处镜像 + 两个行为）</b>：
 * <ol>
 *   <li><b>判据单点</b>：可服务 = {@code VenueStatus#isServable()}，仅 OPEN 为真
 *       （遍历全枚举断言，新增状态枚举时本测试强制重新决策）；</li>
 *   <li><b>三个查询携带门店条件</b>：{@code findPublishedByVenue} /
 *       {@code findPublishedByVenueIds}（反射读 @Query 文本）+
 *       {@code VenueRepository#ACTIVITY_PREDICATE}（常量文本）。SQL 无法调用
 *       {@code isServable()}，只能以 {@code VenueStatus.OPEN} 字面量镜像——
 *       镜像关系靠本测试锁住，漏一处即「筛出来但卡片没有行」这类静默漂移；</li>
 *   <li><b>ACTIVITY_PREDICATE 的门店条件只包在 hasActivity 分支内</b>——
 *       做成无条件过滤会让停业门店从列表整体消失，违反「列表状态不过滤」的既有口径；</li>
 *   <li><b>checkin 行为</b>：非 OPEN 门店拒绝（1037，且零写入）+ OPEN 放行
 *       （对照组，防拦截过度）。</li>
 * </ol>
 *
 * <p>零依赖：不连库、不起 Spring（Mockito + 反射/常量文本），同 FavoriteServiceTest /
 * HomeSlotContractTest 的既有形态。
 */
@ExtendWith(MockitoExtension.class)
class VenueActivityVisibilityTest {

    private static final long VENUE_ID = 122L;
    private static final long ACTIVITY_ID = 3L;
    private static final long USER_ID = 10L;

    @Mock
    private VenueActivityRepository activityRepository;
    @Mock
    private VenueActivityCheckinRepository checkinRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private ActivityStateResolver stateResolver;
    @Mock
    private ActivityWindows activityWindows;
    @Mock
    private VenueShareRepository venueShareRepository;
    @Mock
    private VenueChangePublisher venueChangePublisher;

    private VenueActivityService service;

    @BeforeEach
    void setUp() {
        service = new VenueActivityService(
                activityRepository,
                checkinRepository,
                venueRepository,
                stateResolver,
                activityWindows,
                venueShareRepository,
                venueChangePublisher
        );
    }

    @AfterEach
    void clearContext() {
        UserContext.clear();
    }

    // ── 判据单点 ─────────────────────────────────────────────────────────────

    @Test
    void isServableIsTrueOnlyForOpen() {
        for (VenueStatus s : VenueStatus.values()) {
            assertEquals(s == VenueStatus.OPEN, s.isServable(),
                    "isServable 语义锁定：仅 OPEN 可服务。新增状态枚举时必须在 "
                            + "VenueStatus#isServable 的 javadoc 处重新决策并同步四处镜像：" + s);
        }
    }

    // ── 行为：checkin 准入 ────────────────────────────────────────────────────

    @Test
    void checkinRejectsUnservableVenueWithoutWriting() {
        UserContext.set(USER_ID, UserRole.USER);
        when(activityRepository.findByIdAndDeletedFalse(ACTIVITY_ID))
                .thenReturn(Optional.of(activity()));
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID))
                .thenReturn(Optional.of(venue(VenueStatus.SUSPENDED)));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.checkin(VENUE_ID, ACTIVITY_ID));

        assertEquals(1037, e.getCode(), "门店不可服务时打卡拒绝，错误码 1037（与「活动不存在」区分；1035 已被快讯红线占用，1036 为熔断）");
        verify(checkinRepository, never()).saveAndFlush(any());
        verifyNoInteractions(stateResolver);
    }

    @Test
    void checkinStillWorksForOpenVenue() {
        UserContext.set(USER_ID, UserRole.USER);
        when(activityRepository.findByIdAndDeletedFalse(ACTIVITY_ID))
                .thenReturn(Optional.of(activity()));
        when(venueRepository.findByIdAndDeletedFalse(VENUE_ID))
                .thenReturn(Optional.of(venue(VenueStatus.OPEN)));
        when(checkinRepository.existsByActivityIdAndUserIdAndActivityDateAndDeletedFalse(
                eq(ACTIVITY_ID), eq(USER_ID), any(LocalDate.class))).thenReturn(false);
        when(stateResolver.resolve(any(VenueActivity.class), any()))
                .thenReturn(new ActivityStateView(ActivityState.ACTIVE, null, null));

        VenueActivityResponse response = service.checkin(VENUE_ID, ACTIVITY_ID);

        assertTrue(response.checkedInToday(), "OPEN 门店打卡正常走通（对照组：防拦截过度）");
        verify(checkinRepository).saveAndFlush(any());
    }

    // ── 契约：三个 SQL 查询的镜像条件 ─────────────────────────────────────────

    @Test
    void userFacingActivityQueriesCarryTheServableVenueCondition() throws Exception {
        String single = queryText("findPublishedByVenue", Long.class, ActivityStatus.class);
        String batch = queryText("findPublishedByVenueIds", Collection.class, ActivityStatus.class);
        for (String q : List.of(single, batch)) {
            assertTrue(q.contains("org.quwuting.quwutingservice.venue.enums.VenueStatus.OPEN"),
                    "活动用户端查询必须携带门店可服务条件（2026-10-08「非 OPEN 全静默」）：" + q);
            assertTrue(q.contains("v.deleted = false"),
                    "门店软删与可服务必须一同表达（用户端可见 = 门店可见 × 可服务）：" + q);
        }
    }

    @Test
    void activityFilterPredicateKeepsServableConditionInsideHasActivityBranch() {
        String predicate = VenueRepository.ACTIVITY_PREDICATE;
        assertTrue(predicate.contains(
                        ":hasActivity = false OR (v.status = org.quwuting.quwutingservice.venue.enums.VenueStatus.OPEN"),
                "门店条件必须紧随 hasActivity 分支开头（不得做成无条件过滤——那会把停业门店"
                        + "从列表整体抹掉，违反「列表状态不过滤」的既有口径）：" + predicate);
        assertTrue(predicate.contains("a.deleted = false") && predicate.contains("ActivityStatus.PUBLISHED"),
                "既有活动侧条件不得在追加门店条件时丢失（镜像纪律）：" + predicate);
    }

    // ── 夹具 / 工具 ──────────────────────────────────────────────────────────

    /** 反射读 @Query 文本（注解字符串里没有注释，不存在"剥注释"的必要）。 */
    private static String queryText(String name, Class<?>... paramTypes) throws Exception {
        Method m = VenueActivityRepository.class.getMethod(name, paramTypes);
        Query q = m.getAnnotation(Query.class);
        assertNotNull(q, name + " 必须以 @Query 显式声明（本测试锁其可见性条件）");
        return q.value();
    }

    private static VenueActivity activity() {
        VenueActivity a = new VenueActivity();
        a.setId(ACTIVITY_ID);
        a.setVenueId(VENUE_ID);
        a.setTitle("14:30 前免门票，之后 30 元/位买 1 送 1");
        a.setBenefitKind(ActivityBenefitKind.TICKET_B1G1);
        a.setBadgeLabel("买一送一");
        a.setRedemptionMode(ActivityRedemptionMode.CODE_WORD);
        a.setOuterType(ActivityOuterSchedule.ALWAYS);
        a.setStatus(ActivityStatus.PUBLISHED);
        return a;
    }

    private static Venue venue(VenueStatus status) {
        Venue v = new Venue();
        v.setId(VENUE_ID);
        v.setName("帝豪音乐酒吧");
        v.setStatus(status);
        return v;
    }
}
