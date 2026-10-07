package org.quwuting.quwutingservice.timershare.service;

import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest.RuleInput;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest.TierInput;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareRuleView;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 分享规则的白名单与往返（V42；纯静态，无 Spring / DB）。
 * <p>
 * 这里守的是「用户 → 用户」通道的两条底线：① 库里与线上只可能出现数字（不含规则名等自由文本，
 * 否则就是 UGC 公开展示）；② 接收方会拿这些数去做除法与取整，所以非有限数 / 越界值必须在入口被拒。
 */
class TimerShareRulesTest {

    private static RuleInput rule(TierInput... tiers) {
        return new RuleInput(List.of(tiers));
    }

    private static TierInput tier(Double minutes, Double price, String mode) {
        return new TierInput(minutes, price, mode);
    }

    @Test
    void stepIsTheImplicitDefaultAndIsNotWrittenOut() {
        TimerShareRuleView view = TimerShareRules.normalize(rule(tier(4.0, 20.0, null), tier(3.5, 15.0, "step")));
        assertNull(view.tiers().get(0).mode());
        assertNull(view.tiers().get(1).mode(), "显式 step 也规整成缺省（前端约定：禁把缺省写成显式 'step' 落盘）");
        assertFalse(TimerShareRules.serialize(view).contains("mode"), "缺省不出现在 JSON 里");
    }

    @Test
    void linearModeSurvivesAndIsCaseInsensitive() {
        TimerShareRuleView view = TimerShareRules.normalize(rule(tier(60.0, 300.0, " Linear ")));
        assertEquals("linear", view.tiers().get(0).mode());
        assertTrue(TimerShareRules.serialize(view).contains("\"mode\":\"linear\""));
    }

    @Test
    void unknownModeIsRejectedNotGuessed() {
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareRules.normalize(rule(tier(4.0, 20.0, "prorated"))));
    }

    @Test
    void serializedFormContainsOnlyTheThreeNumericFields() {
        String json = TimerShareRules.serialize(TimerShareRules.normalize(rule(tier(4.0, 20.0, "linear"))));
        // 只有 tiers / durationMinutes / price / mode：没有任何可承载规则名的键
        assertEquals("{\"tiers\":[{\"durationMinutes\":4.0,\"price\":20.0,\"mode\":\"linear\"}]}", json);
    }

    @Test
    void roundTripPreservesEveryTier() {
        TimerShareRuleView original = TimerShareRules.normalize(
                rule(tier(4.0, 20.0, null), tier(60.0, 300.0, "linear"), tier(0.5, 0.0, null)));
        TimerShareRuleView parsed = TimerShareRules.parseOrNull(TimerShareRules.serialize(original));
        assertNotNull(parsed);
        assertEquals(original, parsed);
    }

    @Test
    void rejectsEmptyMissingAndOversizedRules() {
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(null));
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(new RuleInput(null)));
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(new RuleInput(List.of())));

        List<TierInput> tooMany = new ArrayList<>();
        for (int i = 0; i <= TimerSharePolicy.RULE_MAX_TIERS; i++) {
            tooMany.add(tier(4.0 + i, 20.0, null));
        }
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(new RuleInput(tooMany)));
        // 恰好上限是合法的
        TimerShareRules.normalize(new RuleInput(tooMany.subList(0, TimerSharePolicy.RULE_MAX_TIERS)));
    }

    @Test
    void rejectsMissingNonFiniteAndOutOfRangeNumbers() {
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(rule(tier(null, 20.0, null))));
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(rule(tier(4.0, null, null))));
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareRules.normalize(rule(tier(Double.NaN, 20.0, null))));
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareRules.normalize(rule(tier(4.0, Double.POSITIVE_INFINITY, null))));
        // 档长必须 > 0（接收方会做除法）
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(rule(tier(0.0, 20.0, null))));
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(rule(tier(-4.0, 20.0, null))));
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareRules.normalize(rule(tier(TimerSharePolicy.TIER_MAX_MINUTES + 1, 20.0, null))));
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(rule(tier(4.0, -1.0, null))));
        assertThrows(IllegalArgumentException.class,
                () -> TimerShareRules.normalize(rule(tier(4.0, TimerSharePolicy.TIER_MAX_PRICE + 1, null))));
        // 价格 0 是合法的（免费档），不能被当成缺失
        TimerShareRules.normalize(rule(tier(4.0, 0.0, null)));
    }

    @Test
    void rejectsNullTierEntry() {
        List<TierInput> withNull = new ArrayList<>();
        withNull.add(null);
        assertThrows(IllegalArgumentException.class, () -> TimerShareRules.normalize(new RuleInput(withNull)));
    }

    @Test
    void parseOrNullNeverThrowsOnCorruptedStorage() {
        assertNull(TimerShareRules.parseOrNull(null));
        assertNull(TimerShareRules.parseOrNull("   "));
        assertNull(TimerShareRules.parseOrNull("not json"));
        assertNull(TimerShareRules.parseOrNull("{\"tiers\":[]}"), "空档位视作损坏");
        assertNull(TimerShareRules.parseOrNull("{\"other\":1}"));
    }
}
