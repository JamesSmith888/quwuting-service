-- ============================================================================
-- V47: 舞伴「加好友需告知位置」（2026-08-26）——
-- qwt_dancers.require_user_location（per-dancer 开关）+
-- qwt_demand_records.user_location（用户位置表态，风控留痕）
--
-- 背景：部分舞伴加好友时需确认用户能否到达服务地点（服务范围 location_scope
-- 的配套确认，非所有舞伴都需要）。设计 = 用户二选一表态「同城 / 非同城·自行
-- 前往」（UserLocationOption 枚举 code）——相对关系而非真实地址，不收集
-- 坐标/区划/门牌（隐私克制 + 合规安全，见 UserLocationOption javadoc）：
--
-- 1. require_user_location（boolean NOT NULL DEFAULT false）——存量舞伴默认
--    不要求，无需数据迁移。
-- 2. user_location（varchar(20) NULL）——仅开启开关的舞伴提交需求时写入，
--    存量/未开启舞伴为 NULL，无需数据迁移。
-- 3. 枚举列禁 CHECK 约束（项目约定：枚举类列禁 CHECK，应用层解析防御）。
-- ============================================================================

ALTER TABLE qwt_dancers
    ADD COLUMN require_user_location boolean NOT NULL DEFAULT false;

ALTER TABLE qwt_demand_records
    ADD COLUMN user_location varchar(20);
