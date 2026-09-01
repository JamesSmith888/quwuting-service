-- 存量收藏回填营业状态关注（「收藏即关注」2026-09-01 新增，见 AGENTS.md「收藏门店营业状态通知」）
--
-- 背景：收藏门店从 2026-09-01 起自动建立营业状态关注（FavoriteService#addFavorite →
-- VenueStatusWatcherService#ensureWatching，同事务原子提交）——用户心智「收藏 = 在意的店」，
-- 该店营业状态每次实际变更都会收到站内信提醒（收藏列表「状态更新」角标 + 首页提醒卡）。
-- 但存量收藏（本迁移前已收藏的门店）没有关注记录，不迁移则状态变更不通知，需求落空。
--
-- 本迁移一件事：存量收藏一次性回填为关注记录。
--
-- 说明：唯一索引 (user_id, venue_id) 与 venue 索引已在 V1 baseline 创建
-- （V1__baseline.sql 第 1171-1175 行，与 PG V14 实体契约一致），此处不重复建索引；
-- ensureWatching 幂等依赖的唯一约束兜底已由 V1 索引提供。
--
-- 回填 INSERT：created_at/updated_at 取收藏行 created_at（用户「开始关注」的语义时刻 =
-- 收藏时刻，收藏行 created_at 由 Java 写入，不引入 DB 时钟）；NOT EXISTS 防重（已有手动
-- 关注不重复插入）；取消关注 = 物理删除，无软删残留行，deleted=false 过滤纯防御。
-- 幂等：可安全重跑（NOT EXISTS 守卫）——若在 pg2mysql 数据搬运前意外执行（空表 → 回填
-- 0 行），数据到位后重跑本脚本或手动执行同 SQL 即可。

INSERT INTO qwt_venue_status_watchers (user_id, venue_id, created_at, updated_at, deleted)
SELECT f.user_id, f.venue_id, f.created_at, f.created_at, false
FROM qwt_favorites f
WHERE f.deleted = false
  AND NOT EXISTS (
      SELECT 1 FROM qwt_venue_status_watchers w
      WHERE w.user_id = f.user_id AND w.venue_id = f.venue_id AND w.deleted = false
  );
