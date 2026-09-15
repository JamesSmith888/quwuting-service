-- ── V26：公告触达等级（2026-09-15，docs/agents/34-announcements.md「触达等级」） ──────
-- 根因：公告未读口径原为「全部可见公告 − 已读回执」（NOT EXISTS 派生），隐含前提是
-- 「每条公告都是一个需要用户确认知悉的事项」。09-09「公告分开制」（同日可并存多条
-- DATA_UPDATE）+ 09-15「高置信永远自动发公告」之后，公告从低频运营内容变成日更高频
-- 流水（每日舞讯），前提被业务演进打破而未读模型没有随之演进 ⇒ 徽标只增不减、用户被迫
-- 逐条点进详情、最终对徽标脱敏。
-- 修复：把「这条公告是否构成用户的未读债务」显式建模为 touch_level，未读口径以它为唯一判据。
--   * ALERT  —— 计入未读徽标 / 未读红点（新功能、规则变更等需要用户知晓的信息）
--   * SILENT —— 恒不计入未读；仍出现在公告中心列表、仍可搜索阅读、置顶仍进首页公告栏
-- 缺省 'ALERT' = 保持既有行为（新建条目未显式指定时宁可多提醒一次，也不静默丢掉触达）。
-- 列类型 varchar(16) 与实体 AnnouncementTouchLevel（EnumType.STRING）对齐——铁律：新迁移
-- 列类型必须与实体字段 Java 类型匹配（V13/V14 的 tinyint×Integer 启动期事故先例）。
--
-- 存量回填：category <> 'NOTICE' 的条目按语义降为 SILENT——
--   DATA_UPDATE（数据更新 / 每日舞讯）是流水记录，价值在「可查」不在「必读」；
--   FLASH（行业快讯）本就无已读回执（靠 category 排除在未读之外，取 SILENT 与之一致）。
-- 回填是纯口径切换、不动任何已读回执：用户侧历史未读徽标随之自然归零，不需要逐条补回执。

ALTER TABLE qwt_announcements
    ADD COLUMN touch_level varchar(16) NOT NULL DEFAULT 'ALERT'
        COMMENT '触达等级：ALERT 计入未读提醒 / SILENT 不打扰（仅可查）' AFTER category;

UPDATE qwt_announcements SET touch_level = 'SILENT' WHERE category <> 'NOTICE';
