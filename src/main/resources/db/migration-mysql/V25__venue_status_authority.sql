-- V25：门店状态「所有权 + 权威层级」字段（2026-09-14，方案见 docs/agents/48）
--
-- 背景（根因）：qwt_venues.status 原先是一个**没有所有权**的字段——管理端人工编辑、
-- 报告采纳、以及每日舞讯（Agent 通道）都能无条件覆盖它（last-write-wins），
-- 库里没有任何字段记录「这个值是谁的判断」。于是管理员手工修正的门店状态
-- 隔天就被舞讯批量写库冲掉；而舞讯本身并不 100% 可靠（第三方整理，会漏报/误报）。
--
-- 设计：把「信息来源的权威层级」下沉为**服务端领域不变量**（不在采集 Skill 侧打补丁，
-- 否则 Web 后台的同步入口不受约束、且「锁会过期」的时间语义无处安放）：
--   人工直改  >  外部舞讯推断
-- 外部通道写库前必须先过门禁（VenueStatusGuardService），人工通道的状态变更**打锁**。
--
-- 字段语义：
--   status_source      —— 状态值归谁所有：MANUAL 人工直改 / SYNC 外部舞讯推断 /
--                         NULL 旧数据或系统默认（不阻塞，视为无锁）
--   status_locked_until—— 人工锁到期时刻。非空且在将来 ⇒ 外部舞讯通道禁止覆盖该状态
--                         （锁内跳过，不写库、不通知、不发公告）。到期即自动失效，
--                         无需清理任务——「人工优先」是有时限的优先权，不是永久黑名单。
--   daily_sync_exempt  —— 永久豁免（0/1）。人工声明「本店不参与舞讯白名单推断」，
--                         用于**结构性**例外（该店不在舞讯覆盖范围 / 被系统性漏报）。
--                         反复冲突说明问题不在时间维度，长期靠加长锁是打补丁。
--   sync_note          —— 人工备注（改状态 / 设豁免的原因），后台可读，可空。
--
-- 明确不做历史回溯：存量行 status_source 全部留 NULL（= 无锁）而非用 status log 反推。
-- 反推会把几个月前的一次性人工修正全部锁上，制造大量停在旧人工值的僵尸状态；
-- 本机制只对未来的修改生效。
--
-- 枚举以 varchar 存储（对齐 V8 change_source 与 V1 baseline 风格，禁 CHECK，扩枚举免迁移）。

ALTER TABLE qwt_venues
    ADD COLUMN status_source varchar(20) NULL COMMENT '状态来源：MANUAL 人工直改 / SYNC 外部舞讯推断 / NULL 旧数据',
    ADD COLUMN status_locked_until datetime(6) NULL COMMENT '人工锁到期时刻（NULL=无锁）；锁内禁止外部舞讯通道覆盖',
    ADD COLUMN daily_sync_exempt tinyint(1) NOT NULL DEFAULT 0 COMMENT '永久豁免：不参与舞讯白名单/未上榜差集推断',
    ADD COLUMN sync_note varchar(200) NULL COMMENT '人工备注：改状态原因 / 豁免原因（后台可读）';

-- ── 人工锁运营配置（键即代码契约：OpsConfigService 定义常量 + 本迁移插入默认行） ──
-- 锁时长**按人工设定的目标状态不对称**：错判代价不对称决定观察期长短。
--   · 人工置 OPEN（舞讯漏报 / 当日临时恢复）——意图时效极短，锁长了会让舞讯连续多日
--     说不出话、用户看到「营业」白跑一趟 ⇒ 短窗口 3 天；
--   · 人工置停业类（SUSPENDED / CEASED，可能含 CLOSED / RENOVATING）——「这家确实关了」，
--     漏判代价是用户白跑 + 平台失信 ⇒ 长窗口 7 天。
-- 注：CLOSED / RENOVATING 本身不在两个外部通道的作用域内（applyBatch 只动
-- CEASED/SUSPENDED、applyBatchSuspend 只动 OPEN），天然免疫，锁只是无害的兜底。
-- 值为 '0' ⇒ 该方向不打锁（立即回归自动同步）；enabled='false' ⇒ 全局应急关闭本机制。
-- 注意：qwt_ops_config.key 是 MySQL 保留字，INSERT 列名必须反引号（V1 baseline 第 5 条契约）。
INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at) VALUES
('venue.status_lock.enabled', 'true', NULL, now()),
('venue.status_lock.human_open_days', '3', NULL, now()),
('venue.status_lock.human_closed_days', '7', NULL, now());
