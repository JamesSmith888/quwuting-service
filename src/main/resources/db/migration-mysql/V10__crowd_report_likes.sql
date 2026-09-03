-- 今晚热度行级点赞「有用」（2026-09-03，docs/agents/27-venue-crowd-report.md「行级点赞」）
--
-- 背景：确认后积分 = 系统认可（算法判「报得准」才发），报错档位的少数派拿不到任何正反馈，
-- 且确认门槛（≥3 人一致）在冷启动（DAU 5~36）下难触发。行级点赞补上「人际认可」层：
-- 任何用户（含本人，自赞放开但不自动点亮）随手一条「这条有用」，零成本、即时、人人可得。
--
-- 设计要点：
-- ① 唯一约束 (liker_id, report_id) = 软删 toggle（对齐 qwt_favorites 收藏 toggle 先例，
--    非每日一记式生成列部分唯一索引）：每人每行至多 1 赞，再点 = 取消（deleted=true）；
--    取消后再赞 = UPDATE 恢复原行（deleted=false）——被赞通知义务只在该对「首次赞」时
--    履行一次（取消再赞不重发），判定由 INSERT ... ON DUPLICATE KEY 受影响行数派生
--    （1 = 首插、2 = 恢复/重复），无额外标志列（YAGNI）。
-- ② 赞数永不进算法（可信度加权/置信度/列表角标/热度公式）——自赞可刷，纯展示层精神激励。
-- ③ 上报行被管理端删除（软删）后不再出现在 summary/history → 赞随行不可见，无需级联清理
--    （行保留作审计）；删除后当日重报 = 新行 0 赞（内容已变，赞不迁移）。
-- ④ 时间口径：created_at/updated_at 一律由 Java 传 JVM LocalDateTime.now()（北京时间），
--    禁 DB now()（全库约定，见 qwt_venue_crowd_reports upsert 注释）。
-- ⑤ 不建外键（全库无 FK 约定）；liker_id/report_id 语义引用由应用层保证。

CREATE TABLE qwt_venue_crowd_report_likes (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    report_id bigint NOT NULL,
    liker_id bigint NOT NULL,
    PRIMARY KEY (id)
);

ALTER TABLE qwt_venue_crowd_report_likes ADD CONSTRAINT qwt_uk_crowd_like_liker_report UNIQUE (liker_id, report_id);
CREATE INDEX qwt_idx_crowd_likes_report ON qwt_venue_crowd_report_likes (report_id);
