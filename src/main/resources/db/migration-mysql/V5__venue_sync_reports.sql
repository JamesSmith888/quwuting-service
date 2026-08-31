-- 门店同步报告存档（2026-08-31，Web 管理后台「门店同步」页数据源）
-- 管线（quwuting-ops/venue-opening）跑完一次抓取+匹配后，把报告 JSON 上报本表；
-- Web 后台只读本表展示「最近报告/条目/确认写库」，与管线解耦（管线离线可跑）。
-- 幂等：同渠道同报告日只存一份，重复上报 = 覆盖（刷新当次数据）。
-- summary/items 用 TEXT/LONGTEXT 存 JSON 串（规避 Hibernate validate 对 MySQL JSON
-- 列的类型校验不确定性；读写均由 Service 层用 ObjectMapper 编解码）。
CREATE TABLE qwt_venue_sync_reports (
    id            bigint NOT NULL AUTO_INCREMENT,
    created_at    datetime(6),
    updated_at    datetime(6),
    deleted       tinyint(1) NOT NULL DEFAULT 0,
    report_date   date NOT NULL,
    source_id     varchar(50) NOT NULL,
    source_label  varchar(100) NOT NULL DEFAULT '',
    report_url    varchar(500) NOT NULL DEFAULT '',
    summary       longtext NOT NULL,  -- JSON：统计摘要
    items         longtext NOT NULL,  -- JSON：条目数组（MatchResult 镜像）
    uk_key_qwt_idx_sync_reports_unique varchar(32) GENERATED ALWAYS AS (IF((deleted = 0), MD5(CONCAT_WS('#', COALESCE(CAST(report_date AS CHAR), '<n>'), COALESCE(CAST(source_id AS CHAR), '<n>'))), NULL)) STORED,
    PRIMARY KEY (id)
);

-- 部分唯一索引 → 生成列 + UNIQUE（V1 baseline 先例，见 qwt_venue_daily_openings）
CREATE UNIQUE INDEX qwt_idx_sync_reports_unique ON qwt_venue_sync_reports (uk_key_qwt_idx_sync_reports_unique);
CREATE INDEX qwt_idx_sync_reports_date ON qwt_venue_sync_reports (report_date);
