package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.dto.response.FavoriteTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.ReactionTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

/**
 * VenueHeatService 热度公式语义单元测试（Mockito，不依赖数据库）。
 * <p>
 * 覆盖 2026-08 详情页热度专项修复的核心语义：
 * <ol>
 *   <li>负向 Reaction 不计入热度公式（positiveReactionCount30d 计入、negativeReactionCount30d 仅展示）；</li>
 *   <li>满意度中性偏移：高于 6 加分、低于 6 扣分（低分店热度真实下降）；</li>
 *   <li>评价人数不足 MIN_RATING_SAMPLE 时满意度不参与计算、公式文案不含满意度项；</li>
 *   <li><b>非负收敛（2026-08-08）</b>：满意度负偏移把总分拉负时 clamp 到 0，公式文案标注「按0计」；</li>
 *   <li><b>趋势序列</b>：收藏/浏览/正负向 Reaction 由趋势 mega-query 统一取数并回填响应。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VenueHeatServiceTest {

    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private TagInteractionRepository tagInteractionRepository;
    @Mock
    private VenueRepository.HeatCounters counters;

    private VenueHeatService heatService;

    private Venue venue;

    @BeforeEach
    void setUp() {
        heatService = new VenueHeatService(venueLookupService, venueRepository,
                tagInteractionRepository);
        venue = new Venue();
        venue.setId(1L);
        venue.setStatus(VenueStatus.OPEN);

        when(venueLookupService.findById(1L)).thenReturn(venue);
        // 趋势 mega-query：无数据（generate_series 骨架由 SQL 保证连续，此处空列表即可）
        when(venueRepository.countDailyTrends(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(Collections.emptyList());
    }

    /** 基础 stubbing：热度 mega-query 计数器全零，各测试按需覆盖 */
    private void stubZeroCounters() {
        when(venueRepository.countHeatCounters(anyLong(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(counters);
        when(counters.getPv()).thenReturn(0L);
        when(counters.getUv()).thenReturn(0L);
        when(counters.getFavtotal()).thenReturn(0L);
        when(counters.getFavrecent()).thenReturn(0L);
        when(counters.getPosttotal()).thenReturn(0L);
        when(counters.getPostrecent()).thenReturn(0L);
        when(counters.getRatingcount30d()).thenReturn(0L);
        when(counters.getPositivereactioncount30d()).thenReturn(0L);
        when(counters.getNegativereactioncount30d()).thenReturn(0L);
        when(counters.getRaters()).thenReturn(0L);
        when(counters.getSuspensioncount()).thenReturn(0L);
        when(counters.getLateststatuslogtime()).thenReturn(null);
        when(counters.getReportcount()).thenReturn(0L);
        when(counters.getLatestreporttime()).thenReturn(null);
    }

    @Test
    void negativeReactionsAreExcludedFromHeatScore() {
        stubZeroCounters();
        // 正向 5 条、负向 8 条：热度只加 5×3=15，负向仅单独展示
        when(counters.getPositivereactioncount30d()).thenReturn(5L);
        when(counters.getNegativereactioncount30d()).thenReturn(8L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals(15L, resp.heatScore(), "负向 Reaction 不应计入热度指数");
        assertEquals(8L, resp.negativeReactionCount30d(), "负向计数应单独下发供展示");
        assertTrue(resp.formulaText().contains("5×3"), "公式应含正向 Reaction 项");
        assertFalse(resp.formulaText().contains("8×3"), "公式不应把负向计数当作加分项");
    }

    @Test
    void lowSatisfactionClampsHeatScoreToZero() {
        stubZeroCounters();
        // 评价人数 ≥3，满意度 4.0 → 偏移 (4-6)=-2.0 → 贡献 -40 → 总分 -40 被 clamp 到 0
        when(counters.getRaters()).thenReturn(5L);
        when(tagInteractionRepository.aggregateScoresByVenueSinceGroupByTag(anyLong(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"服务", 4.0, 5},
                        new Object[]{"环境", 4.0, 5},
                        new Object[]{"音响效果", 4.0, 5},
                        new Object[]{"性价比", 4.0, 5}));

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals(0L, resp.heatScore(), "满意度负偏移把总分拉负时按 0 计（热度指数非负）");
        assertTrue(resp.formulaText().contains("-2.0×20"), "公式应展示满意度负偏移项");
        assertTrue(resp.formulaText().contains("按0计"), "公式应标注非负收敛");
        assertTrue(resp.formulaDetail().contains("按0计"), "公式详情应说明非负收敛规则");
    }

    @Test
    void highSatisfactionAddsToHeatScore() {
        stubZeroCounters();
        when(counters.getRaters()).thenReturn(5L);
        when(tagInteractionRepository.aggregateScoresByVenueSinceGroupByTag(anyLong(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"服务", 8.0, 5},
                        new Object[]{"环境", 8.0, 5},
                        new Object[]{"音响效果", 8.0, 5},
                        new Object[]{"性价比", 8.0, 5}));

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals(40L, resp.heatScore(), "满意度 8 分应加 40 分（(8-6)×20）");
        assertTrue(resp.formulaText().contains("2.0×20"), "公式应展示满意度正偏移项");
    }

    @Test
    void insufficientRatersExcludesSatisfactionFromFormula() {
        stubZeroCounters();
        // 评价人数 < 3：满意度不参与计算，公式文案不含 ×20 项
        when(counters.getRaters()).thenReturn(2L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals(0L, resp.heatScore());
        assertFalse(resp.formulaText().contains("×20"), "样本不足时公式不应含满意度项");
        assertTrue(resp.formulaDetail().contains("不足3人"), "详情应说明满意度未参与计算的原因");
    }

    @Test
    void trendSeriesArePopulatedFromTrendMegaQuery() {
        stubZeroCounters();
        // 趋势 mega-query 返回 2 天骨架行：收藏/浏览/正负向 Reaction 各自回填对应列表
        LocalDate d1 = LocalDate.now().minusDays(2);
        LocalDate d2 = LocalDate.now().minusDays(1);
        when(venueRepository.countDailyTrends(anyLong(), any(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(List.of(
                new VenueRepository.DailyTrendRow() {
                    @Override public LocalDate getDay() { return d1; }
                    @Override public Long getFavcount() { return 1L; }
                    @Override public Long getViewcount() { return 10L; }
                    @Override public Long getPosreaction() { return 3L; }
                    @Override public Long getNegreaction() { return 2L; }
                },
                new VenueRepository.DailyTrendRow() {
                    @Override public LocalDate getDay() { return d2; }
                    @Override public Long getFavcount() { return 0L; }
                    @Override public Long getViewcount() { return 5L; }
                    @Override public Long getPosreaction() { return 0L; }
                    @Override public Long getNegreaction() { return 1L; }
                }));

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals(2, resp.favoriteTrend().size(), "收藏趋势应含全部骨架行");
        assertEquals(1L, resp.favoriteTrend().get(0).count(), "收藏趋势应回填当日计数");
        assertEquals(10L, resp.viewTrend().get(0).count(), "浏览趋势应回填当日浏览数");
        assertEquals(2, resp.reactionTrend().size(), "反馈趋势应含全部骨架行");
        assertEquals(3L, resp.reactionTrend().get(0).positive(), "反馈趋势应回填正向计数");
        assertEquals(2L, resp.reactionTrend().get(0).negative(), "反馈趋势应回填负向计数");
        assertEquals(0L, resp.reactionTrend().get(1).positive(), "无数据日应补零");
        assertEquals(d1.toString(), resp.favoriteTrend().get(0).date(), "日期应为 yyyy-MM-dd");
    }

    // ── 状态可信度（2026-08-08 三维矩阵：状态类型 × 稳定性 × 持续天数） ────────────
    // 根因回归：旧二维矩阵不区分状态类型，已停业门店（近30天暂停 0 次）恒命中 HIGH，
    // 前端硬编码「稳定营业」→ "已停业却显示稳定营业"（寻梦缘123 生产实证）。
    // 修复后判定与文案均在后端生成（statusConfidenceText / statusConfidenceRuleDetail），
    // 以下用例断言「等级 × 文案」随状态类型正确分治。

    private void stubStatusLog(LocalDateTime latestCreatedAt, long suspensionCount30d, long reportCount) {
        stubZeroCounters();
        when(counters.getLateststatuslogtime()).thenReturn(latestCreatedAt);
        when(counters.getSuspensioncount()).thenReturn(suspensionCount30d);
        when(counters.getReportcount()).thenReturn(reportCount);
    }

    @Test
    void openVenueStableIsHighWithStableBusinessText() {
        // 营业中 + 0 次暂停（无论持续多久）→ HIGH「稳定营业」（原语义保持）
        stubStatusLog(LocalDateTime.now().minusDays(30), 0L, 0L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("HIGH", resp.statusConfidence());
        assertEquals("稳定营业", resp.statusConfidenceText());
        assertTrue(resp.statusConfidenceRuleDetail().contains("近30天暂停 0 次 = 稳定"),
                "判定依据应说明稳定性输入");
    }

    @Test
    void openVenueUnstableRecentlyIsMedium() {
        // 营业中 + 1 次暂停 + 状态持续 ≤7天 → MEDIUM「状态多变」
        stubStatusLog(LocalDateTime.now().minusDays(3), 1L, 0L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("MEDIUM", resp.statusConfidence());
        assertEquals("状态多变", resp.statusConfidenceText());
    }

    @Test
    void openVenueUnstableStaleIsLow() {
        // 营业中 + 1 次暂停 + 状态持续 >7天 → LOW「数据可能过时」
        stubStatusLog(LocalDateTime.now().minusDays(20), 1L, 0L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("LOW", resp.statusConfidence());
        assertEquals("数据可能过时", resp.statusConfidenceText());
    }

    @Test
    void ceasedVenueLongStableIsHighWithNeutralText() {
        // 寻梦缘123 回归：已停业 + 近30天暂停 0 次（停业门店常态）+ 持续 20 天
        // → HIGH 但文案必须是「状态可信」，绝非「稳定营业」
        venue.setStatus(VenueStatus.CEASED);
        stubStatusLog(LocalDateTime.now().minusDays(20), 0L, 0L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("HIGH", resp.statusConfidence());
        assertEquals("状态可信", resp.statusConfidenceText());
        assertFalse(resp.statusConfidenceText().contains("稳定营业"), "已停业门店不得显示稳定营业");
        assertTrue(resp.statusConfidenceRuleDetail().contains("已停业"),
                "判定依据应点名当前状态类型");
        assertTrue(resp.statusConfidenceRuleDetail().contains("20"), "判定依据应含状态持续天数");
    }

    @Test
    void ceasedVenueJustChangedIsMedium() {
        // 已停业 + 刚变更（≤7天）→ MEDIUM「建议确认」（未经时间验证）
        venue.setStatus(VenueStatus.CEASED);
        stubStatusLog(LocalDateTime.now().minusDays(2), 0L, 0L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("MEDIUM", resp.statusConfidence());
        assertEquals("建议确认", resp.statusConfidenceText());
    }

    @Test
    void suspendedVenueLongStableIsHigh() {
        // 暂停营业 + 持续 15 天 → HIGH「状态可信」（长时间未被纠正 = 被时间验证）
        venue.setStatus(VenueStatus.SUSPENDED);
        stubStatusLog(LocalDateTime.now().minusDays(15), 0L, 0L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("HIGH", resp.statusConfidence());
        assertEquals("状态可信", resp.statusConfidenceText());
    }

    @Test
    void activeReportsOverrideConfidenceToLowRegardlessOfStatus() {
        // 活跃报告 override：已停业门店 TTL 内有 2 人报告 → LOW「数据可能过时」（众包实时信号优先）
        venue.setStatus(VenueStatus.CEASED);
        stubStatusLog(LocalDateTime.now().minusDays(20), 0L, 2L);

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals("LOW", resp.statusConfidence());
        assertEquals("数据可能过时", resp.statusConfidenceText());
        assertTrue(resp.statusConfidenceRuleDetail().contains("2 人报告"), "判定依据应说明活跃报告数");
    }
}
