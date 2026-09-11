package org.quwuting.quwutingservice.spend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.spend.dto.SpendEntryItem;
import org.quwuting.quwutingservice.spend.dto.SpendSyncRequest;
import org.quwuting.quwutingservice.spend.dto.SpendSyncResponse;
import org.quwuting.quwutingservice.spend.entity.SpendEntryEntity;
import org.quwuting.quwutingservice.spend.enums.SpendCategory;
import org.quwuting.quwutingservice.spend.enums.SpendDirection;
import org.quwuting.quwutingservice.spend.enums.SpendSource;
import org.quwuting.quwutingservice.spend.repository.SpendEntryRepository;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SpendService 单元测试（Mockito，不依赖数据库）。
 *
 * 核心回归目标（2026-09-11 闪屏归零事故，44 号 §21）：**客户端的历史小写载荷
 * （source="dance"）必须被接受**。旧实现直接 SpendSource.valueOf 判它非法 ⇒ 每一条
 * 账目 100% 被拒 ⇒ 生产库 qwt_spend_entries 长期 0 行，而客户端把 rejected 当已处理
 * 清队、状态行却显示"已同步"——最终表现为账本数字 0.1 秒后归零。
 *
 * 三条主线：
 * ① 协议宽容读（大小写 / 首尾空白）—— 存量客户端必须能收敛；
 * ② 非法值严格拒（未知枚举 / 非正金额 / 非法 ts）—— 绝不猜默认值；
 * ③ 逐条归因：部分被拒时 accepted 条目照常落库，rejectedIds 精确点名。
 */
@ExtendWith(MockitoExtension.class)
class SpendServiceTest {

    /** 业务发生时刻（固定值，避免用例依赖当前时间） */
    private static final long TS = 1_760_000_000_000L;

    @Mock
    private SpendEntryRepository spendEntryRepository;

    private SpendService service() {
        return new SpendService(spendEntryRepository);
    }

    /** 合法条目（大写协议值；特殊字段由各用例覆盖；direction 缺省 = 老客户端不带该字段） */
    private SpendEntryItem entry(String clientEntryId, String source, String category) {
        return new SpendEntryItem(clientEntryId, TS, new BigDecimal("88.00"),
                category, source, "", null, null, null, Boolean.FALSE, null);
    }

    private SpendSyncRequest request(SpendEntryItem... items) {
        return new SpendSyncRequest(List.of(items));
    }

    private void stubFreshInsert() {
        when(spendEntryRepository.findByUserIdAndClientEntryId(anyLong(), anyString()))
                .thenReturn(Optional.empty());
        when(spendEntryRepository.save(any(SpendEntryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ── ① 协议宽容读（事故回归） ──────────────────────────────

    @Test
    void testLowercaseSourceAndCategoryAcceptedAsLegacyClientPayload() {
        stubFreshInsert();

        // 事故现场载荷：老客户端上行的是本地领域值（小写 source）
        SpendSyncResponse res = service().sync(1L, request(entry("led-a", "dance", "PARTNER")));

        assertEquals(1, res.accepted());
        assertEquals(0, res.rejected());
        assertTrue(res.rejectedIds().isEmpty());

        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        // 落库的是枚举（枚举名大写），而不是客户端传进来的原始字符串
        assertEquals(SpendSource.DANCE, captor.getValue().getSource());
        assertEquals(SpendCategory.PARTNER, captor.getValue().getCategory());
    }

    @Test
    void testMixedCaseAndSurroundingWhitespaceAccepted() {
        stubFreshInsert();

        SpendSyncResponse res = service().sync(1L, request(entry("led-b", " Manual ", "drink")));

        assertEquals(1, res.accepted());
        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        assertEquals(SpendSource.MANUAL, captor.getValue().getSource());
        assertEquals(SpendCategory.DRINK, captor.getValue().getCategory());
    }

    // ── ② 非法值严格拒（禁猜默认值） ──────────────────────────

    @Test
    void testUnknownEnumValuesRejectedWithoutFallback() {
        SpendSyncResponse res = service().sync(1L, request(
                entry("led-c", "FOO", "PARTNER"),
                entry("led-d", "DANCE", "BAR"),
                entry("led-e", "  ", "PARTNER"),
                entry("led-f", null, "PARTNER")));

        assertEquals(0, res.accepted());
        assertEquals(4, res.rejected());
        assertEquals(List.of("led-c", "led-d", "led-e", "led-f"), res.rejectedIds());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }

    @Test
    void testNonPositiveAmountAndInvalidTsRejected() {
        SpendEntryItem zeroAmount = new SpendEntryItem("led-g", TS, BigDecimal.ZERO,
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null);
        SpendEntryItem negativeAmount = new SpendEntryItem("led-h", TS, new BigDecimal("-1.00"),
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null);
        SpendEntryItem zeroTs = new SpendEntryItem("led-i", 0L, new BigDecimal("10.00"),
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null);

        SpendSyncResponse res = service().sync(1L, request(zeroAmount, negativeAmount, zeroTs));

        assertEquals(0, res.accepted());
        assertEquals(3, res.rejected());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }

    @Test
    void testOversizedClientEntryIdRejected() {
        StringBuilder longId = new StringBuilder();
        for (int i = 0; i < 33; i++) {
            longId.append('x');
        }

        SpendSyncResponse res = service().sync(1L, request(
                entry(longId.toString(), "DANCE", "PARTNER")));

        assertEquals(1, res.rejected());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }

    // ── ③ 逐条归因（部分成功不整体回滚） ──────────────────────

    @Test
    void testPartialRejectionKeepsAcceptedEntriesAndNamesRejectedOnes() {
        stubFreshInsert();

        SpendSyncResponse res = service().sync(7L, request(
                entry("led-ok-1", "dance", "PARTNER"),
                entry("led-bad", "nope", "PARTNER"),
                entry("led-ok-2", "manual", "OTHER")));

        assertEquals(2, res.accepted());
        assertEquals(1, res.rejected());
        assertEquals(List.of("led-bad"), res.rejectedIds());
        // 计数与明细必须自洽——客户端据此做"移出队列 / 留队 / 转死信"三分类
        assertEquals(res.rejected(), res.rejectedIds().size());
        verify(spendEntryRepository, times(2)).save(any(SpendEntryEntity.class));
    }

    @Test
    void testEmptyPayloadReturnsZerosWithoutWrites() {
        SpendSyncResponse res = service().sync(1L, new SpendSyncRequest(List.of()));

        assertEquals(0, res.accepted());
        assertEquals(0, res.rejected());
        assertTrue(res.rejectedIds().isEmpty());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }

    @Test
    void testNullRequestReturnsZerosWithoutWrites() {
        SpendSyncResponse res = service().sync(1L, null);

        assertEquals(0, res.accepted());
        assertEquals(0, res.rejected());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }

    // ── 幂等与软删（既有契约回归） ────────────────────────────

    @Test
    void testSameIdempotencyKeyUpdatesInsteadOfInserting() {
        SpendEntryEntity existing = new SpendEntryEntity();
        existing.setUserId(1L);
        existing.setClientEntryId("led-x");
        existing.setSource(SpendSource.MANUAL);
        existing.setDeleted(Boolean.FALSE);
        when(spendEntryRepository.findByUserIdAndClientEntryId(eq(1L), eq("led-x")))
                .thenReturn(Optional.of(existing));
        when(spendEntryRepository.save(any(SpendEntryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 软删载荷（deleted=true 携带原始 ts/amount）与恢复（deleted=false）走同一路径
        service().sync(1L, request(new SpendEntryItem("led-x", TS, new BigDecimal("66.00"),
                "PARTNER", "DANCE", "rec-1", 9L, "测试门店", 1800, Boolean.TRUE, null)));

        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        SpendEntryEntity saved = captor.getValue();
        assertEquals(1L, saved.getUserId());
        assertEquals("led-x", saved.getClientEntryId());
        assertEquals(SpendSource.DANCE, saved.getSource());
        assertTrue(saved.isDeleted());
        assertNotNull(saved.getTs());
        assertEquals(0, saved.getTs().compareTo(LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(TS), java.time.ZoneId.systemDefault())));
    }

    // ── 方向字段（2026-09-11，44 号 §24：客人=支出 / 舞伴=收入） ──────────────

    @Test
    void testDirectionDefaultsToExpenseForLegacyAndAbsentPayload() {
        stubFreshInsert();

        // 老客户端载荷不带 direction ⇒ 缺省 EXPENSE（存量语义，兼容收敛）
        SpendSyncResponse res = service().sync(1L, request(
                entry("led-dir-a", "dance", "PARTNER")));

        assertEquals(1, res.accepted());
        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        assertEquals(SpendDirection.EXPENSE, captor.getValue().getDirection());
    }

    @Test
    void testIncomeDirectionAcceptedWithRelaxedCase() {
        stubFreshInsert();

        // 舞伴身份结算：小写 income（宽容读，与 source 同判据）→ 落库 INCOME
        SpendEntryItem income = new SpendEntryItem("led-dir-b", TS, new BigDecimal("60.00"),
                "PARTNER", "DANCE", "rec-2", 9L, "测试门店", 2400, Boolean.FALSE, "income");

        SpendSyncResponse res = service().sync(1L, request(income));

        assertEquals(1, res.accepted());
        assertEquals(0, res.rejected());
        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        assertEquals(SpendDirection.INCOME, captor.getValue().getDirection());
    }

    @Test
    void testUnknownDirectionRejectedWithoutFallback() {
        SpendSyncResponse res = service().sync(1L, request(
                new SpendEntryItem("led-dir-c", TS, new BigDecimal("60.00"),
                        "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, "REWARD")));

        assertEquals(0, res.accepted());
        assertEquals(1, res.rejected());
        assertEquals(List.of("led-dir-c"), res.rejectedIds());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }
}
