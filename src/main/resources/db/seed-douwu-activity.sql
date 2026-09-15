-- ============================================================================
-- 抖舞跳舞俱乐部 · 双节活动录入（2026-09-16，MySQL 语法）
-- ============================================================================
-- 目标库：application-mysql.yaml → 阿里云生产 RDS（rm-bp1xe6rsjf57713004o / qwt_mysql）
--         ⚠️ 这是**生产库**。执行前请确认这是你的意图。
-- 前置：V27 迁移已生效（`qwt_venue_activities` 已存在 —— 已实测确认）。
--
-- ── 本脚本的录入原则：**只录门店海报上真实写着的，一个字都不补** ──
--   海报原文（2026-09-16）：
--     「迎中秋国庆双节双时段优惠活动：1.13:00-13:45；2.18:00-19:00。
--       以上时间段男女门票买一送一（赠券限七日内使用）票价 30 元/位。」
--
--   因此**刻意留空**两个字段，它们不是海报内容、而是需要与门店另行谈定的合作条款：
--     · redemption_mode   = 'OPEN_TO_ALL' —— 海报对**所有到店客人**生效，没有口令要求。
--       这也是事实：目前**没有**与门店约定任何平台专属口令。
--       ⚠️ 该值下 isAttributable() 为 false ⇒ 小程序**不渲染打卡按钮**，这是正确的：
--          还没有可归因的口令，打卡数字没有意义，展示一个点不出结果的按钮更糟。
--     · platform_addon    = NULL —— "报『去舞厅』多赠一瓶饮料"是我上一轮凭空写的，
--       门店从未承诺。**绝不能**在没有门店确认的情况下把承诺挂在线上。
--
--   ⚠️ 与门店谈定后，不改代码、只补两个字段即可开启归因（见文件末尾）。
--
-- ── 待你确认的一个值 ──
--   start_date 取 CURRENT_DATE（今天 9/16）：海报在 9/16 就在宣传这个活动，
--   按"已经在跑"处理。若门店的意思是"双节（中秋 9/25 起）才生效"，
--   把下一行的 CURRENT_DATE 改成 '2026-09-25' 再执行。
--   end_date 取 '2026-10-08'（国庆假期结束次日），请与门店核对。
-- ============================================================================

-- ── 幂等：按「门店 id + 活动名」查重，重跑不会产生第二条 ──
INSERT INTO qwt_venue_activities (
    venue_id, title, benefit_kind, badge_label, benefit_summary,
    redemption_mode, redemption_hint, platform_addon,
    outer_type, start_date, end_date, weekday_mask, windows,
    status, sort_weight,
    created_at, updated_at, deleted
)
SELECT
    v.id,
    '双节双时段 · 男女门票买一送一',
    'TICKET_B1G1',
    '买一送一',
    '票价 30 元/位，赠券限七日内使用。',
    'OPEN_TO_ALL',
    NULL,
    NULL,
    'DATE_RANGE',
    CURRENT_DATE,
    '2026-10-08',
    NULL,
    '[{"name":"","open":"13:00","close":"13:45"},{"name":"","open":"18:00","close":"19:00"}]',
    'PUBLISHED',
    10,
    NOW(), NOW(), 0
FROM qwt_venues v
WHERE v.name = '抖舞跳舞俱乐部'
  AND v.district = '崇川区'
  AND v.deleted = 0
  AND NOT EXISTS (
      SELECT 1 FROM qwt_venue_activities a
      WHERE a.venue_id = v.id
        AND a.title = '双节双时段 · 男女门票买一送一'
        AND a.deleted = 0
  )
-- ⚠️ 必须 LIMIT 1：库中存在同名门店（id=14 有效 / id=114 已软删），
-- 若将来再出现一条同名的未软删记录，本语句会一次插入 2 行活动（同一活动重复展示）。
-- 固定 ORDER BY v.id 取最小 id，结果确定、可复现。
ORDER BY v.id
LIMIT 1;

-- ── 执行后自检（应当返回 1 行）──
-- SELECT a.id, a.venue_id, v.name, a.title, a.benefit_kind, a.redemption_mode,
--        a.outer_type, a.start_date, a.end_date, a.windows, a.status
--   FROM qwt_venue_activities a
--   JOIN qwt_venues v ON v.id = a.venue_id
--  WHERE v.name = '抖舞跳舞俱乐部' AND a.deleted = 0;

-- ============================================================================
-- 与门店谈定「平台专属口令 + 加项」之后（不改代码，只补两个字段）
-- ============================================================================
--   UPDATE qwt_venue_activities a
--     JOIN qwt_venues v ON v.id = a.venue_id
--      SET a.redemption_mode = 'CODE_WORD',
--          a.redemption_hint = '到前台报「去舞厅」',
--          a.platform_addon  = '<门店确认的加项，如：多赠一瓶饮料>'
--    WHERE v.name = '抖舞跳舞俱乐部'
--      AND a.title = '双节双时段 · 男女门票买一送一';
--
-- 为什么加项是**必须**的：若门店对所有到店客人都是同一优惠，用户报不报口令没有区别，
-- 打卡数字就不能作为平台贡献的证据（归因失效）。加项建议用门店成本≈0 的实物小增项，
-- 而不是重复让利。判据见 docs/agents/49-venue-activities.md §7。
-- ============================================================================
