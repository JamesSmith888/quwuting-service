-- ============================================================================
-- V38（MySQL 轨道 = db/migration-mysql）: 门店到访指标物化汇总表
--      （2026-10-06，后端权威文档 = docs/agents/52-venue-presence.md §「到访进排序」；
--       口径分析 = 工作区 quwuting-venue-visit-ranking-analysis-2026-10-06.md）
--
-- ⚠️ 编号说明：PG 遗留轨道（db/migration）与 MySQL 轨道编号各自独立、勿混用。
--
-- ── 为什么需要这张表（不是优化，是可行性前提） ───────────────────────────────
--
-- 需求：把「到店足迹」接入热度公式（列表排序 + 热门判定 + 热度页）。
--
-- 根因：到访人数是**派生量**，不是任何一列：
--     到访人数 = f( ping(距离, 精度) × 同址组几何 ≤50m × 双方营业状态 × 用户并集 )
--   其中「同址组归因」（SHARED 共享 / ABSORBED 并入 / YIELDED 让渡）是
--   VenuePresenceService 的 **Java 侧**计算，而 JPQL **无 FROM 派生表能力**，
--   无法在 HEAT_BEHAVIOR 内表达「按用户分组后求并集、再按营业状态归属」。
--
--   ⛔ 禁止的替代做法：在公式里直接写「距离 ≤150m 的 COUNT(DISTINCT user_id)」标量子查询。
--      那会**绕过归因**——同楼竞品各自吃掉一份整楼人流（比同等人流的独栋店多一倍），
--      且丢掉「停业店证据让渡给在营店」的消歧结果（52 号 §1.1 第 1 条明文禁止）。
--
--   ⇒ 唯一合规路径 = 定时任务按**唯一归因实现**（复用 VenuePresenceService）刷新本表，
--      公式只做 (venue_id) 主键点查的标量子查询。
--
-- ── 口径（三件事，缺一都不是排序口径） ─────────────────────────────────────
--   ① **排除内部账号**：集合 = 运营配置 heat.excluded.user.ids ∪ 哨兵（HeatAccountExclusionService）。
--      到访是**低基数**信号（2026-10-06 现网 51 条 ping 中 user 2/ADMIN 一人占 26 条 = 51%），
--      内部账号在本项的占比远高于它在收藏/反馈里的占比 ⇒ 不排除等于平台自己人直接刷分。
--      注意：admin 展示口径**不排除**（展示回答"发生了什么"，公式回答"有多火"，口径分叉有意）。
--   ② **分摊口径**（而非共享）：同址组内 ≥2 家门店都在营时，每位用户按 1/k 分给 k 家在营店。
--      共享计数（每家都记满）进排序 = 同楼两家各吃一份整楼人流 ⇒ 本列是**小数**（decimal(8,2)），
--      与 admin 展示的共享人数**不是同一个数**（口径分叉已登记，勿"顺手统一"）。
--   ③ **不在营门店记 0**：门店状态 ∉ {OPEN, CLOSED} 且同址无在营店 ⇒ 本店到访计入 0。
--      根因：人不可能"到店"一家停业门店。现网实证——「钜之淋音乐酒吧」(CEASED) 靠 1 条
--      命中在 W=15 时能升到西安同城第 5（2026-10-06 只读模拟）。
--      ⚠️ admin 侧**保留**这份证据（"停业店持续有到访"是门店状态的复核线索，48 号域价值），
--      回归到本表的只有「排序不允许停业店上浮」这一条。
--
-- ── 刷新与时效 ─────────────────────────────────────────────────────────────
--   · 刷新方 = VenueVisitMetricsScheduler（默认 30 分钟，fixedDelay）；
--     每轮先整表清零再逐店 upsert ⇒ 窗口外自然淘汰（不会留陈旧值）。
--   · 代价：**到访项有刷新延迟**——这是与"当日行为当天反映排序"（现网契约，
--     见 HEAT_SCORE javadoc）的**有意例外**，登记在 05 号文档。
--   · 表只保留「曾经有过到访」的门店（无到访门店不落行，公式侧 COALESCE 为 0）
--     ⇒ 行数量级 = 稀疏的到访门店数，不随门店总数增长。
--   · 时间戳由 Java 传 LocalDateTime.now()（JVM 北京时间），禁 DB now()
--     （V33/热度上报同款事故：RDS 会话时区 UTC 会让窗口比较错位）。
--
-- ── 表设计说明 ─────────────────────────────────────────────────────────────
--   · BaseEntity 四列契约（id / created_at / updated_at / deleted）齐备。
--   · UNIQUE (venue_id)：一店一行，upsert 的冲突键。
--   · visit_users_30d / visit_users_7d = **分摊后**的去重到访人数（可为小数）。
--   · group_size = 同址组规模（含本店，≥1），供 admin / 热度页同屏说明口径，不参与计算。
--   · 无外键（全库约定）；无枚举列；无 CHECK（全库约定）。
-- ============================================================================

CREATE TABLE qwt_venue_visit_metrics (
    id               bigint       NOT NULL AUTO_INCREMENT,
    created_at       datetime(6)  NOT NULL,
    updated_at       datetime(6)  NOT NULL,
    deleted          tinyint(1)   NOT NULL DEFAULT 0,
    venue_id         bigint       NOT NULL COMMENT '门店（qwt_venues.id）',
    visit_users_30d  decimal(8,2) NOT NULL DEFAULT 0 COMMENT '近30天到访人数（归因+分摊+排除内部账号；排序口径）',
    visit_users_7d   decimal(8,2) NOT NULL DEFAULT 0 COMMENT '近7天到访人数（同上；仅展示，不进公式）',
    group_size       int          NOT NULL DEFAULT 1 COMMENT '同址组规模（含本店）——口径说明用，不参与计算',
    refreshed_at     datetime(6)  NOT NULL COMMENT '本行刷新时刻（JVM 北京时间）；排序侧据此说明时效',
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_vvm_venue UNIQUE (venue_id)
) COMMENT='门店到访指标物化汇总（归因+分摊+排除内部账号）——到访进热度公式的唯一取数源';

-- 刷新任务的「陈旧行」判定与运营侧观察（按刷新时刻扫描）
CREATE INDEX qwt_idx_vvm_refreshed ON qwt_venue_visit_metrics (refreshed_at);
