package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorProfileResponse;
import org.quwuting.quwutingservice.user.dto.response.AdminUserBehaviorTimelineResponse;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;
import org.quwuting.quwutingservice.user.repository.UserBehaviorRepository;
import org.quwuting.quwutingservice.user.repository.UserRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 单用户行为服务（轨迹合并 / 下线事件展示 / 扩展行为画像 / 分层）的口径单测（2026-10-09）。
 * <p>
 * 纯 Mockito，不连库——SQL 本身由 {@code UserBehaviorCatalogMirrorTest} 门禁 + 真库只读验证覆盖，
 * 本类只守『拿到事实行之后，服务层怎么组装』这一层。
 */
class AdminUserBehaviorServiceTest {

    private static final LocalDateTime T0 = LocalDateTime.now().withNano(0).minusHours(1);
    private static final long UID = 7L;

    private UserRepository userRepository;
    private UserBehaviorRepository behaviorRepository;
    private BehaviorRefNameResolver refNames;
    private AdminUserBehaviorService service;

    @BeforeEach
    void setUp() {
        userRepository = mock(UserRepository.class);
        behaviorRepository = mock(UserBehaviorRepository.class);
        refNames = mock(BehaviorRefNameResolver.class);
        service = new AdminUserBehaviorService(userRepository, behaviorRepository, refNames);

        User user = new User();
        user.setCreatedAt(LocalDateTime.now().minusDays(60));
        when(userRepository.findByIdAndDeletedFalse(UID)).thenReturn(Optional.of(user));
        // 字典与取名：测试里只关心组装，取名直接按 id 造名字
        when(refNames.dictionary(any(), any())).thenAnswer(inv -> {
            String raw = inv.getArgument(1);
            return raw == null ? "" : "字典:" + raw;
        });
        when(refNames.names(any(), anyCollection())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(1);
            return ids.stream().collect(Collectors.toMap(id -> id, id -> "对象" + id, (a, b) -> a));
        });
    }

    // ── 夹具 ──────────────────────────────────────────────────────────────

    private static UserBehaviorRepository.TimelineRow row(UserBehaviorEvent event, LocalDateTime at,
                                                          Long refId, String detail) {
        UserBehaviorRepository.TimelineRow r = mock(UserBehaviorRepository.TimelineRow.class);
        when(r.getEventType()).thenReturn(event.code());
        when(r.getEventDay()).thenReturn(at.toLocalDate());
        when(r.getEventTime()).thenReturn(at);
        when(r.getHappenedAt()).thenReturn(at);
        when(r.getRefId()).thenReturn(refId);
        when(r.getDetailText()).thenReturn(detail);
        return r;
    }

    private static UserBehaviorRepository.UserEventRow agg(UserBehaviorEvent event, LocalDate day,
                                                           Integer hour, long cnt) {
        UserBehaviorRepository.UserEventRow r = mock(UserBehaviorRepository.UserEventRow.class);
        when(r.getEventType()).thenReturn(event.code());
        when(r.getEventDay()).thenReturn(day);
        when(r.getHour()).thenReturn(hour);
        when(r.getCnt()).thenReturn(cnt);
        when(r.getLastAt()).thenReturn(day.atTime(hour == null ? 0 : hour, 0));
        return r;
    }

    private void stubTimeline(List<UserBehaviorRepository.TimelineRow> rows,
                              List<UserBehaviorRepository.UserEventRow> counts) {
        when(behaviorRepository.listTimeline(eq(UID), any(), any(), anyInt())).thenReturn(rows);
        when(behaviorRepository.listUserEvents(eq(UID), any())).thenReturn(counts);
    }

    // ── 轨迹合并 ──────────────────────────────────────────────────────────

    @Test
    void feedLoadBurstBecomesOneRowWithTargetNamesInDetail() {
        List<UserBehaviorRepository.TimelineRow> rows = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            rows.add(row(UserBehaviorEvent.BULLETIN_VIEW, T0.minusSeconds(i), 100L + i, null));
        }
        stubTimeline(rows, List.of(agg(UserBehaviorEvent.BULLETIN_VIEW, T0.toLocalDate(), 9, 7)));

        AdminUserBehaviorTimelineResponse resp = service.timeline(UID, 30, "", 50);

        assertEquals(1, resp.events().size(), "一次打开快讯页写入的 7 行必须合并成一行");
        AdminUserBehaviorTimelineResponse.Item item = resp.events().getFirst();
        assertEquals(7, item.count());
        assertEquals("浏览快讯 ×7", item.title(), "多个对象时标题只写事件名 + ×N，对象名下沉到明细");
        assertTrue(item.detail().contains("对象100") && item.detail().contains("等 7 个"),
                "明细应列出前几个对象并折叠其余：" + item.detail());
        assertNull(item.refKind(), "多对象的合并行不下发深链（点哪个都不对）");
        assertEquals(T0.minusSeconds(6), item.timeFrom());
        assertEquals(7, resp.shownEvents());
        assertFalse(resp.truncated());
        assertEquals("EXTENDED", item.nature());
    }

    @Test
    void sameTargetRepeatedKeepsNameAndLinks() {
        stubTimeline(List.of(
                        row(UserBehaviorEvent.VENUE_VIEW, T0, 42L, null),
                        row(UserBehaviorEvent.VENUE_VIEW, T0.minusSeconds(30), 42L, null),
                        row(UserBehaviorEvent.VENUE_VIEW, T0.minusSeconds(60), 42L, null)),
                List.of(agg(UserBehaviorEvent.VENUE_VIEW, T0.toLocalDate(), 9, 3)));

        AdminUserBehaviorTimelineResponse.Item item = service.timeline(UID, 30, "", 50).events().getFirst();

        assertEquals("浏览门店「对象42」 ×3", item.title());
        assertEquals("VENUE", item.refKind(), "唯一对象的行下发深链类型");
        assertEquals(42L, item.refId());
    }

    @Test
    void singleEventKeepsLegacyShapeAndDictionaryDetail() {
        stubTimeline(List.of(row(UserBehaviorEvent.VENUE_SHARE, T0, 5L, "BUTTON")),
                List.of(agg(UserBehaviorEvent.VENUE_SHARE, T0.toLocalDate(), 9, 1)));

        AdminUserBehaviorTimelineResponse.Item item = service.timeline(UID, 30, "", 50).events().getFirst();

        assertEquals("分享门店「对象5」", item.title(), "单条行与旧版逐字一致（无 ×N）");
        assertEquals("字典:BUTTON", item.detail());
        assertEquals(1, item.count());
        assertNull(item.timeFrom());
        assertEquals("ACTIVE", item.nature());
        assertFalse(item.retired());
    }

    @Test
    void truncationIsMeasuredInRawEventsNotRows() {
        // 窗口内共 30 条浏览，只取到最近 10 条（合并成 1 行）⇒ 仍是『被截断』，且必须以原始事件数交代
        List<UserBehaviorRepository.TimelineRow> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row(UserBehaviorEvent.VENUE_VIEW, T0.minusSeconds(i), 1L + i, null));
        }
        stubTimeline(rows, List.of(agg(UserBehaviorEvent.VENUE_VIEW, T0.toLocalDate(), 9, 30)));

        AdminUserBehaviorTimelineResponse resp = service.timeline(UID, 30, "", 20);

        assertEquals(30, resp.total());
        assertEquals(10, resp.shownEvents());
        assertTrue(resp.truncated(), "合并后行数 < 上限不代表没被截断，必须按原始事件数判断");
    }

    // ── 下线事件的展示 ────────────────────────────────────────────────────

    @Test
    void retiredTypesAreHiddenWhenEmptyAndShownWhenTheyHaveHistory() {
        stubTimeline(List.of(), List.of(agg(UserBehaviorEvent.VENUE_VIEW, T0.toLocalDate(), 9, 2)));
        Set<String> without = codes(service.timeline(UID, 30, "", 50));
        assertFalse(without.contains("DANCER_VIEW") || without.contains("DANCER_DEMAND")
                        || without.contains("RECRUITMENT_CONTACT"),
                "窗口内 0 条的下线功能不得出现在筛选选项里（恒为 0 的噪音）");
        assertTrue(without.contains("BULLETIN_VIEW") && without.contains("SPEND_ENTRY"),
                "现役类型（含新补录的扩展行为）即使 0 条也保留，避免『系统漏了』的错觉");

        stubTimeline(List.of(), List.of(
                agg(UserBehaviorEvent.DANCER_VIEW, T0.toLocalDate().minusDays(20), 20, 4)));
        AdminUserBehaviorTimelineResponse with = service.timeline(UID, 30, "", 50);
        AdminUserBehaviorTimelineResponse.TypeOption dancer = with.typeOptions().stream()
                .filter(o -> o.code().equals("DANCER_VIEW")).findFirst().orElseThrow();
        assertTrue(dancer.retired());
        assertEquals(4, dancer.count(), "有历史数据的下线类型照常展示，事实不因功能下线而消失");
        assertFalse(dancer.retiredNote().isBlank());
    }

    private static Set<String> codes(AdminUserBehaviorTimelineResponse resp) {
        return resp.typeOptions().stream()
                .map(AdminUserBehaviorTimelineResponse.TypeOption::code).collect(Collectors.toSet());
    }

    // ── 画像：扩展行为与分层 ───────────────────────────────────────────────

    @Test
    void extendedBehaviorIsCountedSeparatelyAndNeverAsActive() {
        LocalDate today = LocalDate.now();
        // 先造夹具再 stub：Mockito 不允许在 when(...) 内部再创建带 stub 的 mock（UnfinishedStubbing）
        List<UserBehaviorRepository.UserEventRow> events = List.of(
                agg(UserBehaviorEvent.CHECKIN, today, 9, 1),
                agg(UserBehaviorEvent.CHECKIN, today.minusDays(1), 9, 1),
                agg(UserBehaviorEvent.BULLETIN_VIEW, today, 9, 6),
                agg(UserBehaviorEvent.SPEND_ENTRY, today.minusDays(2), 21, 2));
        when(behaviorRepository.listUserEvents(eq(UID), any())).thenReturn(events);

        AdminUserBehaviorProfileResponse p = service.profile(UID, 30);

        assertEquals(0, p.activeDays(), "扩展行为不得计入活跃天数");
        assertEquals(0, p.activeEventTotal());
        assertEquals(8, p.extendedEventTotal());
        assertEquals(2, p.extendedDays());
        assertEquals(2, p.openDays());
        assertEquals(10, p.eventTotal(), "eventTotal 含全部口径档");
        assertEquals("EXTENDED_ONLY", p.segment().code(),
                "活跃为 0 但在用快讯 / 记账的用户必须落『仅用扩展功能』，而不是『仅打开无行为』（巡检形态）");
        assertTrue(p.hourly().stream().allMatch(v -> v == 0), "活跃时段只统计主动行为，扩展行为不入图");
        Map<String, AdminUserBehaviorProfileResponse.TypeCount> byCode = p.breakdown().stream()
                .collect(Collectors.toMap(AdminUserBehaviorProfileResponse.TypeCount::code, t -> t));
        assertEquals("NEWS", byCode.get("BULLETIN_VIEW").category());
        assertFalse(byCode.get("BULLETIN_VIEW").retired());
    }

    @Test
    void segmentUsesAvailableDaysForNewcomers() {
        User fresh = new User();
        fresh.setCreatedAt(LocalDateTime.now().minusDays(1));
        when(userRepository.findByIdAndDeletedFalse(99L)).thenReturn(Optional.of(fresh));
        LocalDate today = LocalDate.now();
        List<UserBehaviorRepository.UserEventRow> events = List.of(
                agg(UserBehaviorEvent.VENUE_VIEW, today, 9, 5),
                agg(UserBehaviorEvent.VENUE_VIEW, today.minusDays(1), 9, 5));
        when(behaviorRepository.listUserEvents(eq(99L), any())).thenReturn(events);

        AdminUserBehaviorProfileResponse p = service.profile(99L, 30);

        assertEquals(2, p.availableDays(), "注册 1 天前 ⇒ 可用 2 天（含注册当日与今天）");
        assertEquals("NEW", p.segment().code(), "观测期不足的新注册用户不参与比例分档");
        assertEquals(30, p.days());
    }

    @Test
    void availableDaysIsCappedByWindow() {
        User old = new User();
        old.setCreatedAt(LocalDateTime.now().minusDays(400));
        assertEquals(30, AdminUserBehaviorService.availableDays(old, 30, LocalDate.now()));
        User none = new User();
        assertEquals(30, AdminUserBehaviorService.availableDays(none, 30, LocalDate.now()),
                "无注册时间按整窗口计，不得伪造成 0 天");
    }
}
