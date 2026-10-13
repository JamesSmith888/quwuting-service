package org.quwuting.quwutingservice.timershare.service;

import org.quwuting.quwutingservice.timershare.enums.TimerShareLikeDirection;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 点赞记录的「按人分组」——<b>唯一的聚合处</b>（2026-10-13；纯函数、零 Spring / DB 依赖，可单测；
 * 文档 = docs/agents/55-timer-share-likes.md §八）。
 *
 * <h3>只以「记录主人」为中心的聚合</h3>
 * 输入是「主人是赞者或被赞者」的有效点赞行；输出每组 = 主人面前的<b>一位</b>对方，计数两个：
 * 对方赞过主人几次（{@code received}）/ 主人赞过对方几次（{@code given}）。
 * 主人 = 调用者本人，或调用者点开的那位同行者（由服务层决定，本类不关心）。<b>没有</b>全站聚合量，
 * 也没有排名 / 积分：一个主人的记录就是一份时间线，仅此而已。
 *
 * <h3>顺序</h3>
 * 分组按「最近一次点赞时间」倒序（并列时按对方 id 升序，保证稳定）；每组内事件新→旧，最多
 * {@code eventsPerGroup} 条。输入必须<b>已按时间新→旧</b>排好（读路径的 ORDER BY 负责）。
 */
public final class TimerShareLikeHistoryRules {

    private TimerShareLikeHistoryRules() {
    }

    /** 一条有效点赞行的判定所需事实（{@code atMs} = 这次点赞的服务端时刻） */
    public record LikeFact(long likerId, long likedUserId, long atMs) {
    }

    /** 一条事件（相对主人的方向 + 时刻） */
    public record EventFact(TimerShareLikeDirection direction, long atMs) {
    }

    /** 主人面前的一位对方（{@code peerUserId} 仅服务端内部，不下发） */
    public record GroupFact(long peerUserId, int received, int given, long lastAtMs, List<EventFact> events) {
    }

    /**
     * 与主人无关的行（既不是他赞的、也不是赞他的）被跳过——防御：读路径本来不会取到它们，但本函数不依赖这个前提；
     * 自己赞自己的行（理论上不存在，准入已拒）同样跳过。
     */
    public static List<GroupFact> group(long subjectId, List<LikeFact> newestFirst, int eventsPerGroup) {
        Map<Long, Acc> byPeer = new LinkedHashMap<>();
        for (LikeFact fact : newestFirst) {
            boolean given = fact.likerId() == subjectId;
            boolean received = fact.likedUserId() == subjectId;
            if (given == received) {
                continue;
            }
            long peer = given ? fact.likedUserId() : fact.likerId();
            byPeer.computeIfAbsent(peer, Acc::new).add(
                    given ? TimerShareLikeDirection.GIVEN : TimerShareLikeDirection.RECEIVED, fact.atMs(), eventsPerGroup);
        }
        List<GroupFact> out = new ArrayList<>(byPeer.size());
        for (Acc acc : byPeer.values()) {
            out.add(new GroupFact(acc.peer, acc.received, acc.given, acc.lastAtMs, List.copyOf(acc.events)));
        }
        out.sort(Comparator.comparingLong(GroupFact::lastAtMs).reversed().thenComparingLong(GroupFact::peerUserId));
        return out;
    }

    /** 总览：收到 / 送出 / 人数（取所有分组，不受分组数上限影响） */
    public static int[] totals(List<GroupFact> groups) {
        int received = 0;
        int given = 0;
        for (GroupFact group : groups) {
            received += group.received();
            given += group.given();
        }
        return new int[]{received, given, groups.size()};
    }

    private static final class Acc {
        final long peer;
        int received;
        int given;
        long lastAtMs;
        final List<EventFact> events = new ArrayList<>();

        Acc(Long peer) {
            this.peer = peer;
        }

        void add(TimerShareLikeDirection direction, long atMs, int cap) {
            if (direction == TimerShareLikeDirection.GIVEN) {
                given++;
            } else {
                received++;
            }
            lastAtMs = Math.max(lastAtMs, atMs);
            if (events.size() < cap) {
                events.add(new EventFact(direction, atMs));
            }
        }
    }
}
