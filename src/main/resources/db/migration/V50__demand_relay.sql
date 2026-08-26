-- ============================================================================
-- V50: 邀约中转与自动降级（2026-08-26）——
-- qwt_dancers.contact_relay / auto_release（per-dancer 开关）+
-- qwt_demand_records.status（邀约状态机）
--
-- 背景：高流量舞伴反馈「客人私下加微信、口嗨多」——邀约单是「用户→平台」
-- 单向流程，舞伴全程看不到（根因，见 docs/agents/22）。方案 = 平台管理员
-- 微信人工中转：客人填邀约单 → 管理员后台待办 → 一键转发话术到舞伴微信 →
-- 舞伴回「给/不给」→ 管理员一键发放/拒绝；24h 无回复按 per-dancer 开关
-- 自动降级。全程无用户间通信（规避微信「社交」类目红线）。
--
-- 设计要点（对齐 V47/V48 per-dancer 开关先例）：
-- 1. contact_relay（boolean NOT NULL DEFAULT false）——开启后该舞伴的邀约
--    进入「管理员中转 + 舞伴批准」流程（unlock 不再直返联系方式，返回
--    PENDING）；存量舞伴默认关闭 = 填单即得微信现状零回归。
-- 2. auto_release（boolean NOT NULL DEFAULT true）——24h 无回复的自动降级
--    策略：true = 自动发放联系方式（平台兜底，默认策略，不卡单）；false =
--    告知客人暂未回复。仅 contact_relay=true 时有意义（高流量舞伴建议
--    false：宁可客人流失，不让平台代发——把关权被架空是她最在意的点）。
-- 3. qwt_demand_records.status（varchar(20) NULL）——邀约状态机
--    PENDING / APPROVED / REJECTED / AUTO_RELEASED / EXPIRED；NULL =
--    存量锚点记录（V42 前无状态语义，历史客人在当时已拿到微信，等价
--    APPROVED），前端徽标兼容不渲染。
-- 4. 枚举列禁 CHECK 约束（项目约定：应用层解析防御）。
-- ============================================================================

ALTER TABLE qwt_dancers
    ADD COLUMN contact_relay boolean NOT NULL DEFAULT false;

ALTER TABLE qwt_dancers
    ADD COLUMN auto_release boolean NOT NULL DEFAULT true;

ALTER TABLE qwt_demand_records
    ADD COLUMN status varchar(20);

-- 管理端待办查询 + 客人侧「最近 PENDING」去重查询
CREATE INDEX idx_qwt_demand_records_pending
    ON qwt_demand_records (dancer_id, status);
