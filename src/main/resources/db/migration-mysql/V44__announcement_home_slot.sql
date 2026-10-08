-- ── V44：首页公告位互斥（2026-10-08，docs/agents/34-announcements.md「首页公告位」） ──────
-- 根因（不是需求，是缺陷）：`pinned` 是布尔列，而首页公告位是**容量为 1 的稀缺资源**。
--   布尔表达不了"唯一占用" ⇒ 允许 N 条同时 pinned=1；而全系统没有任何一处声明过
--   "容量=1"，唯一约束落在消费端 listAnnouncements(0, 1, true) 的 size=1 上，
--   也就是**在读端做容量限制** ⇒ 任何绕过首页消费的写路径都能写入多条置顶，
--   读端静默取第一条⇒ 「置顶」这个运营动作系统性不兑现。
--   本轮生产实测：36 条可见公告同时 pinned=1 且永不下线，首页位被每日舞讯流水占据，
--   真正需要触达的运营公告（ALERT）反而进不去。
-- 修复落点：把稀缺资源的不变量从读端搬到**写入端 + 数据层**，三层设防：
--   ① 领域层 HomeSlotService = 占位记账唯一写入点（占位前校验、冲突显式拒绝）；
--   ② 本迁移 = 数据层兜底（生成列 + UNIQUE INDEX，绕过应用层直接改库也无法两条占位）；
--   ③ 自愈：AnnouncementService 30s 调度调HomeSlotService#reconcile 兜底收敛。

-- 步骤 1：存量归位 —— 释放全部置顶，仅保留**最新一条 ALERT（需触达）**。
-- 保留判据 = touch_level='ALERT'（用户不知道会吃亏的那类），id 最大者胜出
--   （与首页读端排序 pinned DESC, publish_at DESC, id DESC 同源⇒ 保留的就是
--    用户实际能看到的那条，不会出现"保住了另一条、首页显示的还是它"）。
-- 为何清掉其余：36 条里35 条是每日舞讯 / 历史数据更新（SILENT 流水），
--   它们既不需要被"顶置"又永不下线，正是霸占首页位的机制本身。
-- 判据写在这里而不手抄 id = 迁移可重放、语义自解释。
UPDATE qwt_announcements
   SET pinned = 0
 WHERE deleted = 0
   AND pinned = 1
   AND id <> (
        SELECT id FROM (
            SELECT id
              FROM qwt_announcements
             WHERE deleted = 0
               AND pinned = 1
               AND touch_level = 'ALERT'
             ORDER BY id DESC
             LIMIT 1
        ) AS keeper
   );

-- 步骤 2：数据层不变量 —— 部分唯一索引 = 生成列 + UNIQUE（V7 DATA_UPDATE 同日防重
-- 与 V18 dedup_key 同款手法）。生成列在 pinned=1 且未软删时取常量 'HOME_SLOT'，
-- 否则取 NULL；MySQL 唯一索引忽略 NULL ⇒ 未置顶的行之间互不冲突，
-- 而**至多一条** pinned 行参与唯一约束。
-- 为什么条件里不含 status/publish_at：占位与可见性刻意解耦（草稿也能占位 ="预定"），
--   这样定时任务批量强转 PUBLISHED/OFFLINE 时不触碰 pinned ⇒ 永不与本索引冲突。
--   代价是"位被占"会在发布之前就暴露（运营更早看到），收益是定时路径零冲突。
ALTER TABLE qwt_announcements
    ADD COLUMN uk_key_qwt_idx_ann_home_slot varchar(16) GENERATED ALWAYS AS (
        IF((deleted = 0) AND (pinned = 1), 'HOME_SLOT', NULL)
    ) STORED COMMENT '首页公告位互斥键：至多一条（应用层 HomeSlotService + 本唯一索引双保险）';

CREATE UNIQUE INDEX qwt_idx_ann_home_slot ON qwt_announcements (uk_key_qwt_idx_ann_home_slot);