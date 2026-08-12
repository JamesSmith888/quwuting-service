-- ============================================================================
-- V13: 礼物赠送（qwt_points_transactions 增加 gift_code）
--
-- 背景（2026-08-12 礼物赠送系统，根因见 docs/consumption-model.md）：
-- 直接赠送积分 = 资产转移语义（触碰"可流转准货币"合规红线 + 无情感载体）；
-- 改为赠送礼物 = 消费语义（积分购买礼物一次性送出）。赠送流水（delta < 0）原有
-- 的 target_type/target_id 不变（"谁收到"），本迁移补齐"送了什么"（gift_code）。
--
-- 设计要点：
-- 1. gift_code 为**可空**列（非赠送流水恒空，赠送流水恒非空）——新可空列无需
--    @ColumnDefault，直接 ALTER ADD；
-- 2. 值 = GiftCatalog 枚举名（varchar(30)，枚举 code 均 < 30），**不加 CHECK 约束**
--    （枚举类列禁 CHECK——扩枚举只改 Java 枚举，与 target_type 同模式，见 V10）；
-- 3. 礼物聚合索引：部分索引 (target_type, target_id) WHERE gift_code IS NOT NULL——
--    "目标收到礼物"聚合（GROUP BY gift_code）走该索引；既有
--    qwt_idx_pts_tx_target 继续服务"收到积分价值"（SUM(delta)）与趋势序列。
-- 4. 存量数据（V2 积分赠送）gift_code 为 NULL：聚合时被部分索引天然排除，
--    展示口径 = 礼物时代数据（旧积分赠送仍计入"收到积分价值"热度口径，不丢失）。
-- ============================================================================
ALTER TABLE qwt_points_transactions ADD COLUMN gift_code varchar(30);

-- 礼物聚合（目标收到礼物清单：code → 数量）专用部分索引
CREATE INDEX qwt_idx_pts_tx_target_gift
    ON qwt_points_transactions (target_type, target_id)
    WHERE gift_code IS NOT NULL;
