-- 门店别名表（2026-09-07，docs/agents/38-venue-aliases.md）
-- 语义：管理员维护的门店「用户可见别名」（曾用名/俗称/圈内涵称）——舞厅常改名，
-- 用户只记得老名字/圈内叫法时靠别名搜索认店、详情页核对身份。
-- 与 qwt_venue_sync_aliases（V6，同步管线的「信息源店名 → 平台门店」映射，
-- 导出 aliases.json 给 matcher 消费）语义严格分离：
--   * sync alias = 管线匹配配置（key 含 city，用户不可见）；
--   * 本表     = 门店身份属性（搜索可命中 + 详情页展示，见 VenueDetailResponse.aliases）。
-- 幂等：同店同名至多一条有效记录（deleted=false 生成列唯一键，V1 baseline 先例）；
-- 删除走软删，重新添加同名时复活重用（VenueAliasService#upsert）。

CREATE TABLE qwt_venue_aliases (
    id            bigint NOT NULL AUTO_INCREMENT,
    created_at    datetime(6),
    updated_at    datetime(6),
    deleted       tinyint(1) NOT NULL DEFAULT 0,
    venue_id      bigint NOT NULL,
    alias         varchar(100) NOT NULL,
    uk_key_qwt_idx_venue_aliases_unique varchar(32) GENERATED ALWAYS AS (IF((deleted = 0), MD5(CONCAT_WS('#', COALESCE(CAST(venue_id AS CHAR), '<n>'), COALESCE(CAST(alias AS CHAR), '<n>'))), NULL)) STORED,
    PRIMARY KEY (id)
);

-- 部分唯一索引 → 生成列 + UNIQUE（V1 baseline 先例，同 V6__venue_sync_aliases）
CREATE UNIQUE INDEX qwt_idx_venue_aliases_unique ON qwt_venue_aliases (uk_key_qwt_idx_venue_aliases_unique);

-- 单店别名读取（详情公共部分 / 管理端聚合列表）
CREATE INDEX qwt_idx_venue_aliases_venue ON qwt_venue_aliases (venue_id);
