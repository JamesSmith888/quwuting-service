package org.quwuting.quwutingservice.user.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轨迹同类连发合并器 + 活跃分层分类器的口径门禁（纯函数，零依赖）。
 * <p>
 * 合并器的数据形态取自 2026-10-09 的生产实测：快讯浏览 92% 的行落在『同用户同分钟 4 条以上』的批次里。
 */
class BehaviorTimelineMergerTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 8, 21, 30, 0);

    private static BehaviorTimelineMerger.Raw raw(UserBehaviorEvent event, LocalDateTime at, Long ref) {
        return new BehaviorTimelineMerger.Raw(event, at, at.toLocalDate(), false, ref, null);
    }

    @Test
    void feedLoadBurstCollapsesIntoOneRow() {
        // 一次打开快讯页：同一秒写入 7 条（时间倒序输入）
        List<BehaviorTimelineMerger.Raw> in = new ArrayList<>();
        for (int i = 0; i < 7; i++) {
            in.add(raw(UserBehaviorEvent.BULLETIN_VIEW, T0.minusSeconds(i), 100L + i));
        }
        List<BehaviorTimelineMerger.Burst> out = BehaviorTimelineMerger.merge(in);
        assertEquals(1, out.size(), "同类同刻 7 条必须合并成一行，否则一次操作刷满一屏");
        assertEquals(7, out.getFirst().count());
        assertEquals(T0, out.getFirst().latest().happenedAt(), "合并行展示的是批内最晚时刻");
        assertEquals(T0.minusSeconds(6), out.getFirst().earliest().happenedAt());
    }

    @Test
    void gapOverFiveMinutesStartsANewBurst() {
        List<BehaviorTimelineMerger.Raw> in = List.of(
                raw(UserBehaviorEvent.VENUE_VIEW, T0, 1L),
                raw(UserBehaviorEvent.VENUE_VIEW, T0.minusMinutes(5), 2L),          // 恰好 5 分钟：并入
                raw(UserBehaviorEvent.VENUE_VIEW, T0.minusMinutes(5).minusSeconds(1).minusMinutes(5), 3L), // 距上一条 5 分 1 秒：新批
                raw(UserBehaviorEvent.VENUE_VIEW, T0.minusMinutes(11), 4L));        // 距上一条 ≤5 分：并入第二批
        List<BehaviorTimelineMerger.Burst> out = BehaviorTimelineMerger.merge(in);
        assertEquals(2, out.size());
        assertEquals(2, out.get(0).count());
        assertEquals(2, out.get(1).count(), "链式合并：只看与批内上一条的间隔");
    }

    @Test
    void interleavedOtherTypesDoNotBreakABurst() {
        // 浏览 → 分享 → 浏览：分享不应把浏览拆成两行
        List<BehaviorTimelineMerger.Raw> in = List.of(
                raw(UserBehaviorEvent.VENUE_VIEW, T0, 1L),
                raw(UserBehaviorEvent.VENUE_SHARE, T0.minusSeconds(30), 1L),
                raw(UserBehaviorEvent.VENUE_VIEW, T0.minusSeconds(60), 2L));
        List<BehaviorTimelineMerger.Burst> out = BehaviorTimelineMerger.merge(in);
        assertEquals(2, out.size());
        assertEquals(UserBehaviorEvent.VENUE_VIEW, out.get(0).event());
        assertEquals(2, out.get(0).count());
        assertEquals(UserBehaviorEvent.VENUE_SHARE, out.get(1).event());
        assertEquals(1, out.get(1).count());
    }

    @Test
    void differentTypesAtTheSameInstantStaySeparateRows() {
        List<BehaviorTimelineMerger.Raw> in = List.of(
                raw(UserBehaviorEvent.VENUE_VIEW, T0, 1L),
                raw(UserBehaviorEvent.VENUE_FAVORITE, T0, 1L));
        assertEquals(2, BehaviorTimelineMerger.merge(in).size(),
                "浏览与收藏是两件事，即使同一秒也不能合并");
    }

    @Test
    void rowsWithoutExactTimeAreNeverMerged() {
        BehaviorTimelineMerger.Raw a = new BehaviorTimelineMerger.Raw(UserBehaviorEvent.VENUE_VIEW,
                T0.toLocalDate().atStartOfDay(), T0.toLocalDate(), true, 1L, null);
        BehaviorTimelineMerger.Raw b = new BehaviorTimelineMerger.Raw(UserBehaviorEvent.VENUE_VIEW,
                T0.toLocalDate().atStartOfDay(), T0.toLocalDate(), true, 2L, null);
        assertEquals(2, BehaviorTimelineMerger.merge(List.of(a, b)).size(),
                "没有精确时刻就谈不上『连发』，不得伪造间隔去合并");
    }

    @Test
    void totalEventCountIsPreserved() {
        List<BehaviorTimelineMerger.Raw> in = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            UserBehaviorEvent type = i % 3 == 0 ? UserBehaviorEvent.BULLETIN_VIEW
                    : (i % 3 == 1 ? UserBehaviorEvent.VENUE_VIEW : UserBehaviorEvent.MESSAGE);
            in.add(raw(type, T0.minusMinutes(i * 2L), (long) i));
        }
        int merged = BehaviorTimelineMerger.merge(in).stream().mapToInt(BehaviorTimelineMerger.Burst::count).sum();
        assertEquals(in.size(), merged, "合并只改展示行数，绝不能增删原始事件");
    }

    // ── 分层分类器 ────────────────────────────────────────────────────────────

    @Test
    void segmentUsesAvailableDaysNotAbsoluteDays() {
        // 注册 2 天：无论多活跃都只能是『新近注册』（观测期不足）
        assertEquals(BehaviorSegment.NEW, BehaviorSegment.classify(2, 2, 2, 0));
        // 可用 30 天、活跃 15 天 = 50% → 高频
        assertEquals(BehaviorSegment.HIGH, BehaviorSegment.classify(30, 15, 20, 0));
        // 25% 恰为常规下界
        assertEquals(BehaviorSegment.REGULAR, BehaviorSegment.classify(28, 7, 10, 0));
        assertEquals(BehaviorSegment.LOW, BehaviorSegment.classify(30, 3, 10, 0));
    }

    @Test
    void extendedOnlyIsSplitOutOfOpenOnly() {
        // 无活跃口径内行为：只有打卡 → 巡检形态；打卡 + 扩展行为 → 真实使用
        assertEquals(BehaviorSegment.OPEN_ONLY, BehaviorSegment.classify(30, 0, 12, 0));
        assertEquals(BehaviorSegment.EXTENDED_ONLY, BehaviorSegment.classify(30, 0, 12, 3),
                "只看快讯 / 只记账的真实用户不得被判成『审核/巡检形态』");
        assertEquals(BehaviorSegment.EXTENDED_ONLY, BehaviorSegment.classify(30, 0, 0, 1),
                "有扩展行为就一定打开过，即使打卡记录缺失也不应落入『完全沉默』");
        assertEquals(BehaviorSegment.DORMANT, BehaviorSegment.classify(30, 0, 0, 0));
    }

    @Test
    void activeBehaviorAlwaysWinsOverExtended() {
        assertEquals(BehaviorSegment.LOW, BehaviorSegment.classify(30, 1, 5, 20),
                "有主动行为时按活跃比例分档，扩展行为不参与比例（它不计入活跃口径）");
    }

    @Test
    void everySegmentHasLabelAndHint() {
        for (BehaviorSegment seg : BehaviorSegment.values()) {
            assertTrue(!seg.label().isBlank() && !seg.hint().isBlank(), seg + " 缺少文案");
        }
    }
}
