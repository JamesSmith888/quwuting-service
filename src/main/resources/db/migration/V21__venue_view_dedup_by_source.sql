-- ── V21：浏览去重粒度 = 按天按来源（2026-08-13 晚产品决策：搜索/列表是不同流量） ──
-- 需求（用户）：搜索结果进入的浏览必须计入 SEARCH 来源折线，不能因当天已先经其他入口
--（列表/收藏/分享）进入同一家店而被按天去重吞掉——"搜索和列表进去的是不一样的流量"。
--
-- 设计要点：
-- 1. 去重唯一键由 (venue_id, user_id, view_date) 扩展为 (venue_id, user_id, view_date, source)：
--    同一用户同一场所同一天，每个来源至多记一条（多渠道独立计数，互不覆盖）；
-- 2. 防刷语义保留：已登录用户一天内同一来源最多 1 条（全来源合计最多 4 条），
--    脚本连点/刷新无法反复放大单一来源 PV；匿名 userId=NULL 不受唯一索引约束
--    （UNIQUE 视 NULL 互不相等），每次访问均记录（60s IP 频控兜底，语义不变）；
-- 3. 统计口径自洽：viewcount 随行数变化（多渠道用户当日计多行），每行有且仅有一个
--    source，list + share + search + other = viewcount 恒成立；UV
--    （COUNT(DISTINCT user_id)）不受影响（仍按用户去重）；
-- 4. 兼容性修复（潜在历史根因）：旧 upsert 用 `ON CONFLICT ON CONSTRAINT`，但 V1 基线
--    创建的是 CREATE UNIQUE INDEX（唯一索引，非约束）——该语法只匹配约束、不匹配索引，
--    生产库若保持索引形态，浏览写入每次抛错且被 fire-and-forget 静默吞掉（浏览来源折线
--    全 0 的潜在根因，与缓存失效缺失叠加）。本迁移重建唯一索引（含 source 列），
--    配合 upsert 改用 `ON CONFLICT (列清单)` 列推断（对索引/约束两种形态均健壮）。
--    历史文件（V1/V18）不改动，避免 Flyway checksum 失配；
-- 5. 存量兼容：旧唯一键下同 (venue, user, date) 仅一行（每行单个来源），扩展列集后
--    无冲突，重建唯一索引不会失败。
ALTER TABLE qwt_venue_views DROP CONSTRAINT IF EXISTS qwt_uq_venue_views_dedup;
DROP INDEX IF EXISTS qwt_uq_venue_views_dedup;
CREATE UNIQUE INDEX qwt_uq_venue_views_dedup ON qwt_venue_views (venue_id, user_id, view_date, source);
