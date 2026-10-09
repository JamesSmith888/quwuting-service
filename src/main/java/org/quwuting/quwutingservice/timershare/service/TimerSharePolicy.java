package org.quwuting.quwutingservice.timershare.service;

/**
 * 计时同步的<b>口径常量唯一声明处</b>（2026-10-07，V42；文档 = docs/agents/54-timer-share.md §参数）。
 * <p>
 * 这些是协议 / 防护参数而非运营可调项：调整需发版并与 54 号文档同步。刻意<b>不进 opsconfig</b>——
 * 运营配置的管理端控件只承载开关 / 整数，而这里的参数相互制约（例如 TTL 必须大于前端弹层的
 * 自动刷新周期），拆开可调会让界面允许配出互相矛盾的组合。功能本身<b>无运营开关</b>：
 * 计时二维码同步是正常功能、常开（2026-10-07 用户裁决，删掉了原 `timer.share.enabled`）。
 */
public final class TimerSharePolicy {

    private TimerSharePolicy() {
    }

    // ── 二维码 / token ────────────────────────────────────────────────────────

    /** token 长度（base62；62^10 ≈ 8.4e17，不可枚举） */
    public static final int TOKEN_LENGTH = 10;

    /** 小程序码 scene 的键：scene = "t=&lt;token&gt;"（12 字符，微信上限 32） */
    public static final String SCENE_KEY = "t";

    /** 小程序码落地页（非 tab 页：tab 页收不到带参入口，见前端 47 号 §7.11） */
    public static final String LANDING_PAGE = "pages/timer-join/timer-join";

    // ── 生命周期 ──────────────────────────────────────────────────────────────

    /**
     * 二维码有效期（毫秒，自最近一次创建 / 刷新算起）= 10 分钟。
     * <p>
     * 这是<b>快照新鲜度</b>的保护而不只是安全措施：快照是「创建那一刻」的冻结读数，之后主持方若
     * 暂停 / 继续，快照就不再代表他的现状。有效期让「很久以前截图的码」自然失效，迫使重新打开弹层
     * 刷新快照。前端弹层开着时每 {@code 4 分钟} 自动刷新一次（远小于本值），所以「弹层一直开着」
     * 不会自己过期。
     */
    public static final long SNAPSHOT_TTL_MS = 10 * 60 * 1000L;

    /** 每张分享会话最多可加入的人数（舞伴一次换几位是现实上限；也是刷量的硬顶） */
    public static final int MAX_JOINS = 5;

    /** 加入者「新用户」判据：账号创建距加入 ≤ 15 分钟（扫码拉新的直接度量；启发式，口径见 54 号文档） */
    public static final long NEW_USER_WINDOW_MS = 15 * 60 * 1000L;

    // ── 载荷边界 ──────────────────────────────────────────────────────────────

    /** 主持方上报的「起点以来的墙钟时长」上限 = 12 小时（一场舞不会更长；超限多半是忘了结束的计时） */
    public static final long MAX_WALL_ELAPSED_MS = 12 * 60 * 60 * 1000L;

    /** 净时长相对墙钟秒数允许的上浮（两个读数来自同一时刻的 floor，理论上 ≤；留 1 秒抖动余量） */
    public static final int NET_SECONDS_SLACK = 1;

    /**
     * 结算事实「已发生多久」的上限 = 12 小时（2026-10-09）。结算上报带 {@code settledAgoMs}（客户端
     * 本机单调差：上报那一刻 − 结算那一刻），服务端据此回推 {@code settled_at = 收到时刻 − ago}，
     * 使<b>延迟重放</b>（弱网下客户端把结算事实暂存、联网后补发）仍落在正确的时间点上。上限与墙钟
     * 时长上限同值：比一场舞还久的结算事实没有意义，超限按上限截断（不拒——它只是展示用时间事实）。
     * 前端出站队列的保留期必须与它一致（门禁 X 组比对）。
     */
    public static final long SETTLE_MAX_AGE_MS = MAX_WALL_ELAPSED_MS;

    /** session_key 最大长度 / 字符集（起点毫秒[:成员id]，只含数字字母冒号下划线连字符） */
    public static final int SESSION_KEY_MAX_LENGTH = 48;

    /** 计价规则最多几档 */
    public static final int RULE_MAX_TIERS = 8;

    /** 单档时长上限（分钟）= 24 小时 */
    public static final double TIER_MAX_MINUTES = 24 * 60;

    /** 单档价格上限（元）：与账本金额上限同量级的宽松护栏，只防脏数据 */
    public static final double TIER_MAX_PRICE = 100_000;

    /** 规则 JSON 字符上限（与 V42 列宽 varchar(2048) 对齐） */
    public static final int RULE_JSON_MAX_CHARS = 2048;

    // ── 速率护栏（进程内滑动窗口；单实例部署，重启清零——它防的是速率不是黑名单）──

    /** 主持方创建 / 刷新：窗口内次数上限 */
    public static final int WRITE_RATE_LIMIT = 30;
    public static final long WRITE_RATE_WINDOW_MS = 10 * 60 * 1000L;

    /** 主持方状态轮询：弹层开着每 3 秒一次（20 次/分），上限留 3 倍余量 */
    public static final int STATUS_RATE_LIMIT = 60;
    public static final long STATUS_RATE_WINDOW_MS = 60 * 1000L;

    /** 加入：单用户一小时内的尝试次数（含被拒的） */
    public static final int JOIN_RATE_LIMIT = 20;
    public static final long JOIN_RATE_WINDOW_MS = 60 * 60 * 1000L;

    /** 限流器跟踪的主体上限（防异常流量撑大内存；超限按 Caffeine 策略淘汰最久未用） */
    public static final long RATE_LIMITER_MAX_KEYS = 10_000;

    /** 主持方每自然日（滚动 24h）最多新建的分享会话数（刷新同一场不计）——DB 计数，跨重启有效 */
    public static final int MAX_NEW_SHARES_PER_DAY = 30;

    // ── 二维码图缓存 ──────────────────────────────────────────────────────────

    /** 码图内存缓存时长：略长于二维码有效期，覆盖「同一张码被多次拉取」 */
    public static final long QR_CACHE_TTL_MS = SNAPSHOT_TTL_MS + 5 * 60 * 1000L;

    /** 码图内存缓存容量（每张约 90KB；上限 300 ≈ 27MB） */
    public static final long QR_CACHE_MAX_SIZE = 300;
}
