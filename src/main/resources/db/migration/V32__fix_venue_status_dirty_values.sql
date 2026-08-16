-- ============================================================================
-- V32: 修复 qwt_venues.status 枚举外脏值（PRE_OPEN / LOCKED → OPEN）
--
-- 背景（2026-08-16 生产故障）：批量导入的新城市门店数据（嘉兴/宁波/上海/
-- 马鞍山/孝感，15 行）绕过应用层直接入库，status 被写入枚举外值：
--   - PRE_OPEN（14 行，源数据「预开业/未到营业时间」）
--   - LOCKED（1 行，上海杨浦「国潮1号」）
-- VenueStatus 枚举（OPEN/RENOVATING/CLOSED/SUSPENDED/CEASED）无这两个值，
-- 列表排序查询（searchRanked）读取时 Hibernate Enum.valueOf 抛
-- IllegalArgumentException → GET /venues 500。
--
-- 处理（2026-08-16 运营确认）：PRE_OPEN 与 LOCKED 语义均为「门店存在且
-- 可营业，仅未到营业时间/暂未开放」，归并为 OPEN（营业中）——与源数据
-- 事实一致，避免误伤列表可见性。
--
-- 幂等性：WHERE status IN ('PRE_OPEN','LOCKED') 已修复的行不再命中（no-op），
-- 当前环境线上数据已人工同款修复，本脚本随服务启动自动执行无副作用。
--
-- 防再发：枚举值合法性由应用层把关（见 V10 决策，DB 不建 CHECK 约束），
-- 导入侧必须将源数据状态按枚举白名单归一化后再入库，禁止直写 status。
-- ============================================================================

UPDATE qwt_venues
SET status = 'OPEN'
WHERE status IN ('PRE_OPEN', 'LOCKED');
