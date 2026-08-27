-- ============================================================================
-- V54: 邀约履约确认（2026-08-27，docs/agents/23-user-contribution-and-fulfillment.md）
--
-- 需求（产品讨论结论，见 23 号文档「P1 履约闭环」）：客人确认完成邀约 → 形成
-- 「与舞伴已合作 N 次」的私域履约信号——舞伴/管理员在邀约场景参考（判断客人
-- 是否靠谱），不新增积分、不公开广播（合规：无公开用户主页，见 AGENTS.md
-- 「小程序类目合规 UGC 红线」）。
--
-- 语义：
--   fulfilled_at 非空 = 客人已确认本次邀约履约完成（幂等写一次，不更新）；
--   NULL = 未确认（含存量记录，前端不渲染履约卡）。
--   仅邀约已获批（APPROVED/AUTO_RELEASED 或存量 NULL 等价已发放）的邀约可确认，
--   确认由应用层校验（DemandFulfillmentService），本迁移只落列与索引。
--
-- 索引：idx_qwt_demand_records_user_dancer 支撑「该客人与该舞伴的履约确认数」
-- 统计（COUNT WHERE user_id AND dancer_id AND fulfilled_at IS NOT NULL）——
-- 与管理端列表页跳转舞伴详情同查询前缀，索引名与 JPA 实体 @Index 声明一致。
-- ============================================================================
ALTER TABLE qwt_demand_records ADD COLUMN fulfilled_at timestamp(6);
CREATE INDEX idx_qwt_demand_records_user_dancer ON qwt_demand_records (user_id, dancer_id);
