-- ============================================================================
-- V16: Reaction 第二轮瘦身 14 → 10 + 历史数据物理清理（2026-08-12 晚，根因见
--   ReactionCode.java javadoc 与前端 AGENTS.md「Reaction 快速反馈系统」）
--
-- 背景（用户驱动）：
--   删除 4 个 code：FAIR_PRICE（🍺 消费合理）/ WAITING（⏳ 排队太久）/
--   HIGH_COST（💰 消费较高）/ CLEAN（✨ 干净整洁）——价格/排队/清洁维度
--   用户实际使用率低，字典收敛到"人气/舞伴风格/服务/环境"核心信号。
--
-- 与第一轮（V15）的历史数据策略差异（用户明确要求）：
--   - V15（GOOD_VIBE/GOOD_MUSIC/NORMAL/CROWDED）：保留历史数据 + 前端展示层
--     过滤字典外 code（无最接近承接 code，强行映射会扭曲信号）。
--   - V16（本轮）：**物理清理**——DELETE qwt_venue_reactions 中 4 个 code 的
--     全部记录，**只删表情数据**，不触碰任何其他表（venue / user / feedback /
--     status_report / points 等均不受影响）。理由：用户要求历史数据一并清理，
--     避免前端长期携带"字典外 code 过滤"兼容逻辑（本轮起不再保留孤儿 code）。
--
-- 安全说明：
--   ① qwt_venue_reactions 无外键引用本表（reaction_code 为 varchar(30) 无 FK/CHECK），
--      DELETE 不存在级联风险；
--   ② 删除仅按 reaction_code 过滤，不影响同表其他 code 的参与记录；
--   ③ 已删 code 从枚举移除后自动退出热度公式（positiveCodeNames() 驱动 SQL 镜像），
--      物理删除历史记录对热度计算无额外影响。
--
-- Flyway 默认包事务，本迁移不显式 BEGIN/COMMIT。
-- ============================================================================

-- ① 清理前计数（写入日志辅助核对）
DO $$
DECLARE
    fair_price_cnt INTEGER;
    waiting_cnt INTEGER;
    high_cost_cnt INTEGER;
    clean_cnt INTEGER;
BEGIN
    SELECT COUNT(*) INTO fair_price_cnt FROM qwt_venue_reactions WHERE reaction_code = 'FAIR_PRICE';
    SELECT COUNT(*) INTO waiting_cnt FROM qwt_venue_reactions WHERE reaction_code = 'WAITING';
    SELECT COUNT(*) INTO high_cost_cnt FROM qwt_venue_reactions WHERE reaction_code = 'HIGH_COST';
    SELECT COUNT(*) INTO clean_cnt FROM qwt_venue_reactions WHERE reaction_code = 'CLEAN';
    RAISE NOTICE 'V16 prune: FAIR_PRICE=%, WAITING=%, HIGH_COST=%, CLEAN=%',
        fair_price_cnt, waiting_cnt, high_cost_cnt, clean_cnt;
END $$;

-- ② 物理清理（只删表情数据，4 个 code 的全部历史记录）
DELETE FROM qwt_venue_reactions
WHERE reaction_code IN ('FAIR_PRICE', 'WAITING', 'HIGH_COST', 'CLEAN');

-- ③ 清理结果验证（防御性：删除后必须 0 行，否则可能漏处理）
DO $$
DECLARE
    remaining INTEGER;
BEGIN
    SELECT COUNT(*) INTO remaining FROM qwt_venue_reactions
    WHERE reaction_code IN ('FAIR_PRICE', 'WAITING', 'HIGH_COST', 'CLEAN');
    IF remaining > 0 THEN
        RAISE WARNING 'V16 migration incomplete: pruned codes remaining=%', remaining;
    END IF;
END $$;
