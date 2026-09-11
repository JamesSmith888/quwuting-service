-- ============================================================================
-- V22: 门店照片同步「排除标记」（2026-09-11 用户拍板，见 docs/agents/04-venue-domain.md）
--
-- 背景：高德图片批量同步（一键同步，AmapVenuePhotoSyncService）按「缺主图 OR 无公开
-- 相册」口径自动拉取照片，但存在两类门店不应再被批量同步打扰：
--   ① 用户主动清除照片的门店（管理端「清除图片」= 人工判定错配回退，语义 = 明确
--      拒绝高德图，批量同步再拉 = 违背人工意图）；
--   ② 存量库中所有没有门店照片的门店（当前无图 ≠ 需要高德补，批量同步结果不可靠）。
-- 两类门店统一新增 photo_sync_excluded 标记，批量同步（findMissingImages /
-- countMissingImages）一律排除；单店人工重匹配（retrySync，显式操作）不受限。
--
-- 回填口径与本迁移前 findMissingImages 完全一致（deleted=false 且 image_url NULL/
-- 空串 OR 无 PUBLIC 相册）——即本迁移一应用，当前全部缺图门店即刻带标，批量同步
-- 归零；仅后续新建/恢复（未被标记）门店才进入批同步候选池。
-- 时间口径：本迁移只动标记列 + 依赖既有行状态快照，不写 created_at/updated_at
-- 墙钟（与既有「时间列由 Java 传」红线一致——无任何时间字段写入）。
-- 目录说明：本仓 MySQL 环境 Flyway 目录 = db/migration-mysql（全量基线，V1~V21），
-- 本迁移按该序列继续编号为 V22；db/migration 为遗留 Postgres 序列（勿复用）。
-- ============================================================================

ALTER TABLE qwt_venues ADD COLUMN photo_sync_excluded boolean NOT NULL DEFAULT false;

UPDATE qwt_venues v
SET v.photo_sync_excluded = true
WHERE v.deleted = false
  AND (v.image_url IS NULL OR v.image_url = ''
       OR NOT EXISTS (SELECT 1 FROM qwt_venue_photos p
                      WHERE p.venue_id = v.id
                        AND p.status = 'PUBLIC'
                        AND p.deleted = false));