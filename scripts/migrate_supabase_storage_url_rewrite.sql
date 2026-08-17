-- =====================================================================
-- 去舞厅：对象迁移后 URL 前缀改写（旧项目 → 新项目）
-- 在对象拷贝脚本（migrate_supabase_storage.py）执行成功后运行。
--
-- 使用方法：
--   1. 把下方 <OLD_PROJECT_URL> / <NEW_PROJECT_URL> 两处替换为真实值，
--      形如 https://tkyreautvukkwpwmisbg.supabase.co（无结尾斜杠）；
--   2. 该 SQL 在【业务库所在项目】执行：
--       - 业务库随新项目重建 → 在恢复数据后、于新库执行；
--       - 业务库留在老项目 → 在老库执行（对象已拷走后，改写指向新桶）。
--   3. 先跑「核对」段确认影响行数，再跑「改写」段，最后跑「复核」段。
--
-- 幂等说明：replace() 会把旧前缀全部替换为新前缀，重复执行无害
-- （再次执行时已无旧前缀可替换）。
-- =====================================================================

-- ---------- 0. 替换这两行为真实值 ----------
-- SELECT '<OLD_PROJECT_URL>' AS old_url, '<NEW_PROJECT_URL>' AS new_url;

-- ---------- 1. 核对：改写前受影响的行数（应 ≈ 拷贝的对象数分布） ----------
SELECT 'qwt_venues.image_url'    AS col, count(*) FROM qwt_venues       WHERE image_url         LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_venues.photos',      count(*) FROM qwt_venues       WHERE photos           LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_venues.wechat_qr',   count(*) FROM qwt_venues       WHERE wechat_qr        LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancers.avatar_url', count(*) FROM qwt_dancers      WHERE avatar_url       LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancers.contact_image_url', count(*) FROM qwt_dancers WHERE contact_image_url LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancer_photos.url',  count(*) FROM qwt_dancer_photos WHERE url               LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancer_photos.blur_url', count(*) FROM qwt_dancer_photos WHERE blur_url      LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_users.avatar_url',   count(*) FROM qwt_users        WHERE avatar_url       LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_venue_claims.license_urls', count(*) FROM qwt_venue_claims WHERE license_urls LIKE '<OLD_PROJECT_URL>%';

-- ---------- 2. 改写：旧前缀 → 新前缀 ----------
UPDATE qwt_venues        SET image_url         = replace(image_url,         '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE image_url         LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_venues        SET photos            = replace(photos,            '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE photos            LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_venues        SET wechat_qr         = replace(wechat_qr,         '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE wechat_qr         LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_dancers       SET avatar_url        = replace(avatar_url,        '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE avatar_url        LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_dancers       SET contact_image_url = replace(contact_image_url, '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE contact_image_url LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_dancer_photos SET url               = replace(url,               '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE url               LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_dancer_photos SET blur_url          = replace(blur_url,          '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE blur_url          LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_users         SET avatar_url        = replace(avatar_url,        '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE avatar_url        LIKE '<OLD_PROJECT_URL>%';
UPDATE qwt_venue_claims  SET license_urls      = replace(license_urls,      '<OLD_PROJECT_URL>', '<NEW_PROJECT_URL>') WHERE license_urls      LIKE '<OLD_PROJECT_URL>%';

-- ---------- 3. 复核：改写后应全为 0（残留即未命中，需排查） ----------
SELECT 'qwt_venues.image_url'    AS col, count(*) FROM qwt_venues       WHERE image_url         LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_venues.photos',      count(*) FROM qwt_venues       WHERE photos           LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_venues.wechat_qr',   count(*) FROM qwt_venues       WHERE wechat_qr        LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancers.avatar_url', count(*) FROM qwt_dancers      WHERE avatar_url       LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancers.contact_image_url', count(*) FROM qwt_dancers WHERE contact_image_url LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancer_photos.url',  count(*) FROM qwt_dancer_photos WHERE url               LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_dancer_photos.blur_url', count(*) FROM qwt_dancer_photos WHERE blur_url      LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_users.avatar_url',   count(*) FROM qwt_users        WHERE avatar_url       LIKE '<OLD_PROJECT_URL>%'
UNION ALL SELECT 'qwt_venue_claims.license_urls', count(*) FROM qwt_venue_claims WHERE license_urls LIKE '<OLD_PROJECT_URL>%';
