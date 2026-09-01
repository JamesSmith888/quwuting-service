-- 存量收藏回填营业状态关注（「收藏即关注」2026-09-01 新增，见 AGENTS.md「收藏门店营业状态通知」）
--
-- 背景：收藏门店从 2026-09-01 起自动建立营业状态关注（FavoriteService#addFavorite →
-- VenueStatusWatcherService#ensureWatching，同事务原子提交）——用户心智「收藏 = 在意的店」，
-- 该店营业状态每次实际变更都会收到站内信提醒（收藏列表「状态更新」角标 + 首页提醒卡）。
-- 但存量收藏（本迁移前已收藏的门店）没有关注记录，不迁移则状态变更不通知，需求落空。
--
-- 本迁移把全部现存收藏（deleted=false）一次性回填为关注记录：
--   - created_at/updated_at 取收藏行 created_at（用户「开始关注」的语义时刻 = 收藏时刻，
--     且收藏行 created_at 由 Java 写入（JVM 时区=北京时间），不引入 DB now()——
--     与 V63 时间口径红线一致（Supabase 会话时区 UTC，DB now() 会错位 8h））；
--   - NOT EXISTS 防重：已有手动关注（详情页开关）的 (user_id, venue_id) 不重复插入
--     （qwt_venue_status_watchers 唯一索引 (user_id, venue_id) 兜底，重复插入会炸）；
--   - 取消关注=物理删除（deleteByUserIdAndVenueId），不存在软删残留行，
--     NOT EXISTS 无需过滤 deleted（保留 deleted=false 条件纯防御，行为一致）。

INSERT INTO qwt_venue_status_watchers (user_id, venue_id, created_at, updated_at, deleted)
SELECT f.user_id, f.venue_id, f.created_at, f.created_at, false
FROM qwt_favorites f
WHERE f.deleted = false
  AND NOT EXISTS (
      SELECT 1 FROM qwt_venue_status_watchers w
      WHERE w.user_id = f.user_id AND w.venue_id = f.venue_id AND w.deleted = false
  );
