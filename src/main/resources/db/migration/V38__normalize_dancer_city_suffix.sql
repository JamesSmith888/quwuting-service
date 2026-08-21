-- ============================================================================
-- V38：舞伴城市「市」后缀归一化 + 城市规范化键函数
-- ============================================================================
-- 背景（2026-08-21 根因修复）：门店 city 恒为标准行政区划名（picker region
-- 输出「南通市」），而舞伴城市存在历史手填形态「南通」（2026-08-14 城市选择器
-- 改造前的存量数据，舞伴主城市与子表均受影响）。`GET /dancers?city=` 的字符串
-- 精确匹配「南通市」≠「南通」→ 同城筛选/门店详情页「同城舞伴」入口查 0 条。
--
-- 本迁移两件事：
--   1. 城市规范化键函数 qwt_city_key(city)：仅去掉尾部「市」后缀（禁 REPLACE
--      全替换——'津市市' 会被 REPLACE 全删成 '津'），作为匹配/去重的规范化键。
--      IMMUTABLE：同输入恒同输出，可安全用于 WHERE/GROUP BY。
--   2. 存量数据归一（数据驱动、零硬编码城市名）：舞伴主城市/子表城市若存在
--      「门店城市 = 舞伴城市 || '市'」的对应（如 '南通'+'市' = '南通市'），
--      归一为门店标准行政区划名——映射从 qwt_venues 推导，不写死任何城市名，
--      幂等（已带「市」的城市恒不命中）。
--
-- 归一后：城市词表 /dancers/cities 自然去重（只剩标准形态），同城筛选恢复；
-- 匹配层防御（findPublicPage / findPublicCities 改用 qwt_city_key）使未来绕过
-- 表单的写入（管理端 API / 直写库）再次产生不带「市」数据时匹配仍不失效。
-- ============================================================================

-- ── 1. 城市规范化键函数 ────────────────────────────────────────────────────
CREATE OR REPLACE FUNCTION qwt_city_key(city TEXT)
RETURNS TEXT AS $$
    SELECT CASE WHEN RIGHT(city, 1) = '市' THEN LEFT(city, -1) ELSE city END
$$ LANGUAGE sql IMMUTABLE;

-- ── 2. 舞伴主城市归一（存在门店城市 = 舞伴城市 + '市' 时归一为门店形态） ─────
UPDATE qwt_dancers d
SET city = v.city,
    updated_at = now()
FROM qwt_venues v
WHERE d.deleted = false
  AND d.city IS NOT NULL AND d.city <> ''
  AND v.deleted = false
  AND v.city IS NOT NULL AND v.city <> ''
  AND v.city = d.city || '市'
  AND d.city <> v.city;

-- ── 3. 舞伴城市子表归一（与主城市同一规则） ──────────────────────────────────
UPDATE qwt_dancer_cities c
SET city = v.city,
    updated_at = now()
FROM qwt_venues v
WHERE c.deleted = false
  AND c.city IS NOT NULL AND c.city <> ''
  AND v.deleted = false
  AND v.city IS NOT NULL AND v.city <> ''
  AND v.city = c.city || '市'
  AND c.city <> v.city;
