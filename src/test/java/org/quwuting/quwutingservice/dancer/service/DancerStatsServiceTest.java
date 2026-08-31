package org.quwuting.quwutingservice.dancer.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.dancer.dto.response.DancerDemandRecord;
import org.quwuting.quwutingservice.dancer.entity.Dancer;
import org.quwuting.quwutingservice.dancer.entity.DemandRecord;
import org.quwuting.quwutingservice.dancer.repository.DancerRepository;
import org.quwuting.quwutingservice.dancer.repository.DancerStatsRepository;
import org.quwuting.quwutingservice.dancer.repository.DemandRecordRepository;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourcePermission;
import org.quwuting.quwutingservice.resourceaccess.enums.ResourceType;
import org.quwuting.quwutingservice.resourceaccess.service.ResourceAccessService;
import org.quwuting.quwutingservice.user.entity.User;
import org.quwuting.quwutingservice.user.enums.UserRole;
import org.quwuting.quwutingservice.user.repository.UserRepository;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 舞伴统计服务回归测试（需求热度下钻的授权与去标识化读模型）。 */
@ExtendWith(MockitoExtension.class)
class DancerStatsServiceTest {

    @Mock
    private DancerStatsRepository dancerStatsRepository;
    @Mock
    private DancerRepository dancerRepository;
    @Mock
    private DemandRecordRepository demandRecordRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ResourceAccessService resourceAccessService;

    private DancerStatsService service;

    @BeforeEach
    void setUp() {
        service = new DancerStatsService(resourceAccessService, dancerStatsRepository, dancerRepository,
            demandRecordRepository, userRepository);
    }

    @Test
    void demandRecords_adminReadsCategoryAndMapsLegacyStatus() {
        Dancer dancer = dancer(51L, 8L);
        DemandRecord record = new DemandRecord();
        record.setId(71L);
        record.setUserId(22L);
        record.setCreatedAt(LocalDateTime.of(2026, 8, 31, 12, 0));
        record.setMessage("「去舞厅」：按时段 · KTV · 近3天内😊");
        record.setStatus(null);
        when(dancerRepository.findByIdAndDeletedFalse(51L)).thenReturn(Optional.of(dancer));
        when(resourceAccessService.hasPermission(3L, UserRole.ADMIN, ResourceType.DANCER, 51L,
            ResourcePermission.DANCER_DEMAND_RECORDS_READ)).thenReturn(true);
        when(demandRecordRepository.findByDancerIdAndServiceCategory(eq(51L), eq("PACKAGE"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(record)));
        User user = new User();
        user.setId(22L);
        user.setNickname("舞友小王");
        user.setAvatarUrl("https://example.com/avatar.jpg");
        when(userRepository.findByIdInAndDeletedFalse(List.of(22L))).thenReturn(List.of(user));

        List<DancerDemandRecord> records = service.demandRecords(3L, UserRole.ADMIN, 51L,
                "PACKAGE", -1, 100).getContent();

        assertEquals(1, records.size());
        assertEquals(71L, records.getFirst().id());
        assertEquals("舞友小王", records.getFirst().nickname());
        assertEquals("已获取联系方式", records.getFirst().statusText());
        verify(demandRecordRepository).findByDancerIdAndServiceCategory(eq(51L), eq("PACKAGE"), any(Pageable.class));
    }

    @Test
    void demandRecords_nonManagerIsRejectedBeforeQueryingRecords() {
        when(dancerRepository.findByIdAndDeletedFalse(51L)).thenReturn(Optional.of(dancer(51L, 8L)));
        when(resourceAccessService.hasPermission(9L, UserRole.USER, ResourceType.DANCER, 51L,
            ResourcePermission.DANCER_DEMAND_RECORDS_READ)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.demandRecords(9L, UserRole.USER, 51L, "PACKAGE", 0, 20));

        assertEquals("无管理权限", ex.getMessage());
        verify(demandRecordRepository, never()).findByDancerIdAndServiceCategory(any(), any(), any());
    }

    private static Dancer dancer(Long id, Long createdBy) {
        Dancer dancer = new Dancer();
        dancer.setId(id);
        dancer.setCreatedBy(createdBy);
        return dancer;
    }
}