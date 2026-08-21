-- ============================================================================
-- V37: 舞伴标签 GENTLE（🎩 有风度）下架 + 历史数据物理清理（2026-08-21，根因见
--   DancerTagCode.java javadoc 与前端 docs/agents/09-dancer-and-points.md「舞伴认可」）
--
-- 背景（用户驱动）：舞伴全部为女性，🎩 有风度 是男性化标签（礼帽意象），语义
--   不贴合——从字典移除 GENTLE。与门店 Reaction V16（第二轮瘦身）先例一致：
--   **物理清理**历史数据——DELETE qwt_dancer_recognition_tags 中 tag = 'GENTLE'
--   的全部记录。只删标签行，不触碰 qwt_dancer_recognitions（认可记录本身保留：
--   认可计数以 qwt_dancer_recognitions 聚合、与标签表独立，删标签不影响认可数）。
--
-- 为什么必须物理清理（对齐 V16「本轮起不留孤儿 code」）：
--   后端标签聚合（DancerDetailCacheService.fetchTags / DancerService.fetchTopTags）
--   对聚合行执行 DancerTagCode.valueOf(tag)——枚举移除后残留行会抛
--   IllegalArgumentException → 详情/列表/tags 接口 HTTP 500；前端展示层同样按
--   DANCER_TAG_ENTRIES 过滤，保留孤儿 code 只会让前端长期携带字典外过滤兼容逻辑。
--
-- 安全说明：
--   ① qwt_dancer_recognition_tags 无外键引用（tag varchar(50) 无 FK/CHECK），
--      DELETE 不存在级联风险；
--   ② 删除仅按 tag 过滤，不影响同表其他标签的认可记录；
--   ③ 今日已投 GENTLE 的用户认可记录保留（无标签认可仍计入认可数），下次 toggle
--      时今日标签为空 → 可正常重新参与（每日一票语义自洽，无需额外数据修复）。
--
-- Flyway 默认包事务，本迁移不显式 BEGIN/COMMIT。
-- ============================================================================

-- ① 清理前计数（写入日志辅助核对）
DO $$
DECLARE
    gentle_cnt INTEGER;
BEGIN
    SELECT COUNT(*) INTO gentle_cnt FROM qwt_dancer_recognition_tags WHERE tag = 'GENTLE';
    RAISE NOTICE 'V37 prune: GENTLE=%', gentle_cnt;
END $$;

-- ② 物理清理（只删 GENTLE 标签行，认可记录保留）
DELETE FROM qwt_dancer_recognition_tags WHERE tag = 'GENTLE';

-- ③ 清理结果验证（防御性：删除后必须 0 行，否则可能漏处理）
DO $$
DECLARE
    remaining INTEGER;
BEGIN
    SELECT COUNT(*) INTO remaining FROM qwt_dancer_recognition_tags WHERE tag = 'GENTLE';
    IF remaining > 0 THEN
        RAISE WARNING 'V37 migration incomplete: GENTLE remaining=%', remaining;
    END IF;
END $$;
