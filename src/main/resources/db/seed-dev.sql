-- ============================================================================
-- quwuting-service 开发环境测试数据（PostgreSQL / Supabase）
-- ============================================================================
-- 使用前提：
--   1. 应用已以 dev profile 至少启动过一次（ddl-auto: update 自动建表）
--   2. 在 Supabase SQL Editor 或 psql 中手动执行本脚本
--
-- 测试账号说明（open_id 为伪造值，仅用于数据关联测试）：
--   用户 1 — 平台管理员（role = ADMIN）：所有门店可见管理入口
--   用户 2 — 莎悦老板（role = USER）：已认领场所 1，仅场所 1 可见管理入口
--   用户 3 — 舞友小王（role = USER）：普通用户，无管理入口
--
-- 真实微信登录测试时，需将下方 open_id 替换为真实用户的 openid，
-- 或在应用登录后执行：UPDATE qwt_users SET role = 'ADMIN' WHERE open_id = '<真实openid>';
-- ============================================================================

BEGIN;

-- ── 用户 ─────────────────────────────────────────────────────────────────────

INSERT INTO qwt_users (id, open_id, nickname, avatar_url, role, created_at, updated_at, deleted) VALUES
(1, 'test_openid_admin', '平台管理员', NULL, 'ADMIN', '2026-05-01 09:00:00', '2026-05-01 09:00:00', false),
(2, 'test_openid_owner', '莎悦老板',   NULL, 'USER',  '2026-05-10 14:30:00', '2026-05-10 14:30:00', false),
(3, 'test_openid_user',  '舞友小王',   NULL, 'USER',  '2026-06-01 20:15:00', '2026-06-01 20:15:00', false);

-- ── 场所 ─────────────────────────────────────────────────────────────────────

-- 场所 1：已被用户 2 认领（claimed_by = 2），有动态、有收藏，用于测试管理入口 + 动态 Tab + 热度
INSERT INTO qwt_venues (id, name, status, image_url, photos, description, city, district, address,
                        longitude, latitude, business_hours,
                        tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
                        created_at, updated_at, deleted) VALUES
(1, '莎悦莎莎舞主题音乐吧', 'OPEN',
 'https://picsum.photos/seed/shayue-cover/800/600',
 '["https://picsum.photos/seed/shayue-1/800/600","https://picsum.photos/seed/shayue-2/800/600","https://picsum.photos/seed/shayue-3/800/600"]',
 '浙江绍兴上虞莎悦音乐酒吧，自去年开业以来没停过一天，客流稳定。长期欢迎18—45之间的舞蹈老师加入，无需经验，只要热爱舞蹈、自信大方。工作时间为18:30-02:00，跳舞20/3-4分钟模式，按时段200-260/小时，日薪轻松过千。环境舒适，交通便利，客源充足。',
 '绍兴市', '上虞区', '崧厦街道佳源中心广场4楼（海英电影院旁边）',
 120.8742, 30.0312, '[{"name":"晚场","open":"18:30","close":"02:00"}]',
 '[{"label":"晚场","type":"FIXED","price":20}]',
 '[{"unit":"MINUTE","minutes":3,"price":20},{"unit":"MINUTE","minutes":4,"price":25},{"unit":"MINUTE","minutes":60,"price":200}]',
 '13800001111', NULL,
 '["驻场舞伴","可按时段","招聘中"]', 100, 2,
 '2026-05-10 15:00:00', '2026-07-01 10:00:00', false);

-- 场所 2：正常营业，下午场 + 晚场，有动态
INSERT INTO qwt_venues (id, name, status, image_url, photos, description, city, district, address,
                        longitude, latitude, business_hours,
                        tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
                        created_at, updated_at, deleted) VALUES
(2, '红玫瑰交谊舞厅', 'OPEN',
 'https://picsum.photos/seed/redrose-cover/800/600',
 '["https://picsum.photos/seed/redrose-1/800/600","https://picsum.photos/seed/redrose-2/800/600"]',
 '老牌交谊舞厅，场地宽敞，灯光音响一流。下午场以中老年国标交谊为主，晚场面向年轻舞友，定期举办主题舞会。',
 '杭州市', '西湖区', '文三路388号钱江科技大厦2楼',
 120.1305, 30.2725, '[{"name":"下午场","open":"13:30","close":"17:30"},{"name":"晚场","open":"19:00","close":"23:00"}]',
 '[{"label":"下午场","type":"FIXED","price":15},{"label":"晚场","type":"FIXED","price":20}]',
 '[{"label":"5点前","unit":"MINUTE","minutes":5,"price":20},{"label":"5点后","unit":"MINUTE","minutes":5,"price":30},{"unit":"MINUTE","minutes":10,"price":50}]',
 '13800002222', NULL,
 '["国标","场地宽敞","主题舞会"]', 90, NULL,
 '2026-05-12 10:00:00', '2026-06-20 16:00:00', false);

-- 场所 3：高端定位，仅晚场
INSERT INTO qwt_venues (id, name, status, image_url, photos, description, city, district, address,
                        longitude, latitude, business_hours,
                        tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
                        created_at, updated_at, deleted) VALUES
(3, '金色年华歌舞厅', 'OPEN',
 'https://picsum.photos/seed/golden-cover/800/600',
 NULL,
 '静安区高端歌舞会所，拉丁、爵士舞池分区，配备专业DJ与驻场乐队，适合商务社交与舞蹈爱好者。',
 '上海市', '静安区', '南京西路1266号恒隆广场5楼',
 121.4453, 31.2286, '[{"name":"晚场","open":"19:30","close":"01:00"}]',
 '[{"label":"","type":"FIXED","price":50}]',
 '[{"unit":"MINUTE","minutes":5,"price":50},{"unit":"MINUTE","minutes":10,"price":80},{"unit":"MINUTE","minutes":60,"price":300}]',
 '13800003333', NULL,
 '["拉丁","爵士","驻场乐队","高端商务"]', 80, NULL,
 '2026-05-15 11:00:00', '2026-05-15 11:00:00', false);

-- 场所 4：装修中
INSERT INTO qwt_venues (id, name, status, image_url, photos, description, city, district, address,
                        longitude, latitude, business_hours,
                        tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
                        created_at, updated_at, deleted) VALUES
(4, '星光大道音乐舞厅', 'RENOVATING',
 'https://picsum.photos/seed/starlight-cover/800/600',
 NULL,
 '鄞州区大型音乐舞厅，正在装修升级中，预计8月重新开业，届时将新增VIP包间与全景灯光秀。',
 '宁波市', '鄞州区', '中山东路2266号世纪东方广场3楼',
 121.5630, 29.8620, '[{"name":"晚场","open":"19:00","close":"01:30"}]',
 '[{"label":"","type":"FIXED","price":25}]',
 NULL,
 '13800004444', NULL,
 '["装修升级中","即将开业"]', 70, NULL,
 '2026-06-01 09:00:00', '2026-07-10 09:00:00', false);

-- 场所 5：已停业（门票数据演示"时段免票"模式：4点前免票 + 4点后固定票价）
INSERT INTO qwt_venues (id, name, status, image_url, photos, description, city, district, address,
                        longitude, latitude, business_hours,
                        tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
                        created_at, updated_at, deleted) VALUES
(5, '老地方舞厅', 'CEASED',
 NULL,
 NULL,
 '经营十五年的老舞厅，因场地租约到期已于2026年5月停业。感谢各位舞友多年来的支持。',
 '绍兴市', '越城区', '解放南路1188号',
 120.5820, 30.0010, '[{"name":"下午场","open":"14:00","close":"17:00"},{"name":"晚场","open":"19:00","close":"22:00"}]',
 '[{"label":"下午4点前","type":"FREE"},{"label":"下午4点后","type":"FIXED","price":10}]',
 '[{"unit":"MINUTE","minutes":5,"price":20}]',
 NULL, NULL,
 '["老舞厅"]', 10, NULL,
 '2026-05-05 08:00:00', '2026-05-31 18:00:00', false);

-- 场所 6：西安连曲模式（按曲数计费，演示 SONG 计量单位）
INSERT INTO qwt_venues (id, name, status, image_url, photos, description, city, district, address,
                        longitude, latitude, business_hours,
                        tickets, partner_fees, contact_phone, wechat_qr, tags, sort_weight, claimed_by,
                        created_at, updated_at, deleted) VALUES
(6, '金舞池连曲舞厅', 'OPEN',
 'https://picsum.photos/seed/goldpool-cover/800/600',
 NULL,
 '西安老牌连曲舞厅，采用连曲模式计费，每曲约3分钟。环境优雅，舞池宽敞，适合交谊舞爱好者。',
 '西安市', '碑林区', '南大街168号金舞池大厦2楼',
 108.9465, 34.2610, '[{"name":"下午场","open":"14:00","close":"18:00"},{"name":"晚场","open":"19:30","close":"23:00"}]',
 '[{"label":"","type":"FIXED","price":20}]',
 '[{"unit":"SONG","minutes":3,"price":30},{"unit":"SONG","minutes":4,"price":40},{"unit":"SONG","minutes":5,"price":50}]',
 '13800006666', NULL,
 '["连曲模式","场地宽敞"]', 75, NULL,
 '2026-06-10 10:00:00', '2026-07-01 10:00:00', false);

-- ── 场所动态 ─────────────────────────────────────────────────────────────────

-- 场所 1 的动态：商家发布（招聘、活动）+ 平台发布（规范通知）
INSERT INTO qwt_venue_posts (id, venue_id, title, content, publisher_type, publisher_name, created_at, updated_at, deleted) VALUES
(1, 1, '长期招聘舞蹈老师（18-45岁）',
 '浙江绍兴上虞莎悦音乐酒吧，自去年开业以来没停过一天，客流稳定。长期欢迎18—45之间的舞蹈老师加入，无需经验，只要热爱舞蹈、自信大方、主动热情。工作时间为18:30-02:00，跳舞20/3-4分钟模式，按时段200-260/小时，日薪轻松过千。环境舒适，交通便利，客源充足，赶快把握机会加入我们！',
 'OWNER', '莎悦莎莎舞主题音乐吧', '2026-06-20 10:30:00', '2026-06-20 10:30:00', false),
(2, 1, '七夕专场派对预告',
 '8月19日七夕当晚举办「浪漫莎莎」专场派对，全场灯光升级，前50名入场赠送饮品券。按时段优惠同步开放预订，欢迎新老舞友到场。',
 'OWNER', '莎悦莎莎舞主题音乐吧', '2026-07-15 14:00:00', '2026-07-15 14:00:00', false),
(3, 1, '平台提醒：请完善门店相册与营业信息',
 '平台巡检发现贵店相册图片较少，建议补充3-9张实拍环境图，并确认营业时间标注准确。完善信息有助于提升门店热度与用户信任度。',
 'ADMIN', '去舞厅平台', '2026-07-01 09:00:00', '2026-07-01 09:00:00', false);

-- 场所 2 的动态：商家发布
INSERT INTO qwt_venue_posts (id, venue_id, title, content, publisher_type, publisher_name, created_at, updated_at, deleted) VALUES
(4, 2, '本周六假面舞会报名开启',
 '本周六晚19:30举办夏季假面舞会，现场提供面具租赁。双人同行一人免票，请提前电话预约。',
 'OWNER', '红玫瑰交谊舞厅', '2026-07-18 11:00:00', '2026-07-18 11:00:00', false);

-- ── 收藏 ─────────────────────────────────────────────────────────────────────

INSERT INTO qwt_favorites (id, user_id, venue_id, created_at, updated_at, deleted) VALUES
(1, 3, 1, '2026-06-05 21:00:00', '2026-06-05 21:00:00', false),
(2, 3, 2, '2026-06-10 20:30:00', '2026-06-10 20:30:00', false),
(3, 2, 2, '2026-06-12 19:00:00', '2026-06-12 19:00:00', false);

-- ── 评分交互（维度评分；liked 列为历史遗留字段，Reaction 快速反馈系统已替代原"标签点赞"） ──

-- 场所 1 维度评分：用户 2 和 3 对场所 1 的服务/环境/音响/性价比打分（liked 列不再读写，历史遗留恒为 true/false）
INSERT INTO qwt_tag_interactions (id, user_id, venue_id, tag, liked, score, created_at, updated_at, deleted) VALUES
(4, 3, 1, '服务',       false, 8,    '2026-06-20 21:00:00', '2026-07-20 10:00:00', false),
(5, 3, 1, '环境',       false, 7,    '2026-06-20 21:01:00', '2026-07-20 10:01:00', false),
(6, 3, 1, '音响效果',   false, 9,    '2026-06-20 21:02:00', '2026-06-20 21:02:00', false),
(7, 3, 1, '性价比',     false, 6,    '2026-06-20 21:03:00', '2026-07-21 15:00:00', false),
(8, 2, 1, '服务',       false, 9,    '2026-07-01 12:00:00', '2026-07-19 09:00:00', false),
(9, 2, 1, '环境',       false, 8,    '2026-07-01 12:01:00', '2026-07-01 12:01:00', false),
(10, 2, 1, '性价比',    false, 7,    '2026-07-01 12:02:00', '2026-07-18 20:00:00', false);

-- 场所 2 维度评分
INSERT INTO qwt_tag_interactions (id, user_id, venue_id, tag, liked, score, created_at, updated_at, deleted) VALUES
(13, 3, 2, '服务',      false, 7,    '2026-06-15 20:00:00', '2026-06-15 20:00:00', false),
(14, 3, 2, '环境',      false, 8,    '2026-06-15 20:01:00', '2026-06-15 20:01:00', false),
(15, 3, 2, '音响效果',  false, 9,    '2026-06-15 20:02:00', '2026-06-15 20:02:00', false),
(16, 2, 2, '服务',      false, 6,    '2026-07-10 14:00:00', '2026-07-16 11:00:00', false),
(17, 2, 2, '性价比',    false, 5,    '2026-07-10 14:01:00', '2026-07-10 14:01:00', false);

-- ── 场所 Reaction（替代原"标签点赞"，见 AGENTS.md「Reaction 快速反馈系统」） ──────────
-- 每日一记模型（2026-08）：每行 = 某用户在某个 reaction_date 的一次点击，
-- 同一用户同一场所同一 code 每日至多一行（UNIQUE(user_id, venue_id, reaction_code, reaction_date)）。
-- 窗口统计锚点"此刻"（今日 2026-08-05）：
--   近7天  = 2026-07-30 ~ 08-05；近30天 = 2026-07-06 ~ 08-05；更早只计入"全部"。
-- 演示要点：用户3 对场所1 的 HOT 在 06-28 / 07-25 / 07-31 / 08-04 各点击一次
-- （每日一记累计 countAll=4、近7天=2）；场所1 今日（08-05）有 VIBRANT_PARTNER / RECOMMEND。
-- 注：2026-08-12 字典瘦身 18 → 14，GOOD_VIBE / GOOD_MUSIC / NORMAL / CROWDED 已删除、
--   VALUE 改名 PRICE_HIKE（✌ 舞伴加价，负面）——本 seed 全部使用现存 code。

INSERT INTO qwt_venue_reactions (id, user_id, venue_id, reaction_code, reaction_date, created_at, updated_at, deleted) VALUES
-- 场所1（莎悦）——
(1,  3, 1, 'HOT',           '2026-06-28', '2026-06-28 21:00:00', '2026-06-28 21:00:00', false),
-- 2026-07-25 原 HOT(id=2)+VIBRANT_PARTNER(id=6) 同日多行已收敛（每日一票模型，保留 max(id)=6 的最新意图）
(6,  3, 1, 'VIBRANT_PARTNER', '2026-07-25', '2026-07-25 20:01:00', '2026-07-25 20:01:00', false),
(3,  2, 1, 'HOT',           '2026-07-28', '2026-07-28 21:00:00', '2026-07-28 21:00:00', false),
(4,  3, 1, 'HOT',           '2026-07-31', '2026-07-31 20:30:00', '2026-07-31 20:30:00', false),
-- 2026-08-04 原 HOT(id=5)+RECOMMEND(id=11) 同日多行已收敛（保留 max(id)=11）
(11, 3, 1, 'RECOMMEND',     '2026-08-04', '2026-08-04 20:00:00', '2026-08-04 20:00:00', false),
-- 2026-08-05 原 VIBRANT_PARTNER(id=7)+RECOMMEND(id=9) 同日多行已收敛（保留 max(id)=9）
(9,  3, 1, 'RECOMMEND',     '2026-08-05', '2026-08-05 11:30:00', '2026-08-05 11:30:00', false),
(8,  2, 1, 'SWEET_PARTNER', '2026-08-02', '2026-08-02 20:15:00', '2026-08-02 20:15:00', false),
(10, 1, 1, 'RECOMMEND',     '2026-08-02', '2026-08-02 19:40:00', '2026-08-02 19:40:00', false),
(12, 2, 1, 'MATURE_PARTNER', '2026-08-01', '2026-08-01 21:20:00', '2026-08-01 21:20:00', false),
(13, 2, 1, 'SWEET_PARTNER', '2026-07-28', '2026-07-28 21:01:00', '2026-07-28 21:01:00', false),
(14, 1, 1, 'GOOD_SERVICE',  '2026-07-20', '2026-07-20 15:00:00', '2026-07-20 15:00:00', false),
-- 场所2（红玫瑰）——
-- 2026-06-12 原 HOT(id=15)+VIBRANT_PARTNER(id=16) 同日多行已收敛（保留 max(id)=16）
(16, 3, 2, 'VIBRANT_PARTNER', '2026-06-12', '2026-06-12 19:36:00', '2026-06-12 19:36:00', false),
(17, 3, 2, 'RECOMMEND',     '2026-08-03', '2026-08-03 21:00:00', '2026-08-03 21:00:00', false),
(18, 3, 2, 'RECOMMEND',     '2026-08-04', '2026-08-04 19:30:00', '2026-08-04 19:30:00', false),
(19, 2, 2, 'SWEET_PARTNER', '2026-08-02', '2026-08-02 16:00:00', '2026-08-02 16:00:00', false),
(20, 3, 2, 'GOOD_SERVICE',  '2026-07-15', '2026-07-15 20:00:00', '2026-07-15 20:00:00', false);

-- ── 舞友群（2026-08-17 新增，V33；二维码为本地占位 URL，仅开发展示用） ─────

INSERT INTO qwt_group_chats (id, name, scope, city, region, qr_code_url, description, display_order, enabled, created_at, updated_at, deleted, updated_by) VALUES
(1, '全国舞友交流群', 'NATIONWIDE', NULL, NULL, 'https://example.com/qr/nationwide.png', '全国舞友交流、组局、场地信息共享', 0, true, now(), now(), false, NULL),
(2, '杭州舞友群',     'CITY',       '杭州市', NULL, 'https://example.com/qr/hangzhou.png', '杭州本地舞友交流群', 1, true, now(), now(), false, NULL);

-- ── 重置 IDENTITY 序列（避免后续自增 ID 冲突） ───────────────────────────────

SELECT setval('qwt_users_id_seq', (SELECT MAX(id) FROM qwt_users));
SELECT setval('qwt_venues_id_seq', (SELECT MAX(id) FROM qwt_venues));
SELECT setval('qwt_venue_posts_id_seq', (SELECT MAX(id) FROM qwt_venue_posts));
SELECT setval('qwt_favorites_id_seq', (SELECT MAX(id) FROM qwt_favorites));
SELECT setval('qwt_tag_interactions_id_seq', (SELECT MAX(id) FROM qwt_tag_interactions));
SELECT setval('qwt_venue_reactions_id_seq', (SELECT MAX(id) FROM qwt_venue_reactions));
SELECT setval('qwt_group_chats_id_seq', (SELECT MAX(id) FROM qwt_group_chats));

COMMIT;
