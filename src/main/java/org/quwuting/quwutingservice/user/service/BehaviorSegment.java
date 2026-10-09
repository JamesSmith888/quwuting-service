package org.quwuting.quwutingservice.user.service;

/**
 * 用户活跃分层——平台「行为分析」页与单用户「行为统计」卡的<b>唯一分类器</b>
 * （2026-10-09 由 {@code AdminUserBehaviorAnalyticsService} 的私有常量与 if 链抽出）。
 *
 * <h2>为什么抽出来（根因）</h2>
 * 单用户详情要回答「这个人属于哪一层」，而分层判据（可用天数归一 + 三个比例阈值）原先是平台服务里的
 * 私有逻辑。若详情页另写一份，同一个人在「用户详情」与「行为分析」两页会被分到不同层——
 * 与 2026-09-15 那次「同一个活跃概念在同一页面并存两套定义」是同一种漂移。抽成纯函数后，
 * 两处只能调用同一个 {@link #classify}，阈值只有一处可改。
 *
 * <h2>判据（互斥且完备，按优先级自上而下）</h2>
 * <ol>
 *   <li>可用天数 ≤ {@value #NEW_USER_AVAILABLE_DAYS} → {@link #NEW}（观测期不足，不参与比例分档）；</li>
 *   <li>有主动行为 → 按「活跃天数 ÷ 可用天数」分 {@link #HIGH} / {@link #REGULAR} / {@link #LOW}
 *       （<b>必须按可用天数归一</b>：注册 2 天的人不可能有 8 个活跃日，按绝对天数切档会把新用户
 *       系统性地判成低频——与留存报表「未到期 ≠ 0%」同一判据）；</li>
 *   <li>无主动行为但有<b>扩展行为</b> → {@link #EXTENDED_ONLY}（2026-10-09 新增，见下）；</li>
 *   <li>无主动行为、有登录自动打卡 → {@link #OPEN_ONLY}；</li>
 *   <li>其余 → {@link #DORMANT}。</li>
 * </ol>
 *
 * <h2>{@link #EXTENDED_ONLY} 为什么必须单列</h2>
 * 「仅打开无行为」的语义是<b>审核 / 巡检号的典型形态</b>。快讯 / 计时账本等扩展功能尚未纳入「活跃」口径
 * （见 {@code UserBehaviorEvent.Nature#EXTENDED}），于是一个天天看快讯、每晚用计时器记账的真实用户，
 * 活跃天数为 0、只有打卡——会被判成 {@code OPEN_ONLY}，运营据此误以为他是巡检号。拆出本层
 * 既纠正了这个误判，也<b>直接回答了「要不要把扩展功能纳入活跃」的评审问题</b>：本层人数 =
 * 纳入后会从「仅打开」翻成「活跃」的人数。
 */
public enum BehaviorSegment {

    HIGH("高频活跃", "活跃天数 ≥ 可用天数的 50%"),
    REGULAR("常规活跃", "活跃天数占可用天数 25% ~ 50%"),
    LOW("低频活跃", "窗口内有主动行为，但活跃天数不足可用天数的 25%"),
    EXTENDED_ONLY("仅用扩展功能",
            "窗口内没有「活跃口径」内的主动行为，但使用了快讯 / 计时账本等扩展功能"
                    + "（尚未纳入活跃口径；属真实使用，不是审核/巡检形态）"),
    OPEN_ONLY("仅打开无行为", "窗口内没有任何主动行为，只有登录自动打卡（审核/巡检号的典型形态）"),
    DORMANT("完全沉默", "窗口内既无主动行为也无打开记录"),
    NEW("新近注册", "可用天数 ≤ " + BehaviorSegment.NEW_USER_AVAILABLE_DAYS + " 天，观测期不足，不参与比例分档");

    /** 可用天数 ≤ 此值 → 「新近注册」层 */
    public static final long NEW_USER_AVAILABLE_DAYS = 3;
    /** 活跃天数占可用天数 ≥ 此比例 → 高频活跃 */
    public static final double HIGH_ACTIVE_RATIO = 0.5;
    /** ≥ 此比例（且低于高频阈值）→ 常规活跃；低于则低频活跃 */
    public static final double REGULAR_ACTIVE_RATIO = 0.25;

    private final String label;
    private final String hint;

    BehaviorSegment(String label, String hint) {
        this.label = label;
        this.hint = hint;
    }

    public String label() {
        return label;
    }

    public String hint() {
        return hint;
    }

    /**
     * 分层判定（纯函数，无 IO）。
     *
     * @param availableDays 该用户在窗口内可用的天数 = min(窗口, 注册至今天数 + 1)；&le; 0 视为 1
     * @param activeDays    窗口内有主动行为的去重天数
     * @param openDays      窗口内登录自动打卡的去重天数
     * @param extendedDays  窗口内有扩展行为的去重天数
     */
    public static BehaviorSegment classify(long availableDays, long activeDays, long openDays,
                                           long extendedDays) {
        long available = Math.max(1L, availableDays);
        if (available <= NEW_USER_AVAILABLE_DAYS) {
            return NEW;
        }
        if (activeDays > 0) {
            double ratio = (double) activeDays / available;
            if (ratio >= HIGH_ACTIVE_RATIO) {
                return HIGH;
            }
            return ratio >= REGULAR_ACTIVE_RATIO ? REGULAR : LOW;
        }
        if (extendedDays > 0) {
            return EXTENDED_ONLY;
        }
        return openDays > 0 ? OPEN_ONLY : DORMANT;
    }
}
