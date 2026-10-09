package org.quwuting.quwutingservice.spend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quwuting.quwutingservice.spend.SpendCompanions;
import org.quwuting.quwutingservice.spend.SpendEntryLimits;
import org.quwuting.quwutingservice.spend.dto.SpendCompanionItem;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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
                category, source, "", null, null, null, Boolean.FALSE, null, null);
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
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null, null);
        SpendEntryItem negativeAmount = new SpendEntryItem("led-h", TS, new BigDecimal("-1.00"),
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null, null);
        SpendEntryItem zeroTs = new SpendEntryItem("led-i", 0L, new BigDecimal("10.00"),
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null, null);

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

    // ── ②′ 存储约束（2026-10-01 毒丸修复：越界逐条拒，绝不让落库异常回滚整批） ──

    @Test
    void testValuesBeyondColumnLimitsRejectedPerEntryNotPerBatch() {
        stubFreshInsert();
        SpendEntryItem tooLarge = new SpendEntryItem("led-big", TS, new BigDecimal("100000000.00"),
                "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, null, null);
        SpendEntryItem longVenueName = new SpendEntryItem("led-venue", TS, new BigDecimal("10.00"),
                "PARTNER", "DANCE", "", 1L, "店".repeat(SpendEntryLimits.VENUE_NAME_MAX_LENGTH + 1),
                null, Boolean.FALSE, null, null);
        SpendEntryItem longRef = new SpendEntryItem("led-ref", TS, new BigDecimal("10.00"),
                "PARTNER", "DANCE", "r".repeat(SpendEntryLimits.SOURCE_REF_ID_MAX_LENGTH + 1),
                null, null, null, Boolean.FALSE, null, null);

        SpendSyncResponse res = service().sync(1L, request(
                tooLarge, longVenueName, longRef, entry("led-ok", "DANCE", "PARTNER")));

        assertEquals(1, res.accepted(), "合法条目照常落库");
        assertEquals(List.of("led-big", "led-venue", "led-ref"), res.rejectedIds());
        verify(spendEntryRepository, times(1)).save(any(SpendEntryEntity.class));
    }

    @Test
    void testFloatingPointTailIsRoundedToColumnScaleInsteadOfRejected() {
        stubFreshInsert();
        // 客户端表达式求和的浮点尾差（0.1 + 0.2）——是正常账目，不能因小数位多而拒收
        SpendEntryItem floatTail = new SpendEntryItem("led-float", TS, new BigDecimal("0.30000000000000004"),
                "SNACK", "MANUAL", "", null, null, null, Boolean.FALSE, null, null);

        SpendSyncResponse res = service().sync(1L, request(floatTail));

        assertEquals(1, res.accepted());
        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        assertEquals(new BigDecimal("0.30"), captor.getValue().getAmount(), "落库值 = 校验值 = 列精度");
    }

    @Test
    void testAmountThatRoundsToZeroRejected() {
        SpendEntryItem dust = new SpendEntryItem("led-dust", TS, new BigDecimal("0.004"),
                "SNACK", "MANUAL", "", null, null, null, Boolean.FALSE, null, null);
        SpendSyncResponse res = service().sync(1L, request(dust));
        assertEquals(List.of("led-dust"), res.rejectedIds(), "四舍五入后为 0 的金额不是有效账目");
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
                "PARTNER", "DANCE", "rec-1", 9L, "测试门店", 1800, Boolean.TRUE, null, null)));

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
                "PARTNER", "DANCE", "rec-2", 9L, "测试门店", 2400, Boolean.FALSE, "income", null);

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
                        "PARTNER", "DANCE", "", null, null, null, Boolean.FALSE, "REWARD", null)));

        assertEquals(0, res.accepted());
        assertEquals(1, res.rejected());
        assertEquals(List.of("led-dir-c"), res.rejectedIds());
        verify(spendEntryRepository, never()).save(any(SpendEntryEntity.class));
    }

    // ── 一同计时的人（2026-10-09，V47：账目携带同行者快照）──────────────────────

    private SpendEntryItem withCompanions(String id, List<SpendCompanionItem> companions) {
        return new SpendEntryItem(id, TS, new BigDecimal("45.00"), "PARTNER", "DANCE", "rec-9", 9L, "测试门店",
                2700, Boolean.FALSE, "EXPENSE", companions);
    }

    private SpendEntryEntity savedAfterSync(SpendEntryItem item) {
        service().sync(1L, request(item));
        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        return captor.getValue();
    }

    @Test
    void testCompanionsArePersistedAsSnapshotJson() {
        stubFreshInsert();

        SpendEntryEntity saved = savedAfterSync(withCompanions("led-c1", List.of(
                new SpendCompanionItem("王姐", "https://cdn.example/w.png", "HOST"))));

        List<SpendCompanionItem> stored = SpendCompanions.parse(saved.getCompanionsJson());
        assertEquals(1, stored.size());
        assertEquals("王姐", stored.get(0).nickname());
        assertEquals("HOST", stored.get(0).relation());
    }

    @Test
    void testInvalidCompanionNeverRejectsTheMoneyRecord() {
        stubFreshInsert();

        // 同行者里夹一个关系非法的项 + 一个超长头像：账目照常 accepted，坏项丢弃 / 置空
        SpendSyncResponse res = service().sync(1L, request(withCompanions("led-c2", List.of(
                new SpendCompanionItem("坏项", null, "STRANGER"),
                new SpendCompanionItem("好项", "https://cdn.example/" + "a".repeat(600), "JOINER")))));

        assertEquals(1, res.accepted(), "元数据非法绝不拒绝一笔账（毒丸教训：被拒的账客户端会留队重发，永远上不了云）");
        assertEquals(0, res.rejected());
        ArgumentCaptor<SpendEntryEntity> captor = ArgumentCaptor.forClass(SpendEntryEntity.class);
        verify(spendEntryRepository).save(captor.capture());
        List<SpendCompanionItem> stored = SpendCompanions.parse(captor.getValue().getCompanionsJson());
        assertEquals(1, stored.size());
        assertEquals("好项", stored.get(0).nickname());
        assertNull(stored.get(0).avatarUrl());
    }

    @Test
    void testAbsentCompanionsKeepExistingAndEmptyListClears() {
        SpendEntryEntity existing = new SpendEntryEntity();
        existing.setUserId(1L);
        existing.setClientEntryId("led-c3");
        existing.setCompanionsJson(SpendCompanions.serialize(List.of(
                new SpendCompanionItem("王姐", null, "HOST"))));
        when(spendEntryRepository.findByUserIdAndClientEntryId(eq(1L), eq("led-c3")))
                .thenReturn(Optional.of(existing));
        when(spendEntryRepository.save(any(SpendEntryEntity.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // 老客户端重传同一条账（不带 companions）：不得抹掉新版本写下的同行者
        service().sync(1L, request(withCompanions("led-c3", null)));
        assertEquals(1, SpendCompanions.parse(existing.getCompanionsJson()).size(), "null = 没带 → 保留");

        // 明确置空（空数组）才清
        service().sync(1L, request(withCompanions("led-c3", List.of())));
        assertNull(existing.getCompanionsJson(), "空数组 = 明确置空 → 列 NULL");
    }

    @Test
    void testIncrementalPullReturnsCompanionsAndNeverNull() {
        SpendEntryEntity withPeers = new SpendEntryEntity();
        withPeers.setUserId(1L);
        withPeers.setClientEntryId("led-c4");
        withPeers.setTs(LocalDateTime.of(2026, 10, 9, 21, 0));
        withPeers.setAmount(new BigDecimal("45.00"));
        withPeers.setCategory(SpendCategory.PARTNER);
        withPeers.setSource(SpendSource.DANCE);
        withPeers.setDirection(SpendDirection.EXPENSE);
        withPeers.setCompanionsJson(SpendCompanions.serialize(List.of(new SpendCompanionItem("王姐", null, "HOST"))));
        SpendEntryEntity plain = new SpendEntryEntity();
        plain.setUserId(1L);
        plain.setClientEntryId("led-c5");
        plain.setTs(LocalDateTime.of(2026, 10, 9, 22, 0));
        plain.setAmount(new BigDecimal("10.00"));
        plain.setCategory(SpendCategory.OTHER);
        plain.setSource(SpendSource.MANUAL);
        plain.setDirection(SpendDirection.EXPENSE);
        when(spendEntryRepository.findIncremental(eq(1L), any(), any())).thenReturn(List.of(withPeers, plain));

        var page = service().entries(1L, 0L);

        assertEquals(2, page.entries().size());
        assertEquals("王姐", page.entries().get(0).companions().get(0).nickname());
        assertEquals(List.of(), page.entries().get(1).companions(), "没有同行者 = 空数组而非 null：客户端不必区分「没有」与「缺字段」");
    }
}
