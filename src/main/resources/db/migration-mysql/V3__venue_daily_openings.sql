-- 门店每日营业快照（Agent 每日更新管线写库目标，2026-08-31）
-- 与 PostgreSQL V63 实体映射保持一致；MySQL 8 语法按 V1 baseline 先例转换：
--   * PG 部分唯一索引（WHERE deleted=false）→ 生成列 uk_key_<idx> =
--     IF(cond, MD5(...), NULL) STORED + CREATE UNIQUE INDEX（见 V1 头部注释第 3 条）
--   * Repository upsert 用 ON DUPLICATE KEY UPDATE（与 VenueCrowdReportRepository 同构）
--
-- 语义：只记录「信息源声称某店某日营业/休息」的当日快照事实，不是平台长期状态
-- （venue.status 权威仍在 qwt_venues）。幂等：同店同日报导源至多一条，
-- 重复 apply = 覆盖原行（status/confidence 刷新 + created_at 更新 = 该源当日
-- 最新确认时刻，口径同 V59 crowd reports 每日一记）。deleted 恒 false。

CREATE TABLE qwt_venue_daily_openings (
    id            bigint NOT NULL AUTO_INCREMENT,
    created_at    datetime(6),
    updated_at    datetime(6),
    deleted       tinyint(1) NOT NULL DEFAULT 0,
    venue_id      bigint NOT NULL,
    report_date   date NOT NULL,
    source_id     varchar(50) NOT NULL,
    status        varchar(10) NOT NULL,   -- OPEN / CLOSED（信息源语义）
    confidence    varchar(20) NOT NULL,   -- EXACT / ALIAS / CONTAINED / FUZZY
    uk_key_qwt_idx_daily_openings_unique varchar(32) GENERATED ALWAYS AS (IF((deleted = 0), MD5(CONCAT_WS('#', COALESCE(CAST(venue_id AS CHAR), '<n>'), COALESCE(CAST(report_date AS CHAR), '<n>'), COALESCE(CAST(source_id AS CHAR), '<n>'))), NULL)) STORED,
    PRIMARY KEY (id)
);

-- !! 部分唯一索引 → 生成列 uk_key_qwt_idx_daily_openings_unique + UNIQUE（见 CREATE TABLE 生成列）
CREATE UNIQUE INDEX qwt_idx_daily_openings_unique ON qwt_venue_daily_openings (uk_key_qwt_idx_daily_openings_unique);

-- 列表/详情注入「今日营业」：按日期查当日快照（venue_id IN + report_date），与实体 @Index 对齐
CREATE INDEX qwt_idx_daily_openings_date_venue ON qwt_venue_daily_openings (report_date, venue_id);
