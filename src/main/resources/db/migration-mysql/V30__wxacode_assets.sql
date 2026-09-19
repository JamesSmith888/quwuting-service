-- ============================================================================
-- V30（MySQL 轨道 = db/migration-mysql）: 分享小程序码「静态资产」物化
--      （2026-09-19，前端权威文档 = quwuting 仓 docs/agents/40-wxacode-share.md；
--       后端契约 = docs/agents/36-wxacode.md）
--
-- ⚠️ 编号说明：PG 遗留轨道（db/migration）的 V30 是舞伴城市表（2026-08 时代），
--    两条轨道编号各自独立、勿混用；2026-09-13 起新迁移一律走 MySQL 轨道。
--
-- 根因（用户报障「我的页底部应直接显示分享码；它是静态的就不该每次重新生成」）：
--
-- 平台码（GET /wxacode/home.jpg）的生成输入只有四个量——appid / page
-- （pages/index/index）/ scene（"home"，首页不消费）/ env_version
-- （wechat.qrcode-env）。**没有任何用户维度**：全平台扫的是同一张图，且
-- getwxacodeunlimit 生成的码永久有效、数量不限。它是一份**静态资产**。
--
-- 但它此前被放进了 WxacodeShareService 的进程内缓存（page|scene → bytes，
-- 24h TTL + 容量护栏），于是出现三类缺陷：
--
--   ① 生命周期错配：缓存的失效条件（TTL 到期 / 进程重启 / 缓存被清）与内容的
--      失效条件（四个输入量变化）**毫无关系**。缓存失效 ≠ 内容失效 ⇒ 每次部署
--      重启后的第一个请求都必然外呼微信，这是必然发生而非偶发。
--   ② 跨域误伤：MAX_CACHE_SIZE=500 超限即 clear() **整个** Map，而该 Map 同时
--      装平台码（1 个键）与门店码（scene 带 &s=<uid>，键空间 = 门店 × 用户）。
--      护栏按"总键数"惩罚，被清掉的却包含那张 1 个键的静态码——缓存污染且未分层。
--   ③ 键漏维度：键 = page|scene，缺 env_version。后果不是"多生成"，而是更危险的
--      反面——把生产 wechat.qrcode-env 从 release 改成 trial 后，24h 内仍返回
--      release 的旧码，**配置改了却静默不生效**（而 40 号文档 §6 恰好把"临时改
--      trial"写作联调手段）。这与 51-位置真值服务记录的根因同族（距离 memo 键漏
--      坐标维度），当时立的判据是「派生缓存键必须含全部输入维度」。
--
-- ── 设计：把「静态资产」从「缓存」里拿出来，按内容指纹物化一次 ─────────────
--
--   asset_key = 内容指纹 = appId|envVersion|page|scene（全维度，单点派生）
--               —— 指纹即内容标识，指纹不变 ⇒ 内容不变 ⇒ 不重生成（无 TTL 概念）。
--               指纹变化（如 env 改 trial）⇒ 新行 + 新生成，配置即刻生效（修 ③）。
--   image_bytes = 微信返回的 JPEG 原始字节（约 20~60KB）
--
-- 物化落库（而非本地文件 / 继续走内存）的理由：
--   · 单一持久化后端（RDS MySQL），随库备份与迁移走，无独立目录的生命周期问题；
--   · 多实例天然共享，不依赖"单机部署"这一隐含前提；
--   · 一次点查成本远低于一次微信外呼（性能第一约束：最少往返）。
--
-- 个性化码（门店码）**不进本表**：它的 scene 携带 uid 归因，内容随用户变化，
-- 键空间是乘积型且可再生，按"缓存"语义处理才正确——继续留在
-- WxacodeShareService 的内存缓存，但缓存实例与容量护栏**独立**（修 ②），
-- 且键改由同一份指纹派生（修 ③）。
--
-- ── 表设计说明 ──────────────────────────────────────────────────────────
--   · 主键 = asset_key（varchar(191) utf8mb4 ⇒ 764 字节 < InnoDB DYNAMIC 3072
--     索引前缀上限）。四维可读拼接而非哈希：运维可直接 SELECT 看懂一行是什么码。
--   · app_id / page_path / scene / env_version 冗余成列：主键已含全部信息，
--     但这四列让"人工排查 + 按维度筛选清理"不必反解指纹（诊断为先）。
--   · 无 deleted 列（全库软删约定之外的有意例外）：资产由系统管理、无用户可见
--     删除语义，重置 = 按指纹 DELETE 一行；引入软删会让"insertIfAbsent"的唯一键
--     语义复杂化（软删行仍占键），而收益为零。
--   · 无外键（全库约定）。
--   · 行数上界 = 环境数 × 静态码场景数（当前 3 × 1），无需额外索引。
-- ============================================================================

CREATE TABLE qwt_wxacode_assets (
    asset_key    varchar(191) NOT NULL COMMENT '内容指纹 = appId|envVersion|page|scene',
    app_id       varchar(64)  NOT NULL COMMENT '小程序 appid（生成输入之一）',
    page_path    varchar(191) NOT NULL COMMENT '落地页路径（如 pages/index/index）',
    scene        varchar(64)  NOT NULL COMMENT 'scene 参数（≤32 字符，微信硬限制）',
    env_version  varchar(16)  NOT NULL COMMENT 'release / trial / develop',
    content_type varchar(64)  NOT NULL COMMENT '响应内容类型（image/jpeg）',
    byte_size    int          NOT NULL COMMENT '图片字节数（排查用，免读 BLOB）',
    image_bytes  mediumblob   NOT NULL COMMENT '微信 getwxacodeunlimit 返回的图片原始字节',
    created_at   datetime(6)  NOT NULL COMMENT '首次物化时刻（指纹不变则永不更新）',
    PRIMARY KEY (asset_key)
) COMMENT='分享小程序码静态资产（内容指纹 → 图片字节；一次物化、永久只读）';
