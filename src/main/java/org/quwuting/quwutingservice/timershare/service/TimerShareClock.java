package org.quwuting.quwutingservice.timershare.service;

/**
 * 分享快照的<b>时间算术单点</b>（2026-10-07，V42；纯函数、零 Spring / DB 依赖，可单测）。
 * <p>
 * 主持方与接收方的手机时钟互不可信（可相差分钟级），所以快照不能存「主持方本机的时间戳」，
 * 而要存在<b>服务端时间轴</b>上。本类只做两件事：
 * <ol>
 *   <li>{@link #anchor}：把主持方的一次读数（墙钟时长 + 净秒数 + 是否在走）在「收到时刻」
 *       落到服务端时间轴，得到三个锚点；</li>
 *   <li>{@link #netSecondsAt}：由锚点推算任意服务端时刻的净秒数——与前端
 *       {@code services/danceTimer.getElapsedSeconds} 是同一个公式（floor 秒、减整数排除秒、
 *       暂停则冻结在暂停时刻），接收方就是用它还原出与主持方屏幕一致的读数。</li>
 * </ol>
 *
 * <h3>已知误差（如实登记，别当成零误差）</h3>
 * 读数是主持方在 t0 取的，服务端在 t0 + 上行延迟 收到并以收到时刻回推起点，所以快照比真实
 * <b>少算</b>一个上行延迟（移动网络 50~300ms 量级，弱网可能 1~2 秒）。接收方侧的往返校准
 * 另有半个 RTT 的对称性假设误差。合计是亚秒到秒级——与「两边后续可能差几秒」的产品前提
 * 同量级，金额按档位 / 分钟计，不会因此改变可解释性。想消掉上行延迟需要额外一次时间同步往返，
 * 弱网下得不偿失，故不做。
 */
public final class TimerShareClock {

    private TimerShareClock() {
    }

    /**
     * 服务端时间轴上的三个锚点。
     *
     * @param startServerMs      主持方真实起点
     * @param excludedSeconds    已排除出计费的整数秒（暂停 + 已去掉的空窗）
     * @param pausedAtServerMs   创建时主持方处于暂停则 = 创建（收到）时刻，否则 null
     */
    public record Anchors(long startServerMs, int excludedSeconds, Long pausedAtServerMs) {
        public boolean paused() {
            return pausedAtServerMs != null;
        }
    }

    /**
     * 把主持方的读数落到服务端时间轴。
     *
     * @param recvMs             服务端收到读数的时刻（epoch ms）
     * @param wallElapsedMs      主持方「此刻 − 真实起点」的墙钟毫秒数（含暂停；[0, 12h]）
     * @param netElapsedSeconds  主持方屏幕上的净已计秒数（floor，已扣暂停与去掉的空窗）
     * @param running            主持方此刻是否在走（false = 暂停中，净时长冻结）
     * @throws IllegalArgumentException 读数越界或自相矛盾（调用方转成 1041）
     */
    public static Anchors anchor(long recvMs, long wallElapsedMs, int netElapsedSeconds, boolean running) {
        if (wallElapsedMs < 0 || wallElapsedMs > TimerSharePolicy.MAX_WALL_ELAPSED_MS) {
            throw new IllegalArgumentException("wallElapsedMs 越界: " + wallElapsedMs);
        }
        long wallSeconds = wallElapsedMs / 1000;
        if (netElapsedSeconds < 0 || netElapsedSeconds > wallSeconds + TimerSharePolicy.NET_SECONDS_SLACK) {
            throw new IllegalArgumentException("netElapsedSeconds 与墙钟矛盾: net=" + netElapsedSeconds
                    + " wallSeconds=" + wallSeconds);
        }
        // 抖动余量内的上浮钳回墙钟（净时长不可能大于墙钟）
        long net = Math.min(netElapsedSeconds, wallSeconds);
        int excluded = (int) (wallSeconds - net);
        return new Anchors(recvMs - wallElapsedMs, excluded, running ? null : recvMs);
    }

    /**
     * 由锚点推算 {@code nowMs}（服务端时刻）的净秒数。
     * 暂停中冻结在暂停时刻；公式与前端 getElapsedSeconds 同构：
     * {@code max(0, floor((ref − start)/1000) − excluded)}。
     */
    public static int netSecondsAt(Anchors anchors, long nowMs) {
        long ref = anchors.paused() ? anchors.pausedAtServerMs() : nowMs;
        long seconds = Math.floorDiv(ref - anchors.startServerMs(), 1000L) - anchors.excludedSeconds();
        if (seconds <= 0) {
            return 0;
        }
        return seconds > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) seconds;
    }
}
