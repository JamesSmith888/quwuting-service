package org.quwuting.quwutingservice.venuereaction.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
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
 * 覆盖 topReactions 的**完整展示**契约（见 {@link VenueReactionService#buildTopBadgesFromCounts}
 * javadoc 与 AGENTS.md「Reaction 快速反馈系统」）：
 * <ol>
 *   <li>**所选窗口内所有用户点击过的全部表情（count>0）一个不落全部返回，不做任何截断**
 *       （需求 2026-08-09：取所有用户的所有已点击表情全部展示）——用户已参与但窗口计数
 *       较低的 code 同样包含（2026-08-08 "当前用户已参与 code 不受截断"豁免与
 *       2026-08-09 上午"纯 Top N"口径均已撤销）；</li>
 *   <li>count=0 的 code 不展示（只有真实用户行为才计入展示）；</li>
 *   <li>reactedByMe 仅作徽标标注属性（个人状态不参与集合构成）。</li>
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
    @Mock
    private OpsConfigService opsConfigService;

    private VenueReactionService service;

    @BeforeEach
    void setUp() {
        // @PersistenceContext entityManager 为字段注入，不参与构造；batchGetBadges 路径不触碰
        // opsConfigService 仅 toggle（每日一票开关）使用，徽标路径不触碰，mock 即可
        service = new VenueReactionService(venueReactionRepository, aggregateService,
                venueLookupService, venueHeatService, opsConfigService);
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
     * 完整展示契约（2026-08-09 需求定稿）：venue 3 有 5 个 count>0 的 code（含窗口计数仅 1 的
     * BAD_ENV）。断言 5 个**全部返回**、按窗口计数降序——不做任何截断，用户已参与的低计数
     * code 同样包含（个人状态不参与集合构成，reactedByMe 仅标注）。
     */
    @Test
    void batchGetBadges_returnsAllCodesWithCountAboveZero() {
        // 2026-08-13 字典瘦身同步：mock 改用当前 ReactionCode 字典合法 code
        //（旧 FAIR_PRICE/HIGH_COST/GOOD_MUSIC 已删除，batchGetBadges 按字典过滤后数量失真）
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "HOT", 2L),
                        row(3L, "RECOMMEND", 2L),
                        row(3L, "SWEET_PARTNER", 2L),
                        row(3L, "QUIET", 2L),
                        row(3L, "BAD_ENV", 1L)));
        when(venueReactionRepository.findTodayCodesByUserAndVenueIds(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "BAD_ENV")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), 2L, ReactionWindow.DAYS_7);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(5, badges.size(), "全部 count>0 的 code 均返回，不做任何截断");
        ReactionBadge badEnv = badges.stream()
                .filter(b -> b.code().equals("BAD_ENV")).findFirst().orElse(null);
        assertTrue(badEnv != null, "窗口计数较低的 code 同样包含（无截断）");
        assertEquals(1L, badEnv.count7d());
        assertTrue(badEnv.reactedByMe(), "用户参与的 code 必须携带参与态标注");
        // 降序：前 4 个计数 2，最后 1 个计数 1
        assertEquals(4, badges.stream().filter(b -> b.count7d() == 2).count());
        assertEquals(1, badges.stream().filter(b -> b.count7d() == 1).count());
    }

    /** count=0 的 code 不展示——只有真实用户行为（至少一次参与）才计入展示集合 */
    @Test
    void batchGetBadges_filtersOutZeroCountCodes() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "HOT", 2L),
                        row(3L, "QUIET", 1L)));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), 2L, ReactionWindow.DAYS_7);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(2, badges.size(), "仅 count>0 的 code 返回");
        assertTrue(badges.stream().allMatch(b -> b.count7d() > 0));
    }

    /** 匿名/未登录用户：完整展示不变，reactedByMe 恒 false */
    @Test
    void batchGetBadges_anonymousReturnsAllCodesWithoutReactedByMe() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "HOT", 2L),
                        row(3L, "RECOMMEND", 2L),
                        row(3L, "SWEET_PARTNER", 2L),
                        row(3L, "QUIET", 2L),
                        row(3L, "BAD_ENV", 1L)));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), null, ReactionWindow.DAYS_7);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(5, badges.size(), "匿名用户同样完整展示（无截断）");
        assertTrue(badges.stream().allMatch(b -> !b.reactedByMe()));
    }

    /** 批量场所隔离：每场所各自独立组装全部 count>0 的 code，互不污染 */
    @Test
    void batchGetBadges_isolatesPerVenue() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(
                        row(3L, "HOT", 2L),
                        row(3L, "RECOMMEND", 2L),
                        row(3L, "SWEET_PARTNER", 2L),
                        row(3L, "QUIET", 2L),
                        row(3L, "BAD_ENV", 1L),
                        row(13L, "HOT", 2L),
                        row(13L, "RECOMMEND", 2L),
                        row(13L, "SWEET_PARTNER", 2L),
                        row(13L, "GOOD_SERVICE", 2L),
                        row(13L, "SERVICE_ISSUE", 2L),
                        row(13L, "QUIET", 1L)));
        when(venueReactionRepository.findTodayCodesByUserAndVenueIds(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "BAD_ENV")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L, 13L), 2L, ReactionWindow.DAYS_7);

        // venue 3：全部 5 个 count>0 的 code，BAD_ENV 含参与态标注
        assertEquals(5, result.get(3L).size());
        assertTrue(result.get(3L).stream().anyMatch(b -> b.code().equals("BAD_ENV") && b.reactedByMe()));
        // venue 13：全部 6 个 count>0 的 code——QUIET（计数 1）同样包含（无截断），
        // 且无任何 reactedByMe 泄漏（个人状态仅属于 venue 3）
        assertEquals(6, result.get(13L).size());
        assertTrue(result.get(13L).stream().anyMatch(b -> b.code().equals("QUIET")));
        assertTrue(result.get(13L).stream().allMatch(b -> !b.reactedByMe()));
    }
}
