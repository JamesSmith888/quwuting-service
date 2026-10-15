package org.quwuting.quwutingservice.venuereaction.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.opsconfig.service.OpsConfigService;
import org.quwuting.quwutingservice.venue.service.VenueHeatService;
import org.quwuting.quwutingservice.venue.service.VenueLookupService;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionBadge;
import org.quwuting.quwutingservice.venuereaction.dto.response.ReactionStat;
import org.quwuting.quwutingservice.venuereaction.repository.VenueReactionRepository;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
 * 2026-10-15 个人参与记忆：另覆盖 {@code lastReactedDate}（今天之前最近一次）——
 * 与 {@code reactedByMe} 互相独立、不参与集合构成、跨场所不串、匿名不发查询、JSON 契约为
 * {@code yyyy-MM-dd} 字符串且 null 省略。
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

    /**
     * 个人参与记忆行：Object[]{venueId, code, reactedToday(0/1), lastReactedBeforeToday(LocalDate|null)}
     * （与 findMyReactionMarks 返回结构一致）。
     */
    private static Object[] myRow(Long venueId, String code, int reactedToday, LocalDate lastBeforeToday) {
        return new Object[]{venueId, code, reactedToday, lastBeforeToday};
    }

    /** 仅今日一票（没有更早的票） */
    private static Object[] todayOnly(Long venueId, String code) {
        return myRow(venueId, code, 1, null);
    }

    private static ReactionBadge badgeOf(List<ReactionBadge> badges, String code) {
        return badges.stream().filter(b -> b.code().equals(code)).findFirst().orElse(null);
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
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(todayOnly(3L, "BAD_ENV")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L), 2L);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(5, badges.size(), "全部 count>0 的 code 均返回，不做任何截断");
        ReactionBadge badEnv = badgeOf(badges, "BAD_ENV");
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
                service.batchGetBadges(List.of(3L), 2L);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(2, badges.size(), "仅 count>0 的 code 返回");
        assertTrue(badges.stream().allMatch(b -> b.count7d() > 0));
    }

    /** 匿名/未登录用户：完整展示不变，reactedByMe 恒 false，且**不发个人记忆查询** */
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
                service.batchGetBadges(List.of(3L), null);

        List<ReactionBadge> badges = result.get(3L);
        assertEquals(5, badges.size(), "匿名用户同样完整展示（无截断）");
        assertTrue(badges.stream().allMatch(b -> !b.reactedByMe()));
        assertTrue(badges.stream().allMatch(b -> b.lastReactedDate() == null), "匿名没有个人参与记忆");
        verify(venueReactionRepository, never()).findMyReactionMarks(any(), any(), any());
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
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(todayOnly(3L, "BAD_ENV")));

        Map<Long, List<ReactionBadge>> result =
                service.batchGetBadges(List.of(3L, 13L), 2L);

        // venue 3：全部 5 个 count>0 的 code，BAD_ENV 含参与态标注
        assertEquals(5, result.get(3L).size());
        assertTrue(result.get(3L).stream().anyMatch(b -> b.code().equals("BAD_ENV") && b.reactedByMe()));
        // venue 13：全部 6 个 count>0 的 code——QUIET（计数 1）同样包含（无截断），
        // 且无任何 reactedByMe 泄漏（个人状态仅属于 venue 3）
        assertEquals(6, result.get(13L).size());
        assertTrue(result.get(13L).stream().anyMatch(b -> b.code().equals("QUIET")));
        assertTrue(result.get(13L).stream().allMatch(b -> !b.reactedByMe()));
    }

    // ── 个人参与记忆（2026-10-15）：写入侧「今日这一票」≠ 读取侧「我投过没有」 ──────────────

    /**
     * 核心回归：昨天投过、今天还没投 ⇒ reactedByMe=false（今日这一票未投）但 lastReactedDate 有值。
     * 旧实现只返回 reactedByMe，过零点后与「从没投过」不可区分——这正是用户报障「重置后痕迹消失」。
     */
    @Test
    void batchGetBadges_pastVoteSurvivesTheDailyReset() {
        LocalDate yesterday = LocalDate.of(2026, 10, 14);
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(row(3L, "HOT", 5L), row(3L, "QUIET", 1L)));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "HOT", 0, yesterday)));

        List<ReactionBadge> badges = service.batchGetBadges(List.of(3L), 2L).get(3L);

        ReactionBadge hot = badgeOf(badges, "HOT");
        assertFalse(hot.reactedByMe(), "今天还没投：reactedByMe 仍是写入侧语义（今日这一票）");
        assertEquals(yesterday, hot.lastReactedDate(), "曾投的痕迹不随日重置消失");
        ReactionBadge quiet = badgeOf(badges, "QUIET");
        assertFalse(quiet.reactedByMe());
        assertNull(quiet.lastReactedDate(), "没投过的表情没有痕迹");
    }

    /** 今天又投了一次：两个事实同时成立、互不覆盖（今天取消后前端能回到「曾投」而不是「未投」） */
    @Test
    void batchGetBadges_todayAndPastAreIndependentFacts() {
        LocalDate lastWeek = LocalDate.of(2026, 10, 8);
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(row(3L, "HOT", 5L)));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "HOT", 1, lastWeek)));

        ReactionBadge hot = badgeOf(service.batchGetBadges(List.of(3L), 2L).get(3L), "HOT");

        assertTrue(hot.reactedByMe());
        assertEquals(lastWeek, hot.lastReactedDate());
    }

    /** 只有今天一票：没有「今天之前」的痕迹 ⇒ lastReactedDate 为 null（今天这票由 reactedByMe 表达） */
    @Test
    void batchGetBadges_todayOnlyHasNoPastTrace() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(row(3L, "HOT", 1L)));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(todayOnly(3L, "HOT")));

        ReactionBadge hot = badgeOf(service.batchGetBadges(List.of(3L), 2L).get(3L), "HOT");

        assertTrue(hot.reactedByMe());
        assertNull(hot.lastReactedDate());
    }

    /** 驱动层把 MAX(date) 给成 java.sql.Date 时同样归一为 LocalDate（防御，不依赖具体驱动行为） */
    @Test
    void batchGetBadges_acceptsSqlDateFromDriver() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(row(3L, "HOT", 2L)));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(
                        new Object[]{3L, "HOT", 0, java.sql.Date.valueOf(LocalDate.of(2026, 10, 12))}));

        ReactionBadge hot = badgeOf(service.batchGetBadges(List.of(3L), 2L).get(3L), "HOT");

        assertEquals(LocalDate.of(2026, 10, 12), hot.lastReactedDate());
    }

    /** 个人记忆按场所隔离：venue 3 的曾投痕迹不得出现在 venue 13 的同名表情上 */
    @Test
    void batchGetBadges_pastTraceDoesNotLeakAcrossVenues() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(row(3L, "HOT", 2L), row(13L, "HOT", 2L)));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "HOT", 0, LocalDate.of(2026, 10, 12))));

        Map<Long, List<ReactionBadge>> result = service.batchGetBadges(List.of(3L, 13L), 2L);

        assertEquals(LocalDate.of(2026, 10, 12), badgeOf(result.get(3L), "HOT").lastReactedDate());
        assertNull(badgeOf(result.get(13L), "HOT").lastReactedDate());
    }

    /**
     * 曾投不参与集合构成（与 reactedByMe 同属「标注属性」）：countAll=0 的表情即使库里有个人行
     * （迁移遗留 / 竞态窗口）也不得凭空出现在列表里。
     */
    @Test
    void batchGetBadges_pastTraceNeverCreatesABadge() {
        when(venueReactionRepository.countByVenueIdsGroupByCode(any(), any(LocalDateTime.class), any(LocalDateTime.class)))
                .thenReturn(List.<Object[]>of(row(3L, "HOT", 2L)));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), any(), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "QUIET", 0, LocalDate.of(2026, 10, 12))));

        List<ReactionBadge> badges = service.batchGetBadges(List.of(3L), 2L).get(3L);

        assertEquals(1, badges.size());
        assertEquals("HOT", badges.get(0).code());
    }

    /** 详情 stats 与列表徽标同源：getStats 同样携带 lastReactedDate，且复用同一条个人记忆查询 */
    @Test
    void getStats_carriesLastReactedDate() {
        when(aggregateService.getAggregate(3L)).thenReturn(Map.of("HOT", new long[]{5, 0, 1, 3}));
        when(venueReactionRepository.findMyReactionMarks(eq(2L), eq(List.of(3L)), any(LocalDate.class)))
                .thenReturn(List.<Object[]>of(myRow(3L, "HOT", 0, LocalDate.of(2026, 10, 12))));

        List<ReactionStat> stats = service.getStats(3L, 2L).reactions();
        ReactionStat hot = stats.stream().filter(s -> s.code().equals("HOT")).findFirst().orElseThrow();
        ReactionStat quiet = stats.stream().filter(s -> s.code().equals("QUIET")).findFirst().orElseThrow();

        assertFalse(hot.reactedByMe());
        assertEquals(LocalDate.of(2026, 10, 12), hot.lastReactedDate());
        assertNull(quiet.lastReactedDate());
    }

    /** 匿名查看详情 stats：不发个人记忆查询 */
    @Test
    void getStats_anonymousSkipsPersonalQuery() {
        when(aggregateService.getAggregate(3L)).thenReturn(Map.of("HOT", new long[]{5, 0, 1, 3}));

        service.getStats(3L, null).reactions().forEach(s -> {
            assertFalse(s.reactedByMe());
            assertNull(s.lastReactedDate());
        });
        verify(venueReactionRepository, never()).findMyReactionMarks(any(), any(), any());
    }

    /**
     * JSON 契约：lastReactedDate 序列化为 {@code yyyy-MM-dd} 字符串（不是 [2026,10,12] 数组），
     * 缺省（null）时字段省略——前端把「缺省」读作「没有痕迹」，新前端对老后端 / 老前端对新后端都兼容。
     * 与应用配置 {@code spring.jackson.default-property-inclusion: non_null} 同口径。
     */
    @Test
    void lastReactedDate_serializesAsIsoDateAndIsOmittedWhenNull() throws Exception {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();

        String withDate = mapper.writeValueAsString(
                new ReactionBadge("HOT", "🔥", "人气旺", 5, 1, 3, false, LocalDate.of(2026, 10, 12)));
        String without = mapper.writeValueAsString(
                new ReactionBadge("HOT", "🔥", "人气旺", 5, 1, 3, true, null));
        String statWithDate = mapper.writeValueAsString(
                new ReactionStat("HOT", "🔥", "人气旺", 0, 1, 3, 5, false, LocalDate.of(2026, 10, 12)));

        assertTrue(withDate.contains("\"lastReactedDate\":\"2026-10-12\""), withDate);
        assertFalse(without.contains("lastReactedDate"), without);
        assertTrue(statWithDate.contains("\"lastReactedDate\":\"2026-10-12\""), statWithDate);
    }
}
