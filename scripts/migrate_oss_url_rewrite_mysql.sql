-- =====================================================================
-- 去舞厅：对象迁移后 URL 前缀改写（Supabase 东京 → 阿里云 OSS 华东1杭州）
-- 在对象拷贝脚本（migrate_supabase_to_oss.py）执行成功后运行。
-- 方言：MySQL 8（生产库 qwt_mysql）。2026-09-17 新写——08-22 的
-- migrate_supabase_storage_url_rewrite.sql 为 PG 方言且列清单已过时
-- （缺 group_chats.qr_code_url / app_feedbacks.image_url /
--   venue_photos.url / dancer_photos.cover_url，勿再用旧脚本）。
--
-- 使用方法：
--   1. 把下方 @old_prefix / @new_prefix 两行替换为真实值：
--      - @old_prefix：当前 Supabase 项目的对象公开前缀（含桶名，尾斜杠）；
--      - @new_prefix：OSS 公开读基址（https://<bucket>.oss-cn-hangzhou.aliyuncs.com/，尾斜杠）；
--   2. 只在【生产 MySQL】执行（红线：执行前先跑核对段确认行数）；
--   3. 先跑「1. 核对」确认影响行数 ≈ 拷贝对象数分布，再跑「2. 改写」，
--      最后跑「3. 复核」应全为 0。
--
-- 幂等：REPLACE 重复执行无害（改写后已无旧前缀可匹配）。
-- 兼容：改写后 URL 为 OSS 形态，ImageContentValidator 白名单两形态并存
-- （legacy Supabase 前缀保留），业务提交不受影响。
-- =====================================================================

-- ---------- 0. 替换两行为真实值 ----------
SET @old_prefix = 'https://ijhuwkpumjnqxmfwobog.supabase.co/storage/v1/object/public/qwt-public/';
SET @new_prefix = 'https://<OSS_BUCKET>.oss-cn-hangzhou.aliyuncs.com/';

-- ---------- 1. 核对：改写前受影响的行数（应 ≈ 拷贝对象数分布） ----------
SELECT 'qwt_venues.image_url'             AS col, count(*) AS n FROM qwt_venues        WHERE image_url      LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venues.photos',             count(*) FROM qwt_venues        WHERE photos         LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venues.wechat_qr',          count(*) FROM qwt_venues        WHERE wechat_qr      LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venue_photos.url',          count(*) FROM qwt_venue_photos  WHERE url            LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_users.avatar_url',          count(*) FROM qwt_users         WHERE avatar_url     LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venue_claims.license_urls', count(*) FROM qwt_venue_claims  WHERE license_urls   LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_group_chats.qr_code_url',   count(*) FROM qwt_group_chats   WHERE qr_code_url    LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_app_feedbacks.image_url',   count(*) FROM qwt_app_feedbacks WHERE image_url      LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancers.avatar_url',        count(*) FROM qwt_dancers       WHERE avatar_url     LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancers.contact_image_url', count(*) FROM qwt_dancers       WHERE contact_image_url LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancer_photos.url',         count(*) FROM qwt_dancer_photos WHERE url            LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancer_photos.blur_url',    count(*) FROM qwt_dancer_photos WHERE blur_url       LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancer_photos.cover_url',   count(*) FROM qwt_dancer_photos WHERE cover_url      LIKE CONCAT(@old_prefix, '%');

-- ---------- 2. 改写：旧前缀 → 新前缀 ----------
UPDATE qwt_venues        SET image_url      = REPLACE(image_url,      @old_prefix, @new_prefix) WHERE image_url      LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_venues        SET photos         = REPLACE(photos,         @old_prefix, @new_prefix) WHERE photos         LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_venues        SET wechat_qr      = REPLACE(wechat_qr,      @old_prefix, @new_prefix) WHERE wechat_qr      LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_venue_photos  SET url            = REPLACE(url,            @old_prefix, @new_prefix) WHERE url            LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_users         SET avatar_url     = REPLACE(avatar_url,     @old_prefix, @new_prefix) WHERE avatar_url     LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_venue_claims  SET license_urls   = REPLACE(license_urls,   @old_prefix, @new_prefix) WHERE license_urls   LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_group_chats   SET qr_code_url    = REPLACE(qr_code_url,    @old_prefix, @new_prefix) WHERE qr_code_url    LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_app_feedbacks SET image_url      = REPLACE(image_url,      @old_prefix, @new_prefix) WHERE image_url      LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_dancers       SET avatar_url     = REPLACE(avatar_url,     @old_prefix, @new_prefix) WHERE avatar_url     LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_dancers       SET contact_image_url = REPLACE(contact_image_url, @old_prefix, @new_prefix) WHERE contact_image_url LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_dancer_photos SET url            = REPLACE(url,            @old_prefix, @new_prefix) WHERE url            LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_dancer_photos SET blur_url       = REPLACE(blur_url,       @old_prefix, @new_prefix) WHERE blur_url       LIKE CONCAT(@old_prefix, '%');
UPDATE qwt_dancer_photos SET cover_url      = REPLACE(cover_url,      @old_prefix, @new_prefix) WHERE cover_url      LIKE CONCAT(@old_prefix, '%');

-- ---------- 3. 复核：改写后应全为 0（残留即未命中，需排查） ----------
SELECT 'qwt_venues.image_url'             AS col, count(*) AS n FROM qwt_venues        WHERE image_url      LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venues.photos',             count(*) FROM qwt_venues        WHERE photos         LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venues.wechat_qr',          count(*) FROM qwt_venues        WHERE wechat_qr      LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venue_photos.url',          count(*) FROM qwt_venue_photos  WHERE url            LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_users.avatar_url',          count(*) FROM qwt_users         WHERE avatar_url     LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_venue_claims.license_urls', count(*) FROM qwt_venue_claims  WHERE license_urls   LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_group_chats.qr_code_url',   count(*) FROM qwt_group_chats   WHERE qr_code_url    LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_app_feedbacks.image_url',   count(*) FROM qwt_app_feedbacks WHERE image_url      LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancers.avatar_url',        count(*) FROM qwt_dancers       WHERE avatar_url     LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancers.contact_image_url', count(*) FROM qwt_dancers       WHERE contact_image_url LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancer_photos.url',         count(*) FROM qwt_dancer_photos WHERE url            LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancer_photos.blur_url',    count(*) FROM qwt_dancer_photos WHERE blur_url       LIKE CONCAT(@old_prefix, '%')
UNION ALL SELECT 'qwt_dancer_photos.cover_url',   count(*) FROM qwt_dancer_photos WHERE cover_url      LIKE CONCAT(@old_prefix, '%');
