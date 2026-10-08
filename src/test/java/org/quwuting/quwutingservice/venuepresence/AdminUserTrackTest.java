package org.quwuting.quwutingservice.venuepresence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuepresence.dto.response.AdminUserTrackResponse;
import org.quwuting.quwutingservice.venuepresence.enums.PresenceTrackGrade;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresenceConsentRepository;
import org.quwuting.quwutingservice.venuepresence.repository.VenuePresencePingRepository;
import org.quwuting.quwutingservice.venuepresence.service.VenuePresenceService;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * admin 用户「位置轨迹」接口（2026-10-08 V46，52 号 / GET /admin/users/{id}/track）。
 * <p>
 * 守四条不变量：① 分级 = 距离带（边界 150/300 精确）；② 点按时间升序（绘制序）、
 * 取数方向倒序（截断保留最近）；③ 无坐标行显式计数、不进点集也不静默消失；
 * ④ 窗口钳制（1~90）与未知用户短路（1004，不触达 ping / venue 仓储）。
 */
@ExtendWith(MockitoExtension.class)
class AdminUserTrackTest {

    private static final long USER_ID = 210L;
    private static final long LISA = 121L;
    private static final long YIHU = 120L;

    @Mock
    private VenuePresencePingRepository pingRepository;
    @Mock
    private VenuePresenceConsentRepository consentRepository;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private OpsConfigService opsConfigService;

    @InjectMocks
    private VenuePresenceService service;

    @Test
    void pointsAscendByTimeAndGradeFollowsDistanceBands() {
        givenUserExists();
        LocalDateTime t1 = LocalDateTime.now().minusHours(3);
        LocalDateTime t2 = LocalDateTime.now().minusHours(2);
        LocalDateTime t3 = LocalDateTime.now().minusHours(1);
        // 取数 = 倒序（最近在前）；坐标行照常返回
        when(pingRepository.findTrackByUserIdSince(eq(USER_ID), any(), any())).thenReturn(List.of(
                row(LISA, 450, t3, 32.04, 120.87),
                row(LISA, 295, t2, 32.03, 120.86),
                row(LISA, 88, t1, 32.03, 120.86)));
        givenVenues(List.of());

        AdminUserTrackResponse res = service.trackFor(USER_ID, 7);

        assertEquals(3, res.points().size());
        // 升序（绘制序）：最早在前
        assertEquals(t1, res.points().get(0).at());
        assertEquals(t3, res.points().get(2).at());
        assertEquals("HIT", res.points().get(0).grade());
        assertEquals("命中", res.points().get(0).gradeDisplay());
        assertEquals("NEARBY", res.points().get(1).grade());
        assertEquals("附近", res.points().get(1).gradeDisplay());
        assertEquals("FAR", res.points().get(2).grade());
        assertEquals("留痕", res.points().get(2).gradeDisplay());
    }

    @Test
    void gradeBandBoundariesAreExact() {
        assertEquals(PresenceTrackGrade.HIT, PresenceTrackGrade.ofDistance(150), "150 在命中带内（闭区间）");
        assertEquals(PresenceTrackGrade.NEARBY, PresenceTrackGrade.ofDistance(151));
        assertEquals(PresenceTrackGrade.NEARBY, PresenceTrackGrade.ofDistance(300), "300 在附近带内");
        assertEquals(PresenceTrackGrade.FAR, PresenceTrackGrade.ofDistance(301));
    }

    @Test
    void rowsWithoutCoordinatesAreCountedButNotPlotted() {
        givenUserExists();
        when(pingRepository.findTrackByUserIdSince(eq(USER_ID), any(), any())).thenReturn(List.of(
                row(YIHU, 92, LocalDateTime.now().minusDays(1), null, null),
                row(LISA, 88, LocalDateTime.now().minusDays(2), 32.03, 120.86)));
        givenVenues(List.of());

        AdminUserTrackResponse res = service.trackFor(USER_ID, 7);

        assertEquals(1, res.pointsWithoutCoordinates(), "无坐标旧记录必须显式计数（⛔ 禁静默丢）");
        assertEquals(1, res.points().size());
        assertEquals(Long.valueOf(LISA), res.points().get(0).venueId());
        assertEquals(1, res.venues().size(), "无坐标行不能被计入门店集合");
    }

    @Test
    void windowIsClampedAndEchoed() {
        givenUserExists();
        when(pingRepository.findTrackByUserIdSince(eq(USER_ID), any(), any())).thenReturn(List.of());
        ArgumentCaptor<LocalDateTime> since = ArgumentCaptor.forClass(LocalDateTime.class);

        AdminUserTrackResponse small = service.trackFor(USER_ID, 0);
        AdminUserTrackResponse huge = service.trackFor(USER_ID, 365);

        assertEquals(1, small.windowDays(), "下界钳 1");
        assertEquals(90, huge.windowDays(), "上界钳 90");
        verify(pingRepository, times(2)).findTrackByUserIdSince(eq(USER_ID), since.capture(), any());
        LocalDateTime lastSince = since.getAllValues().get(1);
        assertTrue(lastSince.isBefore(LocalDateTime.now().minusDays(89)));
        assertTrue(lastSince.isAfter(LocalDateTime.now().minusDays(91)));
        // 空结果：不触达 venue 仓储（空集合短路——原生 IN () 是语法错误）
        verify(venueRepository, never()).findByIdInAndDeletedFalse(any());
    }

    @Test
    void truncationKeepsMostRecentPointsAndIsExplicit() {
        givenUserExists();
        LocalDateTime base = LocalDateTime.now().minusDays(3);
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < 801; i++) {
            // i=0 最新（倒序入参）；时间基准固定，便于断言「保留的是最近 800 条」
            rows.add(row(LISA, 100, base.plusMinutes(10L * (800 - i)), 32.03, 120.86));
        }
        when(pingRepository.findTrackByUserIdSince(eq(USER_ID), any(), any())).thenReturn(rows);
        givenVenues(List.of());
        ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);

        AdminUserTrackResponse res = service.trackFor(USER_ID, 7);

        assertTrue(res.truncated(), "超限必须显式标记（⛔ 禁静默截断）");
        assertEquals(800, res.points().size());
        // 升序输出：首 = 被保留集中最早（i=799），末 = 最新（i=0）
        assertEquals(base.plusMinutes(10), res.points().get(0).at());
        assertEquals(base.plusMinutes(8000), res.points().get(799).at(), "保留的必须是最近的 800 条");
        verify(pingRepository).findTrackByUserIdSince(eq(USER_ID), any(), page.capture());
        assertEquals(801, page.getValue().getPageSize(), "上限 + 1：多取一条只用于判超限");
    }

    @Test
    void unknownUserFailsBeforeTouchingPingOrVenueRepositories() {
        when(userRepository.findByIdAndDeletedFalse(USER_ID)).thenReturn(Optional.empty());

        assertThrows(BusinessException.class, () -> service.trackFor(USER_ID, 7));
        verify(pingRepository, never()).findTrackByUserIdSince(any(), any(), any());
        verify(venueRepository, never()).findByIdInAndDeletedFalse(any());
    }

    @Test
    void venuesAreDedupedAndMissingVenueDegradesGracefully() {
        givenUserExists();
        when(pingRepository.findTrackByUserIdSince(eq(USER_ID), any(), any())).thenReturn(List.of(
                row(LISA, 88, LocalDateTime.now().minusHours(1), 32.03, 120.86),
                row(YIHU, 95, LocalDateTime.now().minusHours(2), 32.03, 120.86),
                row(999L, 88, LocalDateTime.now().minusHours(3), 32.03, 120.86)));
        givenVenues(List.of(
                venue(LISA, "丽莎歌舞厅", VenueStatus.OPEN),
                venue(YIHU, "一壶淡泊音乐酒吧", VenueStatus.SUSPENDED)));

        AdminUserTrackResponse res = service.trackFor(USER_ID, 7);

        assertEquals(3, res.venues().size());
        AdminUserTrackResponse.TrackVenue missing = res.venues().stream()
                .filter(v -> v.id() == 999L).findFirst().orElseThrow();
        assertNull(missing.name(), "门店缺失（已软删）时降级为 null 名，前端回退「门店 #id」");
        AdminUserTrackResponse.TrackVenue lisa = res.venues().stream()
                .filter(v -> v.id() == LISA).findFirst().orElseThrow();
        assertEquals("丽莎歌舞厅", lisa.name());
        assertEquals(VenueStatus.OPEN.getDisplayName(), lisa.statusDisplay());
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private void givenUserExists() {
        User u = new User();
        u.setId(USER_ID);
        when(userRepository.findByIdAndDeletedFalse(USER_ID)).thenReturn(Optional.of(u));
    }

    private void givenVenues(List<Venue> venues) {
        when(venueRepository.findByIdInAndDeletedFalse(any())).thenReturn(venues);
    }

    /** 原始行形态 = findTrackByUserIdSince 的投影：{id, venueId, distanceM, accuracyM, lat, lng, createdAt} */
    private static Object[] row(Long venueId, int distance, LocalDateTime at, Double lat, Double lng) {
        return new Object[]{1L, venueId, distance, 30, lat, lng, at};
    }

    private static Venue venue(long id, String name, VenueStatus status) {
        Venue v = new Venue();
        v.setId(id);
        v.setName(name);
        v.setStatus(status);
        return v;
    }
}
