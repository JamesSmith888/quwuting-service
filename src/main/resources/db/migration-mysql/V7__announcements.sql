-- ── V7：全局公告（2026-09-01，docs/agents/34-announcements.md 设计定稿） ──────────
-- 双场景统一一套系统：运营公告（MANUAL 人工发布）+ 数据更新公告（SYSTEM 系统自动），
-- 差异仅 source 字段；管理面在 Web 管理后台（quwuting-admin-web），小程序端只消费。
--
-- MySQL 8 语法（V1 baseline 先例）：
--   * 状态/枚举列一律 varchar，禁 CHECK（扩枚举免迁移，对齐 ReportStatus 先例）；
--   * 时间戳由 Java 侧 LocalDateTime.now() 写入（时间戳红线，禁 DB now() 写业务列）；
--   * DATA_UPDATE 同日防重 → 生成列 + 表外 CREATE UNIQUE INDEX（V1 头部注释第 3 条先例）。

CREATE TABLE qwt_announcements (
    id            bigint NOT NULL AUTO_INCREMENT,
    created_at    datetime(6),
    updated_at    datetime(6),
    deleted       tinyint(1) NOT NULL DEFAULT 0,
    title         varchar(100) NOT NULL COMMENT '标题（≤50 字）',
    content       mediumtext NOT NULL COMMENT 'Markdown 原文',
    category      varchar(32) NOT NULL COMMENT 'NOTICE 运营公告 / DATA_UPDATE 数据更新',
    source        varchar(16) NOT NULL COMMENT 'MANUAL 人工 / SYSTEM 系统',
    scope         varchar(16) NOT NULL DEFAULT 'ALL' COMMENT '一期仅 ALL，预留 CITY 城市粒度',
    status        varchar(20) NOT NULL DEFAULT 'DRAFT' COMMENT 'DRAFT / PUBLISHED / OFFLINE',
    pinned        tinyint(1) NOT NULL DEFAULT 0 COMMENT '置顶（列表排序权重）',
    publish_at    datetime(6) NULL COMMENT '计划发布时间（定时发布）',
    offline_at    datetime(6) NULL COMMENT '计划下线时间',
    published_at  datetime(6) NULL COMMENT '实际发布时间',
    offlined_at   datetime(6) NULL COMMENT '实际下线时间',
    operator_id   bigint NULL COMMENT '操作管理员；SYSTEM 来源 = NULL（Agent 来源先例）',
    -- DATA_UPDATE 同日防重唯一键：同一天同来源同类别至多一条（重复同步不重复发）
    uk_key_qwt_idx_ann_daily_data_update varchar(32) GENERATED ALWAYS AS (
        IF((deleted = 0) AND (source = 'SYSTEM') AND (category = 'DATA_UPDATE'),
           MD5(CONCAT_WS('#', source, category, DATE(COALESCE(publish_at, created_at)))),
           NULL)) STORED,
    PRIMARY KEY (id)
);

-- 部分唯一索引 → 生成列 + UNIQUE（V1 baseline 先例）
CREATE UNIQUE INDEX qwt_idx_ann_daily_data_update ON qwt_announcements (uk_key_qwt_idx_ann_daily_data_update);

-- 用户端可见列表：PUBLISHED + 生效时间过滤（pinned 优先 + 时间倒序）
CREATE INDEX qwt_idx_ann_status_publish ON qwt_announcements (status, publish_at);

CREATE TABLE qwt_announcement_reads (
    id               bigint NOT NULL AUTO_INCREMENT,
    created_at       datetime(6),
    updated_at       datetime(6),
    deleted          tinyint(1) NOT NULL DEFAULT 0,
    user_id          bigint NOT NULL COMMENT '读者',
    announcement_id  bigint NOT NULL COMMENT '公告',
    read_at          datetime(6) NOT NULL COMMENT 'Java 侧写入',
    PRIMARY KEY (id),
    -- 幂等标记已读的天然约束（重复插入 → 23505 幂等语义）；已读记录不软删（事实保留）
    UNIQUE KEY qwt_idx_ann_reads_user_ann (user_id, announcement_id),
    KEY qwt_idx_ann_reads_ann (announcement_id)
);

-- ── 数据更新公告开关与模板（键即代码契约：OpsConfigService 定义常量 + 本迁移插入默认行） ──
-- 默认关闭（'false'）：自动公告钩子（M4）落地前不产生任何 SYSTEM 公告；
-- 模板占位符 {new} = 新增门店数、{reversed} = 恢复营业数（createSystem 填充后写入 content）。
-- 注意：qwt_ops_config.key 是 MySQL 保留字，INSERT 列名必须反引号（V1 baseline 第 5 条契约）。
INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at) VALUES
('announcement.data_update.enabled', 'false', NULL, now()),
('announcement.data_update.template', '今日舞讯更新：新增 {new} 家门店、{reversed} 家门店恢复营业', NULL, now());
