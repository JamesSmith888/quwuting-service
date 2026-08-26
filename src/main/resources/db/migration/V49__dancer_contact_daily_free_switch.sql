-- ── V49：联系方式「每日首免」运营开关（2026-08-26 新增） ──────────────
-- 需求（用户）：「每个用户每天可免费解锁一个舞伴的联系方式」功能先暂时下线，
-- 提取为运营开关（默认关闭 = 下线），后续可在「我的 → 运营配置」页即时恢复。
--
-- 背景：2026-08-24 V42 起，有积分门槛（cost>0）的舞伴联系方式对每个用户每日首次
-- 获取免费（hasGatedContactUnlockToday 判定），无门槛舞伴恒免费（gate 不存在，
-- 与每日首免正交，不受本开关影响）。
--
-- 设计要点（对齐 V31 先例——配置驱动的代码路径，数据层不硬编码产品规则）：
-- 1. 新键 = 'dancer.contact.daily.free'，默认 'false' = 首免下线（暂不提供），
--    有门槛舞伴一律按门槛消耗积分；
-- 2. 开关开启（'true'）= 恢复原每日首免逻辑（PointsService#unlock 分支）；
-- 3. 前端仅展示层文案（解锁结果 toast/徽标「今日首次 · 免费」）由后端
--    UnlockResponse.freeToday 驱动，开关关闭时恒 false，自动收敛，无需数据卫生。
INSERT INTO qwt_ops_config (key, value, updated_by, updated_at)
VALUES ('dancer.contact.daily.free', 'false', NULL, now());
