package org.quwuting.quwutingservice.venue.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.taginteraction.repository.TagInteractionRepository;
import org.quwuting.quwutingservice.venue.dto.response.FavoriteTrendPoint;
import org.quwuting.quwutingservice.venue.dto.response.VenueHeatResponse;
import org.quwuting.quwutingservice.venue.entity.Venue;
import org.quwuting.quwutingservice.venue.enums.VenueStatus;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;

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
 * 覆盖 2026-08 详情页热度专项修复的三条核心语义：
 * <ol>
 *   <li>负向 Reaction 不计入热度公式（positiveReactionCount30d 计入、negativeReactionCount30d 仅展示）；</li>
 *   <li>满意度中性偏移：高于 6 加分、低于 6 扣分（低分店热度真实下降）；</li>
 *   <li>评价人数不足 MIN_RATING_SAMPLE 时满意度不参与计算、公式文案不含满意度项。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
class VenueHeatServiceTest {

    @Mock
    private VenueLookupService venueLookupService;
    @Mock
    private VenueRepository venueRepository;
    @Mock
    private FavoriteRepository favoriteRepository;
    @Mock
    private TagInteractionRepository tagInteractionRepository;
    @Mock
    private VenueRepository.HeatCounters counters;

    private VenueHeatService heatService;

    private Venue venue;

    @BeforeEach
    void setUp() {
        heatService = new VenueHeatService(venueLookupService, venueRepository,
                favoriteRepository, tagInteractionRepository);
        venue = new Venue();
        venue.setId(1L);
        venue.setStatus(VenueStatus.OPEN);

        when(venueLookupService.findById(1L)).thenReturn(venue);
        // 收藏趋势：无数据（缺失日期由服务端补零）
        when(favoriteRepository.countDailyFavoritesSince(anyLong(), any(), any()))
                .thenReturn(Collections.emptyList());
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
    void lowSatisfactionReducesHeatScore() {
        stubZeroCounters();
        // 评价人数 ≥3，满意度 4.0 → 偏移 (4-6)=-2.0 → 贡献 -40
        when(counters.getRaters()).thenReturn(5L);
        when(tagInteractionRepository.aggregateScoresByVenueSinceGroupByTag(anyLong(), any(), any()))
                .thenReturn(List.of(
                        new Object[]{"服务", 4.0, 5},
                        new Object[]{"环境", 4.0, 5},
                        new Object[]{"音响效果", 4.0, 5},
                        new Object[]{"性价比", 4.0, 5}));

        VenueHeatResponse resp = heatService.getHeat(1L);

        assertEquals(-40L, resp.heatScore(), "满意度 4 分应扣 40 分（(4-6)×20）");
        assertTrue(resp.formulaText().contains("-2.0×20"), "公式应展示满意度负偏移项");
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
}
