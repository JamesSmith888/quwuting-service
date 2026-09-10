-- ── V18：行业快讯（2026-09-10，docs/agents/47-bulletins.md 设计定稿） ──────────
-- 复用公告表 qwt_announcements：新增 category='FLASH' 作为快讯专属分类，与公告
-- （NOTICE / DATA_UPDATE）共用实体、状态机与 towxml 渲染链路，但走独立接口与入口。
-- 语义边界（用户 2026-09-10 拍板）：
--   * 快讯只描述「服务可得性」（停业 / 开闭店 / 时段调整），不描述事件与原因；
--   * 只读单向（零投稿 / 零评论 / 零点赞），发布动作只在管理后台与 Agent 接口；
--   * 一期不做城市筛选，city 仅作列表卡片展示标签。
--
-- 差异仅三列 + 一个 Agent 幂等唯一键：
--   * city      —— 城市标签（列表卡片展示）
--   * venue_id  —— 关联门店（可空；列表卡片锚点）
--   * dedup_key —— Agent 幂等去重键（采集重跑保护，非空才参与唯一约束）
--
-- MySQL 8 语法约束同 V7（枚举列 varchar 禁 CHECK / 时间戳 Java 侧写入 /
-- 「部分唯一索引」= 生成列 + 表外 CREATE UNIQUE INDEX）。

ALTER TABLE qwt_announcements
    ADD COLUMN city      varchar(32) NULL COMMENT '快讯城市标签（category=FLASH 使用，一期仅展示不筛选）',
    ADD COLUMN venue_id  bigint      NULL COMMENT '快讯关联门店（可空）',
    ADD COLUMN dedup_key varchar(64) NULL COMMENT 'Agent 幂等去重键（非空才参与唯一约束）';

-- Agent 幂等唯一键：仅对「未软删 + FLASH + dedup_key 非空」生效（V7 DATA_UPDATE
-- 同日防重同款生成列写法）——未给 dedup_key 的管理端条目不受约束，可自由重名。
ALTER TABLE qwt_announcements
    ADD COLUMN uk_key_qwt_idx_ann_flash_dedup varchar(32) GENERATED ALWAYS AS (
        IF((deleted = 0) AND (category = 'FLASH') AND (dedup_key IS NOT NULL),
           MD5(dedup_key),
           NULL)) STORED;

CREATE UNIQUE INDEX qwt_idx_ann_flash_dedup ON qwt_announcements (uk_key_qwt_idx_ann_flash_dedup);

-- 快讯列表索引：category 前缀 + status + publish_at（用户端可见列表倒序扫描；
-- 公告侧既有 qwt_idx_ann_status_publish 不变，两类各自的列表各走各的索引）
CREATE INDEX qwt_idx_ann_category_status_publish ON qwt_announcements (category, status, publish_at);
