-- ── V17：浏览记录来源（qwt_venue_views.source，2026-08-13 新增） ──────
-- 需求（用户）：热度分析页新增「浏览来源」统计图——区分「列表进入」与「分享打开」
-- 两条折线（双序列折线 + 图例）。根因：qwt_venue_views 只有 venue_id/user_id/
-- view_date，详情页的浏览来自列表直进还是分享卡片回流无从区分，归因分析无数据基础。
--
-- 设计要点：
-- 1. source 列（varchar(16)，非空，默认 'OTHER'）承载来源枚举：
--    LIST=列表页进入、SHARE=分享卡片打开、OTHER=其他（搜索/收藏/深链等，兜底默认）；
-- 2. 默认 'OTHER' 兼容存量行——历史浏览无法回溯来源，统一标记 OTHER（新图数据自
--    版本上线之日起开始积累，属已知局限，见前端 AGENTS.md「浏览来源统计」）；
-- 3. 已登录用户按天去重（qwt_uq_venue_views_dedup 唯一约束），upsert DO NOTHING
--    保留「首次来源」（先列表进入后分享打开，同一天记列表）；匿名 user_id=NULL
--    不去重（UNIQUE 视 NULL 互不相等），每次访问均记录来源（60s IP 频控兜底）；
-- 4. 枚举类列不加 CHECK 约束（项目约定：应用层保证合法值，防迁移耦合）；
-- 5. 索引 (venue_id, view_date, source)：热度页「浏览来源」按来源分组计数走该索引。
ALTER TABLE qwt_venue_views ADD COLUMN source varchar(16) NOT NULL DEFAULT 'OTHER';
CREATE INDEX qwt_idx_venue_views_source ON qwt_venue_views (venue_id, view_date, source);
