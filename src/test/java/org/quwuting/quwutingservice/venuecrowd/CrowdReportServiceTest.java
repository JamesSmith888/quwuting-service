package org.quwuting.quwutingservice.venuecrowd;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.favorite.repository.FavoriteRepository;
import org.quwuting.quwutingservice.message.service.MessageService;
import org.quwuting.quwutingservice.points.service.ContributionService;
import org.quwuting.quwutingservice.points.service.PointsService;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.quwuting.quwutingservice.venue.repository.VenueRepository;
import org.quwuting.quwutingservice.venuecrowd.dto.response.CrowdSummary;
import org.quwuting.quwutingservice.venuecrowd.entity.VenueCrowdReport;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdFemaleLevel;
import org.quwuting.quwutingservice.venuecrowd.enums.CrowdMaleLevel;
import org.quwuting.quwutingservice.venuecrowd.repository.VenueCrowdReportRepository;
import org.quwuting.quwutingservice.venuecrowd.service.CrowdReportService;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CrowdReportServiceTest {

    @Mock
    private VenueCrowdReportRepository crowdReportRepository;

    @Mock
    private VenueRepository venueRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ContributionService contributionService;

    @Mock
    private PointsService pointsService;

    @Mock
    private MessageService messageService;

    @Mock
    private FavoriteRepository favoriteRepository;

    private CrowdReportService crowdReportService;

    @BeforeEach
    void setUp() {
        crowdReportService = new CrowdReportService(
                crowdReportRepository,
                venueRepository,
                userRepository,
                contributionService,
                pointsService,
                messageService,
                favoriteRepository
        );
    }

    @Test
    void testLevelsAndDisplayNames() {
        assertEquals("0-20", CrowdFemaleLevel.RANGE_0_20.getDisplayName());
        assertEquals("约30", CrowdFemaleLevel.RANGE_30.getDisplayName());
        assertEquals("约50", CrowdFemaleLevel.RANGE_50.getDisplayName());
        assertEquals("约80", CrowdFemaleLevel.RANGE_80.getDisplayName());
        assertEquals("约100", CrowdFemaleLevel.RANGE_100.getDisplayName());
        assertEquals("约150", CrowdFemaleLevel.RANGE_150.getDisplayName());
        assertEquals("约200", CrowdFemaleLevel.RANGE_200.getDisplayName());
        assertEquals("约300+", CrowdFemaleLevel.RANGE_300_PLUS.getDisplayName());

        assertEquals(8, CrowdFemaleLevel.values().length);
        assertEquals(8, CrowdMaleLevel.values().length);
        assertEquals(CrowdFemaleLevel.RANGE_100, CrowdFemaleLevel.of(5));
        assertEquals(CrowdMaleLevel.RANGE_80, CrowdMaleLevel.of(4));
    }

    @Test
    void testSummaryMainTextGeneration() {
        Long venueId = 100L;
        LocalDateTime now = LocalDateTime.now();

        VenueCrowdReport r1 = new VenueCrowdReport();
        r1.setId(1L);
        r1.setVenueId(venueId);
        r1.setUserId(10L);
        r1.setFemaleLevel(5); // 约100
        r1.setMaleLevel(3);   // 约50
        r1.setReportDate(LocalDate.now());
        r1.setCreatedAt(now.minusMinutes(10));
        r1.setModifyCount(0);

        when(crowdReportRepository.findByVenueIdAndCreatedAtAfterAndDeletedFalse(eq(venueId), any()))
                .thenReturn(List.of(r1));
        when(contributionService.aggregatesFor(any())).thenReturn(Map.of());

        User user = new User();
        user.setId(10L);
        user.setNickname("舞友小张");
        when(userRepository.findByIdInAndDeletedFalse(any())).thenReturn(List.of(user));

        CrowdSummary summary = crowdReportService.summary(venueId);

        assertTrue(summary.hasData());
        assertNotNull(summary.female());
        assertEquals(5, summary.female().level());
        assertEquals("约100", summary.female().levelName());
        assertTrue(summary.mainText().startsWith("舞伴 约100 · 1 位舞友 · "));
        assertNotNull(summary.male());
        assertEquals("男客 约50 · 1 人", summary.maleText());
        assertEquals(1, summary.rows().size());
        assertEquals("约100", summary.rows().get(0).femaleLevelName());
        assertEquals("约50", summary.rows().get(0).maleLevelName());
    }
}
