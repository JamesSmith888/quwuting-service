-- ============================================================================
-- V5: 门店营业时间 固定列 → 营业时段列表（2026-08-08，根因见 AGENTS.md「场所数据模型」）
--
-- 问题：qwt_venues 以 4 个固定列（afternoon_open/afternoon_close/evening_open/
-- evening_close）表达营业时间，把"1 个舞厅 → N 个场次"的业务维度硬编码成 2 个
-- 固定场次——任何新场次（早场/午茶场/深夜场）都要改表结构；时段名被烧进列名
-- 无法自定义；LocalTime 单列也没有"18:30-01:00"跨天结束的显式契约。
-- 根因：schema 跟随表单 UI 形状（下午场/晚场两行）反推，而非领域模型；
-- 且与 tickets/partnerFees 已确立的"变长结构化列表 = JSON 数组字符串列"模式不一致。
--
-- 方案：新增 business_hours varchar(1000) 列，存 JSON 数组
--   [{"name":"午场","open":"13:30","close":"17:30"},{"name":"晚场","open":"18:30","close":"01:00"}]
-- 与 tickets/partnerFees 同模式（强类型 DTO 序列化，读取端反序列化）。
-- 跨天契约：close < open 表示结束于次日凌晨（如 18:30-01:00），原样存取、展示端原样呈现。
--
-- 迁移步骤（顺序敏感）：
-- ① 加可空新列（无阻塞）；
-- ② 存量回填：非空时段按「下午场/晚场」命名组装 JSON 数组，顺序 = 下午场在前晚场在后
--    （与旧固定列展示顺序一致），仅保留起止齐全的时段，双场皆空保持 NULL；
-- ③ 删除 4 个旧列（Hibernate validate 不校验多余列，但实体已移除映射，删列保证 schema 干净）；
-- ④ 防御性校验：回填后所有时段条目 open/close 必须成对出现（DO 块 + WARNING，同 V3 模式）。
-- ============================================================================

-- ① 新增可空列（实体映射：Venue.businessHours，@Column(length = 1000)）
ALTER TABLE qwt_venues ADD COLUMN business_hours varchar(1000);

-- ② 存量回填（jsonb_agg 空输入返回 NULL；ORDER BY seq 保证场次顺序）
UPDATE qwt_venues
SET business_hours = (
    SELECT jsonb_agg(elem ORDER BY seq)::text
    FROM (
        SELECT 1 AS seq,
               jsonb_build_object(
                   'name', '下午场',
                   'open', to_char(afternoon_open, 'HH24:MI'),
                   'close', to_char(afternoon_close, 'HH24:MI')
               ) AS elem
        WHERE afternoon_open IS NOT NULL AND afternoon_close IS NOT NULL
        UNION ALL
        SELECT 2 AS seq,
               jsonb_build_object(
                   'name', '晚场',
                   'open', to_char(evening_open, 'HH24:MI'),
                   'close', to_char(evening_close, 'HH24:MI')
               ) AS elem
        WHERE evening_open IS NOT NULL AND evening_close IS NOT NULL
    ) t
)
WHERE afternoon_open IS NOT NULL AND afternoon_close IS NOT NULL
   OR evening_open IS NOT NULL AND evening_close IS NOT NULL;

-- ③ 删除旧固定列
ALTER TABLE qwt_venues
    DROP COLUMN afternoon_open,
    DROP COLUMN afternoon_close,
    DROP COLUMN evening_open,
    DROP COLUMN evening_close;

-- ④ 防御性校验：存量回填后不存在"起止缺一"的时段（残缺数据会在读取端被丢弃，属迁移缺陷）
DO $$
DECLARE
    malformed_count INTEGER;
BEGIN
    SELECT COUNT(*) INTO malformed_count
    FROM qwt_venues
    WHERE business_hours IS NOT NULL
      AND (
          business_hours::jsonb @> '[{"open":null}]'::jsonb
          OR business_hours::jsonb @> '[{"close":null}]'::jsonb
          OR business_hours::jsonb = '[]'::jsonb
      );
    IF malformed_count > 0 THEN
        RAISE WARNING 'V5 migration incomplete: % venue(s) have malformed business_hours', malformed_count;
    END IF;
END $$;
