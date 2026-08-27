-- ============================================================================
-- V55: 拒绝原因 + 替代邀约（2026-08-27，docs/agents/24-rejection-and-matching.md）
--
-- 需求（产品讨论，24 号文档「P0 拒绝原因闭环 + 换乘站」）：
--   ① 拒绝原因标签：管理员拒绝邀约时选原因标签落库 → 客人侧展示知因文案
--      （「TA 暂时不方便（档期冲突）」）——拒绝从「句号」变「信息」，客人不
--      归因自己；管理端/撮合台按原因优化推荐。
--   ② 客人请求替代：被拒/超时终态页「让平台帮您找类似的」→ 平台代找替代舞伴
--      （管理员微信人工确认替代舞伴同意 → 代建替代邀约直接发放联系方式）——
--      客人被拒后不流失，平台内闭环（换乘站）。
--
-- 语义（全部可空，存量记录零影响）：
--   reject_reason       DemandRejectReason 枚举 code（可空 = 未填原因/存量）；
--   rescue_requested_at 客人请求替代的时间（非空 = 已请求；只置一次幂等）；
--   origin_demand_id    替代邀约溯源（非空 = 本记录是管理员为原邀约代找的替代，
--                       语义上原邀约 status 保持 REJECTED/EXPIRED 不动，新邀约
--                       以独立 APPROVED 记录发放联系方式——「我的邀约」天然可见）；
--                       部分唯一索引 = 一次救援只产出一条替代邀约（防重复代建）。
--
-- 隐私克制延续：只存枚举 code / 时间戳 / id，不存自由文本。
-- ============================================================================
ALTER TABLE qwt_demand_records ADD COLUMN reject_reason varchar(20);
ALTER TABLE qwt_demand_records ADD COLUMN rescue_requested_at timestamp(6);
ALTER TABLE qwt_demand_records ADD COLUMN origin_demand_id bigint;
CREATE UNIQUE INDEX idx_qwt_demand_records_rescue_origin
    ON qwt_demand_records (origin_demand_id)
    WHERE origin_demand_id IS NOT NULL;
