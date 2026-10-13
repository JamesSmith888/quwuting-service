package org.quwuting.quwutingservice.timershare.service;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.junit.jupiter.api.Test;
import org.quwuting.quwutingservice.timershare.dto.request.CreateTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.request.SetTimerShareLikeRequest;
import org.quwuting.quwutingservice.timershare.dto.request.SettleTimerShareRequest;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeHistoryResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikeSetResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareLikesResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareJoinResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareJoinView;
import org.quwuting.quwutingservice.timershare.dto.response.TimerSharePeerResponse;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareProfileView;
import org.quwuting.quwutingservice.timershare.dto.response.TimerShareRuleView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 线上 JSON 形态契约（V42）。前端 {@code types/timerShare.ts} 与 {@code services/timerShare.ts}
 * 依赖这里锁定的形状，而这个形状的生成受<b>全局</b> Jackson 配置影响：
 * {@code spring.jackson.default-property-inclusion: non_null} 会把 null 字段整个删掉。
 * 本测试用同一口径构造 mapper，钉住两件最容易被全局配置悄悄改坏的事：
 * ① 带 null 语义的字段（snapshot / pausedAtServerMs / venue）必须显式写出 null；
 * ② 缺省的计费模式必须省略，而不是写成 {@code "mode":null}。
 */
class TimerShareWireFormatTest {

    private static final JsonMapper APP_MAPPER = JsonMapper.builder()
            .changeDefaultPropertyInclusion(v -> v.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void clientRequestDeserializesIntoTheStronglyTypedRecord() {
        String body = "{\"sessionKey\":\"1780000000000:m1\",\"wallElapsedMs\":1530400,\"netElapsedSeconds\":1230,"
                + "\"running\":true,\"rule\":{\"tiers\":[{\"durationMinutes\":4,\"price\":20},"
                + "{\"durationMinutes\":60,\"price\":300,\"mode\":\"linear\"}]},\"venueId\":42,"
                + "\"parentToken\":\"AbC123xyz0\",\"ruleName\":\"我的私人规则\",\"unknownField\":1}";

        CreateTimerShareRequest req = APP_MAPPER.readValue(body, CreateTimerShareRequest.class);

        assertEquals("1780000000000:m1", req.sessionKey());
        assertEquals(1530400L, req.wallElapsedMs());
        assertEquals(1230, req.netElapsedSeconds());
        assertTrue(req.running());
        assertEquals(2, req.rule().tiers().size());
        assertEquals(4.0, req.rule().tiers().get(0).durationMinutes(), "整数 JSON 数字可读入 Double");
        assertNull(req.rule().tiers().get(0).mode());
        assertEquals("linear", req.rule().tiers().get(1).mode());
        assertEquals(42L, req.venueId());
        assertEquals("AbC123xyz0", req.parentToken());
    }

    @Test
    void ruleNameSentByAClientNeverSurvivesNormalization() {
        // 客户端（哪怕是恶意的）多塞一个 ruleName：解析时被丢弃，规整并序列化后库里和线上都没有它
        String body = "{\"sessionKey\":\"k\",\"wallElapsedMs\":1000,\"netElapsedSeconds\":1,\"running\":true,"
                + "\"rule\":{\"name\":\"加微信xxx\",\"tiers\":[{\"durationMinutes\":4,\"price\":20,\"label\":\"广告\"}]}}";

        CreateTimerShareRequest req = APP_MAPPER.readValue(body, CreateTimerShareRequest.class);
        String stored = TimerShareRules.serialize(TimerShareRules.normalize(req.rule()));

        assertFalse(stored.contains("加微信"));
        assertFalse(stored.contains("广告"));
        assertEquals("{\"tiers\":[{\"durationMinutes\":4.0,\"price\":20.0}]}", stored);
    }

    @Test
    void joinedResponseWritesExplicitNullsForSemanticallyNullFields() {
        TimerShareJoinResponse resp = new TimerShareJoinResponse("JOINED", 1780000000123L,
                new TimerShareJoinResponse.Snapshot(1779999400000L, 60, null,
                        new TimerShareRuleView(List.of(new TimerShareRuleView.Tier(4.0, 20.0, null))), null),
                null);

        JsonNode json = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(resp));

        assertEquals("JOINED", json.get("outcome").asString());
        JsonNode snapshot = json.get("snapshot");
        assertTrue(snapshot.has("pausedAtServerMs") && snapshot.get("pausedAtServerMs").isNull(),
                "「没在暂停」必须是显式 null，不能是字段消失（客户端用 null 与 undefined 区分「没这个字段」与「没有值」）");
        assertTrue(snapshot.has("venue") && snapshot.get("venue").isNull());
        assertEquals(60, snapshot.get("excludedSeconds").asInt());
        JsonNode tier = snapshot.get("rule").get("tiers").get(0);
        assertFalse(tier.has("mode"), "档位式缺省不写出 mode");
        assertEquals(4.0, tier.get("durationMinutes").asDouble());
        assertTrue(json.has("host") && json.get("host").isNull(), "非成功加入时主持方资料显式为 null（V45）");
    }

    @Test
    void joinResponseWritesHostProfileIncludingInnerNulls() {
        TimerShareJoinResponse resp = new TimerShareJoinResponse("ALREADY_JOINED", 1780000000123L,
                new TimerShareJoinResponse.Snapshot(1779999400000L, 60, null,
                        new TimerShareRuleView(List.of(new TimerShareRuleView.Tier(4.0, 20.0, null))), null),
                new TimerShareProfileView("王姐", null));

        JsonNode json = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(resp));

        JsonNode host = json.get("host");
        assertEquals("王姐", host.get("nickname").asString());
        assertTrue(host.has("avatarUrl") && host.get("avatarUrl").isNull(),
                "「没设头像」必须是显式 null——客户端以此决定首字占位（V45）");
    }

    @Test
    void peerResponseAlwaysWritesHostAndSettlementFields() {
        TimerSharePeerResponse resp = new TimerSharePeerResponse("CLOSED", null, null, null, 1780000000123L);

        JsonNode json = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(resp));

        assertEquals("CLOSED", json.get("status").asString());
        assertTrue(json.has("host") && json.get("host").isNull());
        assertTrue(json.has("hostSettledAtMs") && json.get("hostSettledAtMs").isNull(),
                "未结算 → 显式 null（客户端以 null 判「还没结算」，不能是字段消失）");
        assertTrue(json.has("hostSettledNetSeconds") && json.get("hostSettledNetSeconds").isNull());
        assertEquals(1780000000123L, json.get("serverNowMs").asLong());
    }

    @Test
    void joinViewAlwaysWritesSettlementAndJoinTimeFields() {
        // 加入者尚未结算 + 加入时刻缺失：两个「无值」都必须是显式 null（客户端以 null 判「没结算 / 不展示该行」）
        TimerShareJoinView view = new TimerShareJoinView(1, "小李", null, null, null, null);

        JsonNode json = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(view));

        assertEquals(1, json.get("seq").asInt());
        assertTrue(json.has("avatarUrl") && json.get("avatarUrl").isNull());
        assertTrue(json.has("settledAtMs") && json.get("settledAtMs").isNull());
        assertTrue(json.has("settledNetSeconds") && json.get("settledNetSeconds").isNull());
        assertTrue(json.has("joinedAtMs") && json.get("joinedAtMs").isNull(),
                "joinedAtMs（2026-10-09）同属 ALWAYS 契约：缺失必须是显式 null，不能是字段消失");
        assertFalse(json.has("userId"), "不向主持方下发加入者 userId（V45 口径）");
    }

    @Test
    void settleRequestWithoutAgeStillDeserializesForOldClients() {
        // 老客户端（V45 形态）不带 settledAgoMs：必须仍能读入，ago 为 null（服务端按 0 处理 = 即时上报）
        SettleTimerShareRequest old = APP_MAPPER.readValue("{\"netElapsedSeconds\":2700}", SettleTimerShareRequest.class);
        assertEquals(2700, old.netElapsedSeconds());
        assertNull(old.settledAgoMs());

        SettleTimerShareRequest fresh = APP_MAPPER.readValue(
                "{\"netElapsedSeconds\":2700,\"settledAgoMs\":180000}", SettleTimerShareRequest.class);
        assertEquals(180000L, fresh.settledAgoMs());
    }

    @Test
    void responseWithoutSnapshotStillWritesTheNull() {
        JsonNode json = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(
                TimerShareJoinResponse.withoutSnapshot("EXPIRED", 1780000000123L)));
        assertTrue(json.has("snapshot") && json.get("snapshot").isNull());
        assertEquals("EXPIRED", json.get("outcome").asString());
        assertEquals(1780000000123L, json.get("serverNowMs").asLong());
    }

    @Test
    void linearModeIsWrittenOutWhenPresent() {
        String json = APP_MAPPER.writeValueAsString(new TimerShareRuleView(
                List.of(new TimerShareRuleView.Tier(60.0, 300.0, "linear"))));
        assertTrue(json.contains("\"mode\":\"linear\""));
    }

    @Test
    void likeRequestDeserializesLeniently() {
        // 主持方点第 2 位加入者；多余字段被忽略；缺失字段保持 null（由服务端判 INVALID_TARGET，不猜默认值）
        SetTimerShareLikeRequest req = APP_MAPPER.readValue(
                "{\"relation\":\"JOINER\",\"seq\":2,\"liked\":true,\"userId\":99}", SetTimerShareLikeRequest.class);
        assertEquals("JOINER", req.relation());
        assertEquals(2, req.seq());
        assertEquals(true, req.liked());

        SetTimerShareLikeRequest bare = APP_MAPPER.readValue("{}", SetTimerShareLikeRequest.class);
        assertNull(bare.relation());
        assertNull(bare.seq());
        assertNull(bare.liked());
    }

    @Test
    void likeResponsesCarryNoUserIdentifiersAndAlwaysWriteTheBooleans() {
        // 全局 non_null 只删 null；布尔恒写出（false 不是 null）。且任何响应都不含 userId / likedUserId（V45 口径）
        JsonNode likes = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(new TimerShareLikesResponse(true,
                List.of(new TimerShareLikesResponse.Item("JOINER", 1, false, false, false)), 1780000000123L)));
        JsonNode item = likes.get("items").get(0);
        assertTrue(item.has("likedByMe") && !item.get("likedByMe").asBoolean());
        assertTrue(item.has("likesMe") && !item.get("likesMe").asBoolean());
        assertTrue(item.has("likeable") && !item.get("likeable").asBoolean());
        assertEquals("JOINER", item.get("relation").asString());
        assertFalse(item.has("userId") || item.has("likedUserId") || item.has("likerId"));

        JsonNode set = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(
                new TimerShareLikeSetResponse("WINDOW_CLOSED", false, 1780000000123L)));
        assertEquals("WINDOW_CLOSED", set.get("outcome").asString());
        assertTrue(set.has("liked") && !set.get("liked").asBoolean());
        assertFalse(set.has("userId"));
    }

    @Test
    void likeHistoryCarriesNoUserIdentifiersAndWritesNullProfileFieldsExplicitly() {
        TimerShareLikeHistoryResponse res = new TimerShareLikeHistoryResponse(true, "PEER",
                new TimerShareLikeHistoryResponse.Summary(2, 1, 1),
                List.of(new TimerShareLikeHistoryResponse.Group(true, null, null, 2, 1, 1780000000123L,
                        List.of(new TimerShareLikeHistoryResponse.Event("RECEIVED", 1780000000123L)))),
                false, 1780000000999L);

        JsonNode json = APP_MAPPER.readTree(APP_MAPPER.writeValueAsString(res));
        JsonNode group = json.get("groups").get(0);

        // 昵称 / 头像未设置 = 显式 null（客户端兜底占位），不是缺字段
        assertTrue(group.has("nickname") && group.get("nickname").isNull());
        assertTrue(group.has("avatarUrl") && group.get("avatarUrl").isNull());
        assertTrue(group.has("me") && group.get("me").asBoolean());
        assertEquals("PEER", json.get("subject").asString());
        assertEquals("RECEIVED", group.get("events").get(0).get("direction").asString());
        assertTrue(json.has("truncated") && !json.get("truncated").asBoolean());
        String all = json.toString();
        for (String banned : List.of("userId", "likerId", "likedUserId", "peerUserId")) {
            assertFalse(all.contains(banned), "点赞记录响应不得含用户标识：" + banned);
        }
    }
}
