-- ── V20：行业快讯浏览统计（2026-09-11，docs/agents/47-bulletins.md「九、浏览统计」） ──
-- 背景：快讯列表页是信息流（全文内联、列表即本体），"查看人数"按**信息流展示即计**
-- 口径统计——快讯被加载展示时前端 fire-and-forget 批量上报，服务端按
-- **(bulletin_id, user_id, view_date) 去重**（同一用户同一条快讯同一天只计 1 次，
-- 跨天再刷重新计 = 每日展示 PV 口径，对齐 V2 qwt_venue_views 的按天去重先例；
-- 快讯无门店域 V21 的 source 维度——信息流只有一个展示入口，无需归因分列）。
--
-- 设计要点：
-- ① 唯一键 qwt_uk_bv_user_bulletin_date 承载"每人每条每天一次"的语义，写入走
--    批量 INSERT ... ON DUPLICATE KEY UPDATE id = id（BulletinViewService 用
--    JdbcTemplate 单语句多行 VALUES，一次 DB 往返覆盖整页，满足"最少 DB 往返"
--    第一约束——快讯每页 10 条，逐条 upsert 就是 10 次跨洲往返）。
-- ② 计数不缓存：与表态同理由（快讯体量小），列表页按整页 id 一次 IN + GROUP BY 聚合。
-- ③ user_id NOT NULL：信息流接口全部需登录（UserContext.requireAuth），上报端点
--    同样鉴权，无匿名路径。
-- ④ 不建外键（全库无 FK 约定）；bulletin_id 语义引用由应用层保证（写入前不校验
--    可见性——上报是纯计数埋点，指向已软删/已下线条目的历史行无需拦截，计数聚合
--    只发生在用户端可见列表的 id 集合内）。
-- ⑤ 时间戳由 Java 侧传 LocalDateTime（全库约定，禁 DB now()）；deleted 列是
--    BaseEntity 契约列，浏览记录永不软删，恒 0。

CREATE TABLE qwt_bulletin_views (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6) NOT NULL,
    updated_at datetime(6) NOT NULL,
    deleted tinyint(1) NOT NULL,
    bulletin_id bigint NOT NULL,
    user_id bigint NOT NULL,
    view_date date NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_bv_user_bulletin_date UNIQUE (bulletin_id, user_id, view_date)
);

-- 列表页整页聚合走 (bulletin_id, view_date) 前缀（IN + GROUP BY bulletin_id）
CREATE INDEX qwt_idx_bv_bulletin_date ON qwt_bulletin_views (bulletin_id, view_date);
