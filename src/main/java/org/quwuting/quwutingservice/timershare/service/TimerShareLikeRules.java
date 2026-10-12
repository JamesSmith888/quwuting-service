package org.quwuting.quwutingservice.timershare.service;

import org.quwuting.quwutingservice.timershare.enums.TimerShareRole;

import java.util.ArrayList;
import java.util.List;

/**
 * 「谁可以赞谁、什么时候还来得及」的<b>唯一判定处</b>（2026-10-12，V48；纯函数、零 Spring / DB 依赖，可单测；
 * 文档 = docs/agents/54-timer-share.md §十五）。
 *
 * <h3>准入（扫码在场就是凭据）</h3>
 * 只有同一张会话里的<b>主持方</b>与<b>加入者</b>能互赞：
 * <ul>
 *   <li>调用者是主持方 → 能赞的是已加入的第 N 位加入者（{@code seq} 1 起 = 界面「第 N 位」）；</li>
 *   <li>调用者是加入者 → 能赞的只有主持方；<b>加入者之间不开放</b>（互不相识的人之间不造互动通道）；</li>
 *   <li>两者都不是 → 不是成员（返回 null，调用方统一当「不存在」，不泄露 token 是否存在）。</li>
 * </ul>
 *
 * <h3>为什么「读状态」与「写」共用同一个入口 {@link #pairsOf}</h3>
 * 读（{@code GET likes}）要列出「调用者面前有几个能赞的人」，写（{@code POST likes}）要校验「这个目标是不是其中之一」。
 * 如果两处各写一份推导，迟早出现「界面给了按钮、点下去说不是成员」或反过来的裂缝——所以写只是在读的结果里
 * {@link #find 找一项}，不再自己推一遍。
 *
 * <h3>窗口</h3>
 * 见 {@link TimerSharePolicy#LIKE_WINDOW_MS}：该对最近一次活动后 24 小时。
 */
public final class TimerShareLikeRules {

    private TimerShareLikeRules() {
    }

    /** 会话的判定所需事实（不含 token / 规则等与点赞无关的列） */
    public record ShareFact(long hostUserId, long snapshotAtMs, Long hostSettledAtMs) {
    }

    /** 一位加入者的判定所需事实（按加入先后传入，顺序即 seq） */
    public record JoinFact(long userId, long joinedAtMs, Long settledAtMs) {
    }

    /**
     * 调用者面前的一位「可赞对象」。
     *
     * @param role        对方的角色（HOST / JOINER）
     * @param seq         对方是加入者时 = 第几位（1 起）；对方是主持方 = 0
     * @param otherUserId 对方的用户 id（仅服务端内部，不下发）
     * @param anchorMs    该对最近一次活动的服务端时刻（窗口起算点）
     */
    public record Pair(TimerShareRole role, int seq, long otherUserId, long anchorMs) {
    }

    /**
     * 调用者在这张会话里能赞的全部对象。<b>返回 null = 调用者不是这张会话的成员</b>
     * （与「成员但暂时没有可赞对象」的空列表区分：主持方还没人加入时是空列表，不是 null）。
     */
    public static List<Pair> pairsOf(long callerId, ShareFact share, List<JoinFact> joinsInOrder) {
        if (callerId == share.hostUserId()) {
            List<Pair> out = new ArrayList<>(joinsInOrder.size());
            for (int i = 0; i < joinsInOrder.size(); i++) {
                JoinFact join = joinsInOrder.get(i);
                out.add(new Pair(TimerShareRole.JOINER, i + 1, join.userId(), anchorMs(share, join)));
            }
            return out;
        }
        for (JoinFact join : joinsInOrder) {
            if (join.userId() == callerId) {
                return List.of(new Pair(TimerShareRole.HOST, 0, share.hostUserId(), anchorMs(share, join)));
            }
        }
        return null;
    }

    /** 在可赞对象里找指定目标（角色 + 序号完全匹配）；找不到 → null */
    public static Pair find(List<Pair> pairs, TimerShareRole role, int seq) {
        for (Pair pair : pairs) {
            if (pair.role() == role && pair.seq() == seq) {
                return pair;
            }
        }
        return null;
    }

    /**
     * 该对最近一次活动的服务端时刻 = 会话最近创建 / 刷新、该加入者加入、主持方结算、该加入者结算 四者最大值。
     * 缺失的结算时刻（尚未结算）不参与。
     */
    static long anchorMs(ShareFact share, JoinFact join) {
        long anchor = Math.max(share.snapshotAtMs(), join.joinedAtMs());
        if (share.hostSettledAtMs() != null) {
            anchor = Math.max(anchor, share.hostSettledAtMs());
        }
        if (join.settledAtMs() != null) {
            anchor = Math.max(anchor, join.settledAtMs());
        }
        return anchor;
    }

    /** 此刻该对还在点赞窗口内（含边界） */
    public static boolean withinWindow(Pair pair, long nowMs) {
        return nowMs <= pair.anchorMs() + TimerSharePolicy.LIKE_WINDOW_MS;
    }
}
