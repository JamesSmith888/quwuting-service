-- ============================================================================
-- V15: Reaction 字典瘦身 18 → 14 + VALUE 语义纠偏（2026-08-12，根因见
--   ReactionCode.java javadoc 与前端 AGENTS.md「Reaction 快速反馈系统」）
--
-- 背景（用户驱动 + 根因分析）：
--   ① 删除 4 个 code（GOOD_VIBE / GOOD_MUSIC / NORMAL / CROWDED）：
--      - GOOD_VIBE（氛围好）/ GOOD_MUSIC（音乐棒）与 HOT（人气旺）重叠且用户无感
--        （来舞厅的动机是舞伴不是音乐）；
--      - NORMAL（普通）是零信息默认态（没点表情就等于普通）；
--      - CROWDED（人多拥挤）是 HOT 的负面镜像——同一事实（人多）正负互搏。
--   ② VALUE（✌"性价比高"，POSITIVE）语义纠偏：✌ 实为圈内黑话「剪刀手」——
--      10 元场有舞伴临时加价至 20 元时比 V 手势，是负面标签。为不得罪人不明示
--      "剪刀手"字样（emoji 保留 ✌ 作圈内暗号、label 落中性行为描述「舞伴加价」），
--      code 改名 PRICE_HIKE、极性改 NEGATIVE（退出热度公式、进负面信号单独计数）。
--
-- 历史数据策略：
--   - VALUE → PRICE_HIKE：直接重映射（code 名变更，数据语义不变，保留可分析性）。
--   - GOOD_VIBE / GOOD_MUSIC / NORMAL / CROWDED：**保留不删**——它们是非 POSITIVE
--     （GOOD_MUSIC/GOOD_VIBE 虽曾是 POSITIVE，但枚举删除后 SQL 镜像经
--     positiveCodeNames() 驱动，已自动退出热度公式），无最接近承接 code 不宜
--     强行映射（会扭曲热度/负面信号）；由前端展示层过滤字典外 code（已统一
--     `if (!meta) return` 静默丢弃 + stats 消费端 filter）。
--
-- 列结构（reaction_code varchar(30)）无外键约束、无 CHECK 枚举约束，直接 UPDATE 即可。
-- Flyway 默认包事务（V3 同款），本迁移不显式 BEGIN/COMMIT。
-- ============================================================================

-- ① 数据迁移：VALUE → PRICE_HIKE（code 名变更，语义不变）
UPDATE qwt_venue_reactions
SET reaction_code = 'PRICE_HIKE'
WHERE reaction_code = 'VALUE';

-- ② 迁移结果验证（防御性：迁移后必须 0 行，否则可能漏处理边界 code）
-- 与 V3 同模式：生产环境不在 Flyway 迁移中抛错，改用 DO 块 + WARNING，
-- 应用启动后可在日志 grep 验证。
DO $$
DECLARE
    value_left INTEGER;
BEGIN
    SELECT COUNT(*) INTO value_left FROM qwt_venue_reactions WHERE reaction_code = 'VALUE';
    IF value_left > 0 THEN
        RAISE WARNING 'V15 migration incomplete: VALUE remaining=%', value_left;
    END IF;
END $$;
