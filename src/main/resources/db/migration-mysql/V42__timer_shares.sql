-- ============================================================================
-- V42（MySQL 轨道 = db/migration-mysql）: 计时「二维码同步给对方」——分享会话 + 加入流水 + 运营开关
--      （2026-10-07，后端权威文档 = docs/agents/54-timer-share.md；
--       前端 = quwuting 仓 docs/agents/59-timer-share.md）
--
-- ⚠️ 编号说明：PG 遗留轨道（db/migration）与 MySQL 轨道编号各自独立、勿混用。
-- ⚠️ V41 已被「热度上报营业日」占用（同日另一条工作流），本迁移顺延 V42；部署前须核对
--    db/migration-mysql 下无第二份 V42（Flyway 对重复版本号直接拒启）。
--
-- ── 需求与模型（为什么是「一次快照 + 独立副本」，不是实时镜像） ────────────────
--
-- 业务：客人在计时中出示一张小程序码，舞伴扫码后直达自己的计时页并从同一起点开始计时，
--       目的 = 传播小程序（舞伴侧是目前的空白人群）。
--
-- 模型：扫码得到的是一份**独立副本**——加入那一刻之后各走各的账（各自暂停、各自结算），
--       服务端只在「创建 / 刷新 / 加入」三个瞬间参与，之后不再在线。
--       ⇒ 弱网（舞厅地下室）下只有扫码那一次需要网络；对方有自己的账，留存不依赖主持方。
--       ⇒ 服务端**只存时间事实，不存金额**（金额由各端 danceCost 现算，单一口径）。
--
-- 快照 = 「真实起点 + 已排除秒数 + 规则」，起点存在**服务端时间轴**上：
--   start_server_ms       主持方真实起点（epoch ms，服务端时钟）。主持方上报
--                         wallElapsedMs（此刻 − 起点），服务端以收到时刻回推：
--                         start = recv − wall。两端手机时钟不必一致。
--   excluded_seconds      已排除出计费的秒数（暂停 + 已去掉的空窗），= floor(wall/1000) − net。
--   paused_at_server_ms   主持方创建那一刻处于暂停时 = 创建时刻（服务端时钟），否则 NULL；
--                         接收方据此以「冻结在 net」的形态起步。
--   接收方加入时用自己测得的时钟偏移把这三个量换回本机时间（见前端 utils/timerShareClock）。
--
-- ── 为什么 excluded 存秒而不存毫秒 ───────────────────────────────────────────
-- 主持方端 getElapsedSeconds 是 floor 秒、pausedAccumSeconds 是整数秒。接收方用整数秒
-- 还原时，净时长与主持方屏幕上的读数逐秒一致（毫秒存法会在 floor 边界上差 1 秒）。
--
-- ── 规则快照：只存计价参数，不存任何自由文本 ────────────────────────────────
-- rule_json = {"tiers":[{"durationMinutes":4,"price":20,"mode":"linear"?}]}。
-- 规则名是用户输入的自由文本，经二维码展示给另一个用户 = UGC 公开展示（个人主体小程序的
-- 类目红线，见前端 AGENTS「小程序类目合规 UGC 红线」）。所以**名称不上云**：接收方由档位
-- 本地派生展示名。写入前服务端按白名单重新序列化（TimerShareRules），库里只会有数字。
--
-- ── 二维码 token ────────────────────────────────────────────────────────────
-- token = 10 位 base62（SecureRandom，62^10 ≈ 8.4e17，不可枚举）；scene = "t=<token>"（12 字符，
-- 远低于微信 32 字符上限）。token 即 bearer 凭据：知道它就能加入，所以有 TTL、人数上限、
-- 主持方关闭三道收口（expires_at_ms / max_joins / status）。
--
-- ── 时间列为什么是 bigint 毫秒而不是 datetime ───────────────────────────────
-- 本表的时间量参与「服务端时间轴」算术（起点回推、TTL 比较、与客户端往返校准），全程 epoch ms，
-- 用 datetime 会多一次时区换算且无益。可读时间由 BaseEntity 的 created_at / updated_at 提供。
--
-- ── 并发与幂等 ──────────────────────────────────────────────────────────────
--   · UNIQUE (host_user_id, session_key)：同一场计时（同一位成员）只有一张分享会话——重复打开
--     弹层 = 刷新同一行（token 不变、二维码图不变）。session_key = 主持方端「起点ms[:成员id]」。
--   · UNIQUE (share_id, user_id)：同一人对同一会话只有一条加入记录（重复扫码幂等）。
--   · 加入走「对分享行 SELECT ... FOR UPDATE」串行化（人数上限判定 + 计数自增 + 流水写入
--     在同一事务内），不依赖 save + catch 唯一键异常（那条路径在事务内会 rollback-only，
--     是本库历史上出过 500 的形态，见 PointsUnlockRepository#insertIfAbsent）。
--   · 无外键、枚举列无 CHECK、不写 DB now()（全库约定）。
--
-- ── 漏斗（直接 SQL，不建统计表）─────────────────────────────────────────────
--   创建 qwt_timer_shares → 扫码加入 qwt_timer_share_joins → 新用户 is_new_user
--   → 对方自己结算（qwt_spend_entries source=DANCE 且 ts ≥ 加入时刻）→ 次日回访；
--   parent_share_id 串起「加入者再出示」的传播链。完整查询见 54 号文档「漏斗」节。
--
-- ============================================================================

CREATE TABLE qwt_timer_shares (
    id                  bigint        NOT NULL AUTO_INCREMENT,
    created_at          datetime(6)   NOT NULL COMMENT '创建时刻（JVM 北京时间）',
    updated_at          datetime(6)   NOT NULL COMMENT '最近一次刷新 / 加入 / 关闭的时刻',
    deleted             tinyint(1)    NOT NULL DEFAULT 0,
    token               varchar(16)   NOT NULL COMMENT '二维码凭据：10 位 base62，scene = t=<token>',
    host_user_id        bigint        NOT NULL COMMENT '主持方（qwt_users.id）',
    session_key         varchar(48)   NOT NULL COMMENT '主持方端的场次标识：起点毫秒[:成员id]，与 host_user_id 共同唯一',
    parent_share_id     bigint        NULL     COMMENT '传播链：创建者本身是经某个二维码加入的，则指向那张会话',
    status              varchar(16)   NOT NULL COMMENT 'ACTIVE / CLOSED（过期是派生态：expires_at_ms 已过）',
    start_server_ms     bigint        NOT NULL COMMENT '主持方真实起点（服务端时间轴 epoch ms）',
    excluded_seconds    int           NOT NULL COMMENT '已排除出计费的秒数（暂停 + 已去掉的空窗）',
    paused_at_server_ms bigint        NULL     COMMENT '创建 / 刷新时主持方处于暂停则 = 该时刻，否则 NULL',
    rule_json           varchar(2048) NOT NULL COMMENT '计价参数 {"tiers":[...]}，服务端白名单重序列化，不含任何自由文本',
    venue_id            bigint        NULL     COMMENT '主持方关联的门店（qwt_venues.id，校验存在后才写）',
    snapshot_at_ms      bigint        NOT NULL COMMENT '快照时刻（服务端时钟）：最近一次创建 / 刷新',
    expires_at_ms       bigint        NOT NULL COMMENT '二维码失效时刻 = snapshot_at_ms + TTL；主持方每次打开弹层都会顺延',
    max_joins           int           NOT NULL COMMENT '最多可加入人数',
    join_count          int           NOT NULL COMMENT '已加入人数（与 joins 表行数一致，行锁内维护）',
    refresh_count       int           NOT NULL COMMENT '快照被刷新的次数（首次创建 = 1；观测主持方重开弹层的频度）',
    closed_at_ms        bigint        NULL     COMMENT '主持方结束计时 / 单独结算时关闭的时刻',
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_ts_token UNIQUE (token),
    CONSTRAINT qwt_uk_ts_host_session UNIQUE (host_user_id, session_key)
) COMMENT='计时同步分享会话：主持方一场计时的一份时间快照 + 二维码凭据';

-- 主持方维度：每日新建上限（防刷）与自己的历史
CREATE INDEX qwt_idx_ts_host_created ON qwt_timer_shares (host_user_id, created_at);

-- 传播链回溯（「加入者再出示」）
CREATE INDEX qwt_idx_ts_parent ON qwt_timer_shares (parent_share_id);

CREATE TABLE qwt_timer_share_joins (
    id                  bigint      NOT NULL AUTO_INCREMENT,
    created_at          datetime(6) NOT NULL COMMENT '加入时刻（JVM 北京时间）',
    updated_at          datetime(6) NOT NULL COMMENT '同 created_at（流水行不再更新）',
    deleted             tinyint(1)  NOT NULL DEFAULT 0,
    share_id            bigint      NOT NULL COMMENT '分享会话（qwt_timer_shares.id）',
    user_id             bigint      NOT NULL COMMENT '加入者（qwt_users.id）',
    is_new_user         tinyint(1)  NOT NULL COMMENT '加入时该账号是否为新建（账号创建距加入 ≤ 15 分钟）：扫码拉新的直接度量',
    net_seconds_at_join int         NOT NULL COMMENT '加入那一刻按服务端时间轴推算的净时长（秒）：观测「计时进行到多久才被扫」',
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_tsj_share_user UNIQUE (share_id, user_id)
) COMMENT='计时同步加入流水：每行 = 一个用户扫码加入了一张分享会话';

-- 加入者维度：漏斗里「这个人在加入之后做了什么」按 user_id 回查
CREATE INDEX qwt_idx_tsj_user_created ON qwt_timer_share_joins (user_id, created_at);

-- ── 运营开关（已于 V43 撤回，见该文件）────────────────────────────────────────
-- 本段插入的 `timer.share.enabled` 默认 false 开关，**在 V43 中被删除**（2026-10-07 用户裁决：
-- 这是正常功能，不需要运营开关）。⛔ 本段保持原样不可删改——V42 已应用到生产库
-- （2026-10-07 16:50），改字节会让 Flyway validate 直接拒启。历史即事实。
INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at)
VALUES ('timer.share.enabled', 'false', NULL, now());
