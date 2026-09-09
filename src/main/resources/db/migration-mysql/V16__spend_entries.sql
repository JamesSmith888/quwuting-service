-- 消费账本上云（2026-09-09，docs/agents/44-spend-ledger.md §12）：用户拍板
-- V1 上云采集，数据「用户自己可见」（接口全部 user-scoped，无任何公开分发口径）。
--
-- 设计要点：
-- ① 本地为源、云端为镜：小程序本地账目（qwt_dance_ledger_v1）是写入第一现场
--    （舞厅地下室弱网常态），经 POST /spend/entries/sync 批量幂等上报；本表是
--    同步副本 + 聚合数据源，不做业务端直写入口（除 sync）。
-- ② 幂等键 = user_id + client_entry_id（客户端生成、重放安全）：生成列
--    client_dedupe 仅在 deleted=0 时非 NULL——MySQL 无部分唯一索引，生成列 +
--    表外唯一索引是全库既有模式（软删行键置 NULL，可重复出现）；sync 逻辑按
--    (user_id, client_entry_id) 查存在即 UPDATE（含恢复 deleted=false），不存在
--    则 INSERT，天然幂等。
-- ③ venue_name 快照随账目落库：门店改名/删除后账目行仍可读（对齐 DanceRecord
--    规则快照思路）；venue_id 可空 = 未关联（定位失败且用户未手动挂），聚合时
--    作独立「未关联」桶诚实展示，禁混入 OTHER。
-- ④ category 固定 6 类枚举（TICKET/PARTNER/DRINK/SNACK/TRANSPORT/OTHER，禁自定义
--    ——装修陷阱 + UGC 风险，用户 2026-09-09 拍板）；source 区分自动/手动，是
--    统计口径可信度前提（场次只数 DANCE）。
-- ⑤ V1 手动记账零自由文本（无 note 列）——上云即 UGC，而全库零内容安全调用是
--    已知缺口；备注延 V2 且必须接 msgSecCheck（44 号文档 §12.3）。
-- ⑥ 时间口径：ts 为业务发生时刻（结算=停止时刻；手动=记账时刻），Java 传
--    LocalDateTime（JVM 时区），禁 DB now()（全库约定）；created_at/updated_at
--    由 BaseEntity 维护，updated_at 兼作增量拉取游标（跨设备恢复）。
-- ⑦ 不建外键（全库无 FK 约定）；user_id/venue_id 语义引用由应用层保证。

CREATE TABLE qwt_spend_entries (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    user_id bigint NOT NULL,
    client_entry_id varchar(32) NOT NULL,
    ts datetime(6) NOT NULL,
    amount decimal(10, 2) NOT NULL,
    category varchar(16) NOT NULL,
    source varchar(8) NOT NULL,
    source_ref_id varchar(32),
    venue_id bigint,
    venue_name varchar(100),
    duration_seconds int,
    client_dedupe varchar(96) GENERATED ALWAYS AS (IF(deleted = 0, CONCAT(user_id, ':', client_entry_id), NULL)) STORED,
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_spend_client_dedupe UNIQUE (client_dedupe)
);

CREATE INDEX qwt_idx_spend_user_ts ON qwt_spend_entries (user_id, deleted, ts);
CREATE INDEX qwt_idx_spend_user_venue ON qwt_spend_entries (user_id, venue_id, deleted, ts);
CREATE INDEX qwt_idx_spend_user_updated ON qwt_spend_entries (user_id, updated_at);
