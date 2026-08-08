package org.quwuting.quwutingservice.venuereaction.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venuereaction.ReactionWindow;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.repository.VenueReactionRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * VenueReactionService 徽标编排语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08-08 根因修复的核心契约（见 {@link VenueReactionService#buildTopBadgesFromCounts}
 * javadoc 与 AGENTS.md「Reaction 快速反馈系统 → 跨页一致性同步」）：
 * <ol>
 *   <li>**用户已参与的 code 不受 Top N 截断**——场景复现：venue 已有 4 个更高窗口计数的
 *       code，用户刚参与的新 code（窗口计数 1）排在第 5 位；若被截断，列表数据重取
 *       （收藏 Tab onShow 刷新 / 下拉刷新 / Skyline 列表回收）后卡片上"我参与的 chip"
 *       凭空消失，而详情页（/reactions/stats 全量）仍在——本测试断言该 code 恒被包含；</li>
 *   <li>用户已参与的 code 已在 Top N 内时**不重复追加**（幂等）；</li>
 *   <li>匿名/未参与场景保持**纯 Top N** 截断（用户豁免不泄漏给其他用户）。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VenueReactionServiceTest {

    @Mock
    private VenueReactionRepository venueReactionRepository;
    @Mock
    private VenueReactionAggregateService aggregateService;
    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private VenueHeatService venueHeatService;

    private VenueReactionService service;

    @BeforeEach
    void setUp() {
        // @PersistenceContext entityManager 为字段注入，不参与构造；batchGetBadges 路径不触碰
        service = new VenueReactionService(venueReactionRepository, aggregateService,
                venueLookupService, venueHeatService);
    }

    /** 聚合行：Object[]{venueId, code, countAll, count7d, count30d}（与原生 SQL 返回结构一致） */
    private static Object[] row(Long venueId, String code, long count) {
        return new Object[]{venueId, code, count, count, count};
    }

    /** 个人状态行：Object[]{venueId, code}（与 findTodayCodesByUserAndVenueIds 返回结构一致） */
    private static Object[] myRow(Long venueId, String code) {
        return new Object[]{venueId, code};
    }

    /**
     * 根因场景：venue 3 近7天已有 FAIR_PRICE/HIGH_COST/HOT/SWEET_PARTNER 各 2 次（Top 4 被占满），
     * 用户刚参与 GOOD_MUSIC（窗口计数 1，排第 5）。断言 GOOD_MUSIC 恒在徽标内且 reactedByMe=true——
     * 否则列表重取后"我参与的 chip"消失（2026-08-08 线上复现案例）。
     */
    @Test
    void batchGetBadges_keepsUserParticipatedCodeBelowTopN() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "FAIR_PRICE", 2L),
                        row(3L, "HIGH_COST", 2L),
                        row(3L, "HOT", 2L),
                        row(3L, "SWEET_PARTNER", 2L),
                        row(3L, "GOOD_MUSIC", 1L)));
        when(venueReactionRepository.findTodayCodesByUserAndVenueIds(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "GOOD_MUSIC")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), 2L, ReactionWindow.DAYS_7);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(5, badges.size(), "Top 4 + 用户已参与的截断线下 code");
        ReactionBadge goodMusic = badges.stream()
                .filter(b -> b.code().equals("GOOD_MUSIC")).findFirst().orElse(null);
        assertTrue(goodMusic != null, "用户刚参与的 GOOD_MUSIC 必须包含在徽标内");
        assertEquals(1L, goodMusic.count7d());
        assertTrue(goodMusic.reactedByMe(), "用户参与的 code 必须携带参与态高亮");
    }

    /** 用户已参与的 code 已在 Top N 内：不重复追加（幂等），其余条目不受影响 */
    @Test
    void batchGetBadges_doesNotDuplicateUserCodeAlreadyInTopN() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "HOT", 2L),
                        row(3L, "FAIR_PRICE", 1L)));
        when(venueReactionRepository.findTodayCodesByUserAndVenueIds(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "HOT")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), 2L, ReactionWindow.DAYS_7);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(2, badges.size(), "Top N 内的用户 code 不重复追加");
        assertEquals(1, badges.stream().filter(b -> b.code().equals("HOT")).count());
        assertTrue(badges.stream().filter(b -> b.code().equals("HOT")).findFirst().get().reactedByMe());
        assertFalse(badges.stream().filter(b -> b.code().equals("FAIR_PRICE")).findFirst().get().reactedByMe());
    }

    /** 匿名/未参与用户：保持纯 Top N 截断——用户豁免不向他人泄漏 */
    @Test
    void batchGetBadges_anonymousKeepsPureTopN() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "FAIR_PRICE", 2L),
                        row(3L, "HIGH_COST", 2L),
                        row(3L, "HOT", 2L),
                        row(3L, "SWEET_PARTNER", 2L),
                        row(3L, "GOOD_MUSIC", 1L)));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), null, ReactionWindow.DAYS_7);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(4, badges.size(), "匿名用户不携带个人豁免，保持纯 Top 4");
        assertTrue(badges.stream().noneMatch(b -> b.code().equals("GOOD_MUSIC")));
        assertTrue(badges.stream().allMatch(b -> !b.reactedByMe()));
    }

    /** 批量场所隔离：每场所各自独立按"Top N + 用户豁免"组装，互不污染（用户豁免不泄漏到其他场所） */
    @Test
    void batchGetBadges_isolatesPerVenue() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "HOT", 2L),
                        row(3L, "GOOD_MUSIC", 1L),
                        row(13L, "CLEAN", 3L),
                        row(13L, "GOOD_VIBE", 2L),
                        row(13L, "FAIR_PRICE", 2L),
                        row(13L, "HOT", 2L),
                        row(13L, "SWEET_PARTNER", 2L),
                        row(13L, "QUIET", 1L)));
        when(venueReactionRepository.findTodayCodesByUserAndVenueIds(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "GOOD_MUSIC")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L, 13L), 2L, ReactionWindow.DAYS_7);

        // venue 3：HOT（Top 1）+ GOOD_MUSIC（用户豁免，截断线下追加）
        assertEquals(2, result.get(3L).size());
        assertTrue(result.get(3L).stream().anyMatch(b -> b.code().equals("GOOD_MUSIC")));
        // venue 13：Top 4（CLEAN/GOOD_VIBE/FAIR_PRICE/HOT/SWEET_PARTNER 中前 4）——用户豁免仅属于
        // venue 3，venue 13 的 QUIET（第 5 位）正常截断，且无任何 reactedByMe 泄漏
        assertEquals(4, result.get(13L).size());
        assertTrue(result.get(13L).stream().noneMatch(b -> b.code().equals("QUIET")));
        assertTrue(result.get(13L).stream().allMatch(b -> !b.reactedByMe()));
    }
}
