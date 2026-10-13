package org.quwuting.quwutingservice.payqr;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.exception.BusinessException;
import org.quwuting.quwutingservice.payqr.dto.PayQrDtos;
import org.quwuting.quwutingservice.payqr.enums.PayQrState;
import org.quwuting.quwutingservice.payqr.repository.UserPayQrStatusRepository;
import org.quwuting.quwutingservice.payqr.service.PayQrStatusService;
import org.quwuting.quwutingservice.support.HqlSyntaxAssertions;
import org.quwuting.quwutingservice.user.repository.UserRepository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 收款码配置状态（2026-10-13，V49）：协议只接收张数、越界拒绝而非钳制、首次配置时刻只在 count&gt;0 时给出、
 * 名单超限明说、SQL 的两个关键约束（upsert 赋值顺序 / 用户范围引用常量）。
 */
@ExtendWith(MockitoExtension.class)
class PayQrStatusServiceTest {

    @Mock
    private UserPayQrStatusRepository repository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private PayQrStatusService service;

    @Test
    void reportOnlyCarriesACountAndTheServerStampsFirstConfiguredTimeWhenPositive() {
        service.report(7L, 2);
        service.report(7L, 0);

        ArgumentCaptor<LocalDateTime> firstAt = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(repository).upsert(eq(7L), eq(2), firstAt.capture(), any(LocalDateTime.class));
        assertNotNull(firstAt.getValue(), "count>0 ⇒ 带上首次配置时刻（SQL 侧 COALESCE 保首次）");
        verify(repository).upsert(eq(7L), eq(0), eq((LocalDateTime) null), any(LocalDateTime.class));
    }

    @Test
    void outOfRangeCountIsRejectedNotClamped() {
        // 端侧上限与服务端漂移是 bug，应暴露（1022），而不是被静默吞成"合法的 6"
        assertThrows(BusinessException.class, () -> service.report(7L, PayQrStatusService.MAX_COUNT + 1));
        assertThrows(BusinessException.class, () -> service.report(7L, -1));
        assertThrows(BusinessException.class, () -> service.report(7L, null));
        verifyNoInteractions(repository);
    }

    @Test
    void snapshotsForShortCircuitsOnEmptyInputAndDerivesTheThreeStates() {
        assertEquals(Map.of(), service.snapshotsFor(List.of()));
        verify(repository, never()).findStatusByUserIds(anyCollection());

        LocalDateTime first = LocalDateTime.now().minusDays(2);
        when(repository.findStatusByUserIds(anyCollection())).thenReturn(List.<Object[]>of(
                new Object[]{5L, 3, first},     // 持有 3 张
                new Object[]{6L, 0, first},     // 配置后清空
                new Object[]{7L, 0, null}));    // 只报过 0、从未配置
        Map<Long, PayQrStatusService.Snapshot> got = service.snapshotsFor(List.of(5L, 6L, 7L, 8L));

        assertEquals(new PayQrStatusService.Snapshot(3, PayQrState.CONFIGURED), got.get(5L));
        assertEquals(new PayQrStatusService.Snapshot(0, PayQrState.CLEARED), got.get(6L));
        assertEquals(PayQrState.NO_REPORT, got.get(7L).state(), "只报过 0 = 没配置过，不得读成『曾配置』");
        assertNull(got.get(8L), "缺席由调用方取 Snapshot.NO_REPORT，服务不替它造行");
    }

    @Test
    void everyStateHasAnExplicitDisplayAndNoReportDoesNotClaimUnconfigured() {
        assertEquals("已配置 2 张", PayQrState.CONFIGURED.display(2));
        assertEquals("曾配置，现已清空", PayQrState.CLEARED.display(0));
        String noReport = PayQrState.NO_REPORT.display(0);
        // 无记录 = 没配置过 或 旧版小程序未上报，二者不可区分——文案必须两种可能都写出来
        assertTrue(noReport.contains("没配置过") && noReport.contains("旧版"), noReport);
        assertEquals(PayQrState.NO_REPORT, PayQrStatusService.Snapshot.NO_REPORT.state());
    }

    @Test
    void adminUsersFlagsTruncationInsteadOfSilentlyCapping() {
        List<UserPayQrStatusRepository.UserRow> rows = new ArrayList<>();
        for (long i = 1; i <= 201; i++) {
            rows.add(row(i, 1));
        }
        when(repository.findConfiguredUsers(anyInt())).thenReturn(rows);

        PayQrDtos.AdminUsers users = service.adminUsers();

        assertEquals(200, users.users().size());
        assertTrue(users.truncated(), "多取一条判超限，超限必须明说");
    }

    @Test
    void clearedUserStaysInTheListMarkedAsCleared() {
        when(repository.findConfiguredUsers(anyInt())).thenReturn(List.of(row(9L, 0)));
        when(userRepository.findAllById(anyCollection())).thenReturn(List.of());

        PayQrDtos.AdminUsers users = service.adminUsers();

        assertFalse(users.truncated());
        assertTrue(users.users().get(0).cleared(), "配置后又清空的用户仍在名单里（回答『配置过』），以 cleared 区分");
        assertEquals("U#00009", users.users().get(0).userCode(), "资料缺失也给代号");
        assertNull(users.users().get(0).nickname());
    }

    @Test
    void upsertKeepsChangedAtSemanticsByAssignmentOrder() throws Exception {
        // MySQL 的 ON DUPLICATE KEY UPDATE 按书写顺序赋值：updated_at 必须排在 qr_count 之前，
        // 才能用「旧 qr_count ≠ 新值」判定是否真的变化；颠倒后每次同值上报都会被判为"无变化"或"恒变化"
        String sql = UserPayQrStatusRepository.class
                .getMethod("upsert", Long.class, int.class, LocalDateTime.class, LocalDateTime.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
        int updatedAt = sql.indexOf("updated_at = IF(qr_count <> VALUES(qr_count)");
        int firstAt = sql.indexOf("first_configured_at = COALESCE(");
        int qrCount = sql.indexOf("qr_count = VALUES(qr_count)", sql.indexOf("ON DUPLICATE KEY UPDATE"));
        assertTrue(updatedAt > 0 && firstAt > 0 && qrCount > 0, sql);
        assertTrue(updatedAt < qrCount, "updated_at 判定必须先于 qr_count 赋值");
        assertTrue(firstAt < qrCount);
    }

    @Test
    void jpqlQueryParsesAndNativeQueriesReferenceTheSharedUserScope() throws Exception {
        // JPQL 侧只保证词法可解析（绿灯 ≠ MySQL 侧绿灯，连库契约另测）；原生 SQL 必须引用 USER_SCOPE 常量而不是抄写
        HqlSyntaxAssertions.assertParses(UserPayQrStatusRepository.class
                .getMethod("findStatusByUserIds", java.util.Collection.class)
                .getAnnotation(org.springframework.data.jpa.repository.Query.class).value());
        for (String method : List.of("sumStats", "findConfiguredUsers")) {
            String sql = java.util.Arrays.stream(UserPayQrStatusRepository.class.getMethods())
                    .filter(m -> m.getName().equals(method)).findFirst().orElseThrow()
                    .getAnnotation(org.springframework.data.jpa.repository.Query.class).value();
            assertTrue(sql.contains(org.quwuting.quwutingservice.user.repository.UserStatsSql.USER_SCOPE),
                    method + " 必须引用 UserStatsSql.USER_SCOPE（全库唯一的『真实用户』定义）");
        }
    }

    private static UserPayQrStatusRepository.UserRow row(long userId, int count) {
        return new UserPayQrStatusRepository.UserRow() {
            @Override
            public Long getUserId() {
                return userId;
            }

            @Override
            public Integer getQrCount() {
                return count;
            }

            @Override
            public LocalDateTime getFirstConfiguredAt() {
                return LocalDateTime.now().minusDays(1);
            }

            @Override
            public LocalDateTime getChangedAt() {
                return LocalDateTime.now();
            }
        };
    }
}
