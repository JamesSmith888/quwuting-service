-- ============================================================================
-- V51: 舞伴「联系方式更新时间」（2026-08-26 晚）——
-- qwt_dancers.contact_updated_at（最近一次联系方式变更时间，驱动列表卡片 /
-- 详情页「最近更新了联系方式」信号）。
--
-- 背景：用户希望在列表 item 与详情页合适位置，低调告知"该舞伴最近更新了相册 /
-- 联系方式"。相册更新信号无需新增列——直接取 qwt_dancer_photos 最新一张 PUBLIC
-- 媒体（照片 / 短视频）的 created_at（见 DancerPhotoRepository
-- #findLatestPublicCreatedAtByDancer*）；联系方式更新信号需专属时间戳，因为
-- Dancer.updated_at 会随任意字段（昵称 / 简介 / 头像 / 状态 …）变更而跳动，
-- 无法精确表达"联系方式变更"。
--
-- 规则：
-- 1. contact_updated_at（timestamp NULL）——联系方式（contact /
--    contact_image_url）有变更且变更后非空时，由服务层写入 LocalDateTime.now()；
--    存量舞伴恒 NULL（前端不渲染该信号）。列无 NOT NULL / 无 DEFAULT（NULL =
--    未更新过联系方式，语义明确；应用层解析防御）。
-- 2. 相册最近更新（last_album_updated_at）为派生值，不落库、不冗余存储。
-- 3. 枚举列 / 时间列禁 CHECK 约束（项目约定：应用层解析防御）。
-- ============================================================================

ALTER TABLE qwt_dancers
    ADD COLUMN contact_updated_at timestamp;
