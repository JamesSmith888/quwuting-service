-- ============================================================================
-- V17: 赠送者列表查询索引优化（2026-08-12 性能）
--
-- 背景（根因）：GET /points/gifters（礼物墙点击弹层/详情页）按
-- (target_type, target_id, gift_code) 精确过滤并按 user_id 聚合。既有索引：
-- - qwt_idx_pts_tx_target (target_type, target_id, created_at)：目标维度索引，
--   gift_code 是残差过滤——热门场所礼物流水上万时仍要扫该目标全部流水；
-- - qwt_idx_pts_tx_target_gift (target_type, target_id) WHERE gift_code IS NOT
--   NULL（V13，礼物墙聚合用）：只能收窄到"目标的所有礼物行"，gift_code 依然
--   残差过滤——每次弹层点击都要扫目标全部礼物行 + JOIN 用户 + 聚合，延迟可感知。
--
-- 方案：把 V13 的部分索引**升级**为 (target_type, target_id, gift_code)
-- （同样 WHERE gift_code IS NOT NULL）——gift_code 等值过滤变成索引前置列，
-- 礼物墙（GROUP BY gift_code）与赠送者列表（GROUP BY user_id）两个聚合都变成
-- 索引精确匹配 + 小结果聚合。旧索引是新的严格前缀子集，直接替换
-- （防双索引冗余写放大——流水表只追加，索引写放大直接进落盘成本）。
-- ============================================================================
DROP INDEX qwt_idx_pts_tx_target_gift;

CREATE INDEX qwt_idx_pts_tx_target_gift_code
    ON qwt_points_transactions (target_type, target_id, gift_code)
    WHERE gift_code IS NOT NULL;
