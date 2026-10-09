package org.quwuting.quwutingservice.user.service;

import org.quwuting.quwutingservice.user.repository.UserBehaviorEvent;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 轨迹「同类连发」合并器（2026-10-09；纯函数，无 IO，仅 ADMIN 消费）。
 *
 * <h2>为什么轨迹要合并（根因）</h2>
 * 轨迹原本是「一行事实 = 一行界面」的平铺。这对低频事件（分享、收藏、上报）是对的，但对<b>批量写入</b>
 * 的事件是灾难：快讯浏览是「展示即计」——用户打开一次快讯页，客户端把本次加载出的 4~10 条一并上报，
 * 生产实测 92% 的快讯浏览行落在「同一用户同一分钟 4 条以上」的批次里。逐行平铺的后果是：一次操作刷满一屏，
 * 真正有信息量的事件（上报、点赞、记账）被淹没在后面，且每个「加载更多」翻出来的还是同一类噪音。
 * 门店浏览也有同形问题（浏览列表时连点多家）。
 *
 * <h2>合并规则（口径，改动需同步 docs/agents/35）</h2>
 * <ul>
 *   <li><b>同类型</b>才合并（浏览与分享即使同一分钟也是两件事，各自成行）；</li>
 *   <li>同类型相邻事件的时间间隔 ≤ {@link #MERGE_GAP}（5 分钟）就并入同一批——<b>链式</b>：
 *       只看与批内上一条（更晚那条）的间隔，所以持续 20 分钟的连续浏览仍是一行；</li>
 *   <li>输入按时间<b>倒序</b>；其他类型的事件穿插<b>不会打断</b>某一类型的批（浏览中间夹一次分享，
 *       浏览依然合并成一行，分享独立成行）——输出按「批内最晚事件时刻」倒序，与平铺时的相对先后一致；</li>
 *   <li>只精确到日（{@code timeApprox}，历史脏行没有时间戳）的事件<b>不合并</b>——没有时刻就谈不上「连发」，
 *       宁可保持平铺也不伪造一个间隔。</li>
 * </ul>
 * 合并只改变<b>展示行数</b>，不改变事件总数（{@code total} 与「加载更多」的 {@code limit} 仍按原始事件计）。
 */
public final class BehaviorTimelineMerger {

    /** 同类事件相邻间隔不超过此值即并入同一批 */
    public static final Duration MERGE_GAP = Duration.ofMinutes(5);

    private BehaviorTimelineMerger() {
    }

    /** 一条原始轨迹事件（已按目录解析出事件类型；{@code happenedAt} 恒非空） */
    public record Raw(UserBehaviorEvent event, LocalDateTime happenedAt, LocalDate day,
                      boolean timeApprox, Long refId, String detail) {
    }

    /** 一个批：同类连发的若干原始事件，{@code members} 按时间倒序（首个 = 最晚） */
    public record Burst(UserBehaviorEvent event, List<Raw> members) {

        /** 本批的原始事件数 */
        public int count() {
            return members.size();
        }

        /** 本批最晚事件（行的展示时刻） */
        public Raw latest() {
            return members.getFirst();
        }

        /** 本批最早事件（合并行的起点） */
        public Raw earliest() {
            return members.getLast();
        }
    }

    /**
     * 合并。
     *
     * @param rawDesc 原始事件，必须按 {@code happenedAt} 倒序
     * @return 批列表，按批内最晚事件时刻倒序（同刻保持输入顺序）
     */
    public static List<Burst> merge(List<Raw> rawDesc) {
        List<Burst> out = new ArrayList<>();
        Map<UserBehaviorEvent, Burst> open = new EnumMap<>(UserBehaviorEvent.class);
        for (Raw raw : rawDesc) {
            if (raw.timeApprox()) {
                // 无精确时刻：单独成行，且不影响任何类型的开放批
                out.add(new Burst(raw.event(), new ArrayList<>(List.of(raw))));
                continue;
            }
            Burst current = open.get(raw.event());
            if (current != null && withinGap(current, raw)) {
                current.members().add(raw);
                continue;
            }
            Burst fresh = new Burst(raw.event(), new ArrayList<>(List.of(raw)));
            open.put(raw.event(), fresh);
            out.add(fresh);
        }
        return out;
    }

    /** 与批内当前最早一条的间隔是否在窗口内（输入倒序 ⇒ 新事件不晚于批内最早事件） */
    private static boolean withinGap(Burst burst, Raw next) {
        Duration gap = Duration.between(next.happenedAt(), burst.earliest().happenedAt());
        return !gap.isNegative() && gap.compareTo(MERGE_GAP) <= 0;
    }
}
