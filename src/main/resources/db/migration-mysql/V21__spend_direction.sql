-- 消费账目方向（2026-09-11，docs/agents/44-spend-ledger.md §24）
--
-- 背景：计时器同时被「客人」与「舞伴」使用——客人计时 = 陪跳付费（支出），
-- 舞伴计时 = 陪跳收款（收入）。账本此前没有方向维度，舞伴的计费 100% 被记成
-- 支出。本迁移为 qwt_spend_entries 增加 direction 列，标识每笔账目的方向。
--
-- 设计要点：
-- ① direction 是**余额无符号**的方向标签（EXPENSE/INCOME），金额恒为正；
--    direction 决定展示（+/- 前缀）与「收入/结余」口径。
-- ② 默认 'EXPENSE'：存量行（上云版以来的全部账目）与老客户端载荷（不带
--    direction 字段，服务端缺省）天然收敛为支出语义，禁破坏性迁移。
-- ③ 枚举列禁 CHECK（全库既有约定：扩枚举免迁移）；varchar 长度 8 覆盖
--    最长枚举名 INCOME。
-- ④ 统计聚合（overview 的 total/byCategory/byVenueTop/unlinked/trend）
--    写侧统一过滤 direction='EXPENSE'——统计页是「消费分析」，收入不进
--    「支出」口径；而 summary 的 sessionCount（场次）仍统计全部 DANCE
--    条目（含收入场次）——"这个月跳了几场"对两类身份都成立。
-- ⑤ 时间列由 Java 传 LocalDateTime（全库约定，禁 DB now()）。

ALTER TABLE qwt_spend_entries
    ADD COLUMN direction varchar(8) NOT NULL DEFAULT 'EXPENSE' AFTER source;