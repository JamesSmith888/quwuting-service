-- ============================================================================
-- 寻梦缘（江苏省南通市崇川区）测试业务数据
-- ============================================================================
-- 地址：校西路当家人对面电梯京扬广场2楼
-- 坐标：校西路 / 钟秀路一带近似坐标（120.873, 32.015），如需精确可用小程序
--       wx.chooseLocation 重新选点后 UPDATE qwt_venues 的 longitude/latitude。
--
-- 脚本特性：
--   * 自包含 —— 自动创建认领人用户、场所、动态、收藏，无需预先准备数据
--   * 不硬编码 ID —— 全部走 IDENTITY 自增并 RETURNING 捕获，与已有数据零冲突
--   * 幂等 —— 重复执行会自动跳过（检测到"寻梦缘·崇川区"已存在即返回）
--
-- 使用：应用以 dev profile 启动过（自动建表）后，在 Supabase SQL Editor 执行。
-- ============================================================================

BEGIN;

DO $$
DECLARE
  v_owner_id BIGINT;
  v_venue_id BIGINT;
BEGIN
  -- ── 幂等保护：已存在则跳过，避免重复执行产生重复数据 ──
  IF EXISTS (
    SELECT 1 FROM qwt_venues
    WHERE name = '寻梦缘' AND district = '崇川区' AND deleted = false
  ) THEN
    RAISE NOTICE '「寻梦缘」已存在，跳过本次插入';
    RETURN;
  END IF;

  -- ── 1. 门店认领人（用于测试"商家"身份的管理入口与动态发布） ──
  SELECT id INTO v_owner_id
  FROM qwt_users
  WHERE open_id = 'test_openid_xmy_owner' AND deleted = false;

  IF v_owner_id IS NULL THEN
    INSERT INTO qwt_users (open_id, nickname, avatar_url, role, created_at, updated_at, deleted)
    VALUES ('test_openid_xmy_owner', '寻梦缘店长', NULL, 'USER', now(), now(), false)
    RETURNING id INTO v_owner_id;
  END IF;

  -- ── 2. 场所：寻梦缘 ──
  INSERT INTO qwt_venues (
    name, status, image_url, photos, description, city, district, address,
    longitude, latitude, business_hours,
    tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
    created_at, updated_at, deleted
  ) VALUES (
    '寻梦缘', 'OPEN',
    'https://picsum.photos/seed/xunmengyuan-cover/800/600',
    '["https://picsum.photos/seed/xunmengyuan-1/800/600","https://picsum.photos/seed/xunmengyuan-2/800/600","https://picsum.photos/seed/xunmengyuan-3/800/600"]',
    '寻梦缘舞厅位于崇川区校西路京扬广场2楼（当家人对面，乘电梯直达）。场地宽敞明亮，空调开放，音响灯光专业。下午场以交谊舞、国标为主，晚场增设莎莎、拉丁等流行舞种，定期举办主题舞会。欢迎新老舞友光临，也可来电预约包场。',
    '南通市', '崇川区', '校西路当家人对面电梯京扬广场2楼',
    120.873, 32.015,
    '[{"name":"下午场","open":"14:00","close":"17:30"},{"name":"晚场","open":"19:00","close":"23:30"}]',
    '[{"label":"下午场","type":"FIXED","price":15},{"label":"晚场","type":"FIXED","price":20}]',
    '[{"unit":"MINUTE","minutes":4,"price":20},{"unit":"MINUTE","minutes":10,"price":45},{"unit":"MINUTE","minutes":60,"price":200}]',
    '13800005555', NULL,
    '["场地宽敞","空调开放"]',
    85, v_owner_id,
    '2026-06-15 10:00:00', '2026-07-20 12:00:00', false
  )
  RETURNING id INTO v_venue_id;

  -- ── 3. 门店动态：商家发布 2 条 + 平台发布 1 条 ──
  INSERT INTO qwt_venue_posts (venue_id, title, content, publisher_type, publisher_name, created_at, updated_at, deleted) VALUES
  (v_venue_id,
   '周末假面舞会 · 报名开启',
   '本周六晚19:30，寻梦缘举办夏季假面舞会，现场提供面具租赁，双人同行一人免票。提前电话预约可享按时段优惠，欢迎新老舞友到场！',
   'OWNER', '寻梦缘', '2026-07-18 11:00:00', '2026-07-18 11:00:00', false),
  (v_venue_id,
   '长期招聘舞伴 / 舞蹈老师',
   '寻梦缘长期招聘18-45岁舞蹈老师及舞伴，无需经验，热爱舞蹈、大方主动即可。工作时间19:00-23:30，待遇从优，日结。有意者请到店咨询或电话联系。',
   'OWNER', '寻梦缘', '2026-07-10 09:30:00', '2026-07-10 09:30:00', false),
  (v_venue_id,
   '平台提醒：请完善门店相册信息',
   '平台巡检发现贵店相册图片较少，建议补充3-9张实拍环境图，并确认营业时间标注准确。完善信息有助于提升门店热度与用户信任度。',
   'ADMIN', '去舞厅平台', '2026-07-05 15:00:00', '2026-07-05 15:00:00', false);

  -- ── 4. 收藏：由已有种子测试用户收藏（若种子用户不存在则自动跳过） ──
  INSERT INTO qwt_favorites (user_id, venue_id, created_at, updated_at, deleted)
  SELECT u.id, v_venue_id, now(), now(), false
  FROM qwt_users u
  WHERE u.open_id IN ('test_openid_user', 'test_openid_admin')
    AND u.deleted = false
    AND NOT EXISTS (
      SELECT 1 FROM qwt_favorites f
      WHERE f.user_id = u.id AND f.venue_id = v_venue_id AND f.deleted = false
    );

  RAISE NOTICE '寻梦缘测试数据插入完成，venue_id = %', v_venue_id;
END $$;

COMMIT;
