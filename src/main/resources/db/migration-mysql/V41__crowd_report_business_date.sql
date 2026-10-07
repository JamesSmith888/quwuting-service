-- ============================================================================
-- V41（MySQL 轨道 = db/migration-mysql）: 热度上报「一人一夜一票」——唯一键从自然日改为营业日
--      （2026-10-07，文档 = docs/agents/53-venue-crowd-stats.md / 前端 58-crowd-stats-and-likes.md）
--
-- ⚠️ 编号说明：PG 遗留轨道（db/migration）与 MySQL 轨道编号各自独立、勿混用。
--
-- ── 根因 ────────────────────────────────────────────────────────────────────
-- 旧唯一键 = (venue_id, user_id, report_date)，report_date = 自然日。营业时段跨午夜
-- （生产实测 37 条有效上报里约 1/3 发生在 23:00~01:00），所以同一个人同一夜可以投两票：
-- 23:50 一票（report_date=D）、00:10 又一票（report_date=D+1），两行同时落进 6h 窗口被重复计票，
-- 确认积分也会按两个不同的行 id 各发一次。「一夜」是业务概念、自然日是日历概念——唯一键用错了坐标系。
--
-- ── 解法 ────────────────────────────────────────────────────────────────────
-- 新增 business_date（营业日：05:00 之前归属前一天，口径见 CrowdPolicy.BUSINESS_DAY_START_HOUR），
-- 唯一键改为 (venue_id, user_id, business_date)。
--
-- ⛔ report_date **不改语义、不删列**：它是行为统计口径的「日列」（UserStatsSql / UserBehaviorSql /
--    UserBehaviorEvent 把它当作用户当天有过该动作的自然日），改成营业日会让 00:00~05:00 的上报
--    在活跃天数 / 留存口径里整体前移一天，与其它事件表的自然日错位。两个日期各管各的坐标系。
--
-- ── 05:00 分界的依据（生产只读取证，2026-10-07）──────────────────────────────
--   有效上报按小时分布：00-02 点 4 条、12-23 点 33 条、**03:00~11:00 零条**
--   ⇒ 分界落在死区，不会把同一场夜劈成两个营业日。
--
-- ── 存量回填与冲突处理 ──────────────────────────────────────────────────────
--   · business_date = DATE(created_at − 5h)。created_at 是「最近一次上报/改报时刻」（改报会刷新它），
--     即这一行此刻所属的夜。created_at 理论上可空（历史 PG 数据），依次回退 updated_at /
--     report_date 当日 12:00（取正午：裸日期按 00:00 算会被 −5h 推到前一天，语义错位）。
--   · 若存量里已经存在「同一人同一夜」的多行（正是本迁移要封堵的情形），保留 created_at 最新的一行，
--     其余软删（deleted=1）——与读侧「一人一票取最新」同口径。2026-10-07 取证冲突 0 组，此段为
--     部署时点上的防御（迁移失败 = 应用无法启动，宁可多一句确定性的去重语句）。
--
-- ── 执行顺序为什么是这样 ────────────────────────────────────────────────────
--   先加可空列 → 回填 → 去重 → 换唯一键（必须在去重之后，否则建唯一索引会因存量冲突失败）→ 收紧 NOT NULL。
--   旧生成列是 STORED，索引依附于列，所以先 DROP INDEX 再 DROP COLUMN。
--   MySQL DDL 不可回滚、不要求每步幂等（Flyway 失败即停、人工 repair）。诚实的失败模式：若「换键」
--   这两步本身失败，表会暂时没有唯一约束——但此时 Flyway 已标记失败、应用不会启动，不存在并发写入窗口，
--   人工重跑后两步即可（本迁移已在本地 MySQL 8.0 上用含冲突数据的样本验证，见 53 号文档「验证」节）。
-- ============================================================================

ALTER TABLE qwt_venue_crowd_reports ADD COLUMN business_date date NULL;

UPDATE qwt_venue_crowd_reports
SET business_date = DATE(DATE_SUB(COALESCE(created_at, updated_at, TIMESTAMP(report_date, '12:00:00')), INTERVAL 5 HOUR));

UPDATE qwt_venue_crowd_reports r
JOIN (
    SELECT ranked.id
    FROM (
        SELECT id,
               ROW_NUMBER() OVER (PARTITION BY venue_id, user_id, business_date
                                  ORDER BY created_at DESC, id DESC) AS rn
        FROM qwt_venue_crowd_reports
        WHERE deleted = 0
    ) ranked
    WHERE ranked.rn > 1
) dup ON dup.id = r.id
SET r.deleted = 1;

ALTER TABLE qwt_venue_crowd_reports DROP INDEX qwt_idx_crowd_reports_user_day;
ALTER TABLE qwt_venue_crowd_reports DROP COLUMN uk_key_qwt_idx_crowd_reports_user_day;

ALTER TABLE qwt_venue_crowd_reports MODIFY COLUMN business_date date NOT NULL;

ALTER TABLE qwt_venue_crowd_reports
    ADD COLUMN uk_key_qwt_idx_crowd_reports_user_night varchar(32)
        GENERATED ALWAYS AS (IF((deleted = 0), MD5(CONCAT_WS('#',
            COALESCE(CAST(venue_id AS CHAR), '<n>'),
            COALESCE(CAST(user_id AS CHAR), '<n>'),
            COALESCE(CAST(business_date AS CHAR), '<n>'))), NULL)) STORED;

CREATE UNIQUE INDEX qwt_idx_crowd_reports_user_night
    ON qwt_venue_crowd_reports (uk_key_qwt_idx_crowd_reports_user_night);
