-- ============================================================================
-- V11: 实时状态报告泛化为「紧急公告」信号层（2026-08-11）
--
-- 背景：详情页「紧急公告」区域需要展示门店最近数小时的突发事件（突然检查/情况
-- 不明/暂停营业/舞池不开/突然清场/恢复营业/突然关门/禁龙）。现有 qwt_venue_status_reports
-- 只有 reason 一维（CHECK/UNKNOWN/CLEARED，均为"暂停营业"的子原因），无法表达
-- 8 类事件。泛化方案（不新建表，遵循「扩场景=扩枚举」约定）：
--
--   ① type varchar(20) NOT NULL：事件类型（ReportType 枚举），替代 reason。
--      存量行按 reason 回填映射（CHECK→SUDDEN_INSPECTION / UNKNOWN→SITUATION_UNCLEAR /
--      CLEARED→SUDDEN_EVICTION，其余兜底 SUSPENDED——历史数据均为暂停报语义）；
--  ② expires_at timestamp(6) NOT NULL：按类型分级的过期时刻（写入时 = createdAt +
--      类型 TTL）。TTL 唯一事实源从 Service 层常量迁移到本列——所有"活跃"判定
--      （热度计数 / 公开列表 / 管理端列表 / hasMyReport）统一改判 expires_at > now()，
--      替代旧的 created_at >= now - 4h 单窗口（旧窗口无法表达分级 TTL）。
--  ③ admin_action varchar(20) NULL：管理端处置标记（ADOPTED=已采纳 / REMOVED=已移除）。
--      旧实现采纳与移除同为 soft delete（deleted=true），公开视图无法区分"已核实"
--      与"已清理"。公告区需要展示"已核实"标记，故引入本列区分两种处置语义。
--
-- 约束演进：UNIQUE(user_id, venue_id) 保留——每用户对每门店仍只有一条当前信号，
-- 换类型上报 = upsert 覆盖（新增类型不做多行并存，避免 chip 状态机与恢复模型复杂化）。
--
-- 幂等：Flyway 按版本只执行一次；IF NOT EXISTS / IF EXISTS 为防御性写法。
-- ============================================================================

-- ① 新增列（可空 → 回填 → SET NOT NULL，遵循「Schema 演进」规则 2）
ALTER TABLE qwt_venue_status_reports ADD COLUMN type varchar(20);
ALTER TABLE qwt_venue_status_reports ADD COLUMN expires_at timestamp(6);
ALTER TABLE qwt_venue_status_reports ADD COLUMN admin_action varchar(20);

-- ② 存量回填：reason → type 映射（历史数据均为暂停报语义）
UPDATE qwt_venue_status_reports
   SET type = CASE reason
       WHEN 'CHECK'   THEN 'SUDDEN_INSPECTION'
       WHEN 'UNKNOWN' THEN 'SITUATION_UNCLEAR'
       WHEN 'CLEARED' THEN 'SUDDEN_EVICTION'
       ELSE 'SUSPENDED'
   END
 WHERE type IS NULL;

-- 过期时刻回填：存量行按旧 TTL 常量（4h）补 expires_at
UPDATE qwt_venue_status_reports
   SET expires_at = created_at + interval '4 hours'
 WHERE expires_at IS NULL;

-- ③ 收紧约束 + 删除旧列
ALTER TABLE qwt_venue_status_reports ALTER COLUMN type SET NOT NULL;
ALTER TABLE qwt_venue_status_reports ALTER COLUMN expires_at SET NOT NULL;
ALTER TABLE qwt_venue_status_reports DROP COLUMN reason;

-- ④ 活跃判定索引（expires_at 过滤，与 venue_id 复合；created_at 索引保留供排序）
CREATE INDEX IF NOT EXISTS qwt_idx_status_reports_venue_expires
    ON qwt_venue_status_reports (venue_id, expires_at);
