-- 门店同步 · 手动映射别名表（2026-08-31，与 PostgreSQL V64 实体映射保持一致）
-- 与 PostgreSQL V64 实体映射保持一致；MySQL 8 语法按 V1 baseline 先例转换：
--   * PG 部分唯一索引（WHERE deleted=false）→ 生成列 uk_key_<idx> =
--     IF(cond, MD5(...), NULL) STORED + CREATE UNIQUE INDEX（见 V1 头部注释第 3 条）
--
-- 语义：管理员在 Web 管理后台「门店同步 → 映射管理」手工配置的
-- 「网上门店名称（信息源店名）→ 平台门店」映射，等价于管线 Matcher 的
-- 别名表（ALIAS 置信度命中）。管线 --refresh-aliases 从后端拉取
-- （GET /admin/venue-sync/aliases/export）写入本地 data/aliases.json。
-- key = (city, source_name)：城市用标准城市名（对齐 cities.json 口径），
-- source_name 为信息源清洗后店名（对齐报告 source_name）。幂等：同城同名
-- 至多一条（生成列唯一键）。deleted 恒 false。

CREATE TABLE qwt_venue_sync_aliases (
    id            bigint NOT NULL AUTO_INCREMENT,
    created_at    datetime(6),
    updated_at    datetime(6),
    deleted       tinyint(1) NOT NULL DEFAULT 0,
    city          varchar(50) NOT NULL,
    source_name   varchar(100) NOT NULL,
    venue_id      bigint NOT NULL,
    note          varchar(200) NOT NULL DEFAULT '',
    uk_key_qwt_idx_sync_aliases_unique varchar(32) GENERATED ALWAYS AS (IF((deleted = 0), MD5(CONCAT_WS('#', COALESCE(CAST(city AS CHAR), '<n>'), COALESCE(CAST(source_name AS CHAR), '<n>'))), NULL)) STORED,
    PRIMARY KEY (id)
);

-- 部分唯一索引 → 生成列 + UNIQUE（V1 baseline 先例）
CREATE UNIQUE INDEX qwt_idx_sync_aliases_unique ON qwt_venue_sync_aliases (uk_key_qwt_idx_sync_aliases_unique);

-- 列表展示按最近配置倒序（与实体 @Index 对齐）
CREATE INDEX qwt_idx_sync_aliases_updated ON qwt_venue_sync_aliases (updated_at);
