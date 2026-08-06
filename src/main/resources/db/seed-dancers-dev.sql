-- ============================================================================
-- quwuting-service 舞伴生态体系开发环境测试数据（PostgreSQL / Supabase）
-- ============================================================================
-- 使用前提：
--   1. 先执行 seed-dev.sql（依赖其用户 1/2/3 与场所 1/2）
--   2. 应用已以 dev profile 启动过（ddl-auto: update 自动创建 qwt_dancers /
--      qwt_dancer_venues / qwt_dancer_recognitions / qwt_dancer_recognition_tags）
--   3. 在 Supabase SQL Editor 或 psql 中手动执行本脚本
--
-- 演示要点（认可 = 每日一记模型，同 Reaction）：
--   窗口锚点"此刻"（今日 2026-08-06）：近7天 = 07-31 ~ 08-06；更早只计入"全部"。
--   舞伴 1（小雅，NORMAL）：用户3 累计 5 日认可（近7天 3、今日 1），用户2 累计 2 日
--     （近7天 2、今日 0）→ countAll=7 / count7d=5 / countToday=1，排序演示"近7天优先"。
--   舞伴 2（阿丽，PENDING）：演示主动注册的审核中状态——列表不可见，详情仅本人/管理员可见。
--   舞伴 3（林姐，NORMAL，常驻场所1）：认可较少，演示"老数据不永久占优"的排序对比。
-- ============================================================================

BEGIN;

-- ── 舞伴 ─────────────────────────────────────────────────────────────────────
-- 舞伴 1/3：管理员后台创建（status=NORMAL 直接公开，created_by = 管理员 1）
-- 舞伴 2：用户 3 主动注册（status=PENDING 待认证，created_by = 用户 3）
INSERT INTO qwt_dancers (id, nickname, avatar_url, bio, gender, status, created_by, city, created_at, updated_at, deleted) VALUES
(1, '小雅',
 'https://picsum.photos/seed/xiaoya/200/200',
 '交谊舞爱好者，主攻慢四，周末常在红玫瑰晚场，欢迎新手一起跳。', 'FEMALE',
 'NORMAL', 1, '杭州市', '2026-06-01 10:00:00', '2026-07-20 10:00:00', false),
(2, '阿丽',
 NULL,
 '刚注册的舞伴资料，等待管理员审核。', NULL,
 'PENDING', 3, '杭州市', '2026-08-05 20:00:00', '2026-08-05 20:00:00', false),
(3, '林姐',
 'https://picsum.photos/seed/linjie/200/200',
 '莎悦驻场，快三一绝，人很随和，喜欢带新人。', 'FEMALE',
 'NORMAL', 1, '绍兴市', '2026-05-10 09:00:00', '2026-06-15 09:00:00', false);

-- ── 舞伴-舞厅关系（HOME 常驻 / APPEARANCE 出现，多舞厅不强制绑定） ──────────

INSERT INTO qwt_dancer_venues (id, dancer_id, venue_id, relation, note, created_at, updated_at, deleted) VALUES
(1, 1, 2, 'HOME',       '每周六晚场', '2026-06-01 10:00:00', '2026-06-01 10:00:00', false),
(2, 1, 1, 'APPEARANCE', '7月参加过一次主题舞会', '2026-07-18 21:00:00', '2026-07-18 21:00:00', false),
(3, 3, 1, 'HOME',       '常驻', '2026-05-10 09:00:00', '2026-05-10 09:00:00', false);

-- ── 认可记录（每日一记：同一用户同一舞伴每日至多一行） ───────────────────────

INSERT INTO qwt_dancer_recognitions (id, user_id, dancer_id, recognition_date, created_at, updated_at, deleted) VALUES
-- 舞伴 1（小雅）：用户3 认可 5 日（07-20 / 07-28 / 08-02 / 08-04 / 08-06），用户2 认可 2 日（08-01 / 08-05）
(1,  3, 1, '2026-07-20', '2026-07-20 21:00:00', '2026-07-20 21:00:00', false),
(2,  3, 1, '2026-07-28', '2026-07-28 20:30:00', '2026-07-28 20:30:00', false),
(3,  3, 1, '2026-08-02', '2026-08-02 20:00:00', '2026-08-02 20:00:00', false),
(4,  3, 1, '2026-08-04', '2026-08-04 21:10:00', '2026-08-04 21:10:00', false),
(5,  3, 1, '2026-08-06', '2026-08-06 19:30:00', '2026-08-06 19:30:00', false),
(6,  2, 1, '2026-08-01', '2026-08-01 20:40:00', '2026-08-01 20:40:00', false),
(7,  2, 1, '2026-08-05', '2026-08-05 21:20:00', '2026-08-05 21:20:00', false),
-- 舞伴 2（阿丽，PENDING）：仅用户2 认可 1 日（演示"未认证资料不参与公开列表"）
(8,  2, 2, '2026-08-05', '2026-08-05 22:00:00', '2026-08-05 22:00:00', false),
-- 舞伴 3（林姐）：用户3 认可 2 日（07-25 / 08-03）
(9,  3, 3, '2026-07-25', '2026-07-25 20:15:00', '2026-07-25 20:15:00', false),
(10, 3, 3, '2026-08-03', '2026-08-03 21:05:00', '2026-08-03 21:05:00', false);

-- ── 认可标签（标签来源 = 用户认可行为，tag 必须命中 DancerTagCode 字典） ─────

INSERT INTO qwt_dancer_recognition_tags (id, recognition_id, dancer_id, user_id, tag, created_at, updated_at, deleted) VALUES
(1,  3, 1, 3, 'GOOD_VIBE',        '2026-08-02 20:00:00', '2026-08-02 20:00:00', false),
(2,  4, 1, 3, 'EASY_TALK',        '2026-08-04 21:10:00', '2026-08-04 21:10:00', false),
(3,  4, 1, 3, 'PATIENT',          '2026-08-04 21:10:00', '2026-08-04 21:10:00', false),
(4,  5, 1, 3, 'DANCE',            '2026-08-06 19:30:00', '2026-08-06 19:30:00', false),
(5,  5, 1, 3, 'BEGINNER_FRIENDLY','2026-08-06 19:30:00', '2026-08-06 19:30:00', false),
(6,  7, 1, 2, 'DANCE',            '2026-08-05 21:20:00', '2026-08-05 21:20:00', false),
(7,  9, 3, 3, 'GOOD_VIBE',        '2026-07-25 20:15:00', '2026-07-25 20:15:00', false),
(8, 10, 3, 3, 'DANCE',            '2026-08-03 21:05:00', '2026-08-03 21:05:00', false);

-- ── 重置 IDENTITY 序列（避免后续自增 ID 冲突） ───────────────────────────────

SELECT setval('qwt_dancers_id_seq', (SELECT MAX(id) FROM qwt_dancers));
SELECT setval('qwt_dancer_venues_id_seq', (SELECT MAX(id) FROM qwt_dancer_venues));
SELECT setval('qwt_dancer_recognitions_id_seq', (SELECT MAX(id) FROM qwt_dancer_recognitions));
SELECT setval('qwt_dancer_recognition_tags_id_seq', (SELECT MAX(id) FROM qwt_dancer_recognition_tags));

COMMIT;
