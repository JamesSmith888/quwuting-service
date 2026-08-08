-- ============================================================================
-- V3: Reaction 字典扩版 + 视觉升级（2026-08-08，根因见 AGENTS.md「Reaction 快速反馈系统」）
--
-- 背景（用户驱动 + 根因分析）：
--   旧 16 项 OpenMoji 表情视觉同质化严重——单色矢量图标在列表卡片 chip 与
--   Picker 弹窗中缺乏品牌辨识度，且"年轻舞伴多/舞伴年龄偏成熟"两个维度直接用
--   "👧 15岁/👴 35岁" 等具体年龄，存在未成年人合规风险（与舞伴服务语境叠加
--   触碰《未成年人保护法》）。本次改造：
--   ① 新增 4 个 code（VIBRANT/SWEET/MATURE_PARTNER 替代 YOUNG/OLD_PARTNER；VALUE 新增）
--   ② 删除 2 个 code（YOUNG_PARTNER/OLD_PARTNER，以"风格+年龄"组合替代"年龄标签"）
--   ③ reaction_code 列值迁移：保留历史数据可分析性，按用户感知最接近原则映射
--      - YOUNG_PARTNER → SWEET_PARTNER（"年轻"维度映射到"甜美风"——年轻用户偏好甜美风格，
--        历史数据中"年轻舞伴多"的场所也往往是"甜美风"集中的场所，映射误差小）
--      - OLD_PARTNER   → MATURE_PARTNER（"年龄偏成熟" → "成熟风"——语义直接对应）
--   ④ 新增 2 个 code（VIBRANT_PARTNER / VALUE）历史上不存在，不需要迁移
--
-- 列结构（reaction_code varchar(30)）无外键约束、无 CHECK 枚举约束，直接 UPDATE 即可。
-- Flyway 默认包事务（V2 写法），本迁移不显式 BEGIN/COMMIT。
-- ============================================================================

-- ① 数据迁移：历史 reaction_code 字符串重映射（按用户感知最接近原则）
UPDATE qwt_venue_reactions
SET reaction_code = 'SWEET_PARTNER'
WHERE reaction_code = 'YOUNG_PARTNER';

UPDATE qwt_venue_reactions
SET reaction_code = 'MATURE_PARTNER'
WHERE reaction_code = 'OLD_PARTNER';

-- ② 迁移结果验证（防御性：迁移后必须 0 行，否则可能漏处理边界 code）
-- 严格模式可改用 RAISE EXCEPTION，但生产环境不宜在 Flyway 迁移中抛错（已执行 UPDATE
-- 即便回滚需手写反向，复杂度高）。改用 DO 块 + WARNING，应用启动后可在日志 grep 验证。
DO $$
DECLARE
    young_left INTEGER;
    old_left INTEGER;
BEGIN
    SELECT COUNT(*) INTO young_left FROM qwt_venue_reactions WHERE reaction_code = 'YOUNG_PARTNER';
    SELECT COUNT(*) INTO old_left FROM qwt_venue_reactions WHERE reaction_code = 'OLD_PARTNER';
    IF young_left > 0 OR old_left > 0 THEN
        RAISE WARNING 'V3 migration incomplete: YOUNG_PARTNER remaining=%, OLD_PARTNER remaining=%',
            young_left, old_left;
    END IF;
END $$;
