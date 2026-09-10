-- ── V19：行业快讯表态（2026-09-10，docs/agents/47-bulletins.md「六、快讯表态」） ──
-- 背景：快讯列表页由「标题列表 + 跳详情」改为 TG 频道式气泡流（全文内联），
-- 同时开放用户表态——语义 = **一人一条恒一个表情**（点新表情替换、点同款取消），
-- 与门店 Reaction「每日一票」刻意不同：快讯是一次性内容，没有"次日再来评一次"
-- 的场景，永久一票既是产品语义，也让"一人一条"能被 DB 唯一键直接承载。
--
-- 设计要点：
-- ① 唯一键 qwt_uk_br_user_bulletin(user_id, bulletin_id) 是"一人一票"的**唯一
--    不变量载体**——门店域每日一票因按 code 建约束而不得不靠应用层咨询锁串行化，
--    本域约束正好落在语义维度上，无需额外锁（并发写入靠 INSERT ... ON DUPLICATE
--    KEY UPDATE 单语句收口，见 BulletinReactionRepository#upsertReaction）。
-- ② 换票 = UPDATE 同一行的 reaction_code（不新增行），取消 = 物理删除该行——
--    与门店域"取消即物理删除"同口径，deleted 列恒 0（BaseEntity 契约列，保留备用）。
--    注意：因硬删，唯一键**不**把 deleted 纳入；将来若改软删，必须同步改键。
-- ③ 计数不缓存：快讯体量小（单条内容表态量级远低于门店），一次 IN 查询即取整页
--    表态聚合，无聚合缓存（省掉门店域那套缓存失效链路）。
-- ④ 不建外键（全库无 FK 约定）；user_id / bulletin_id 语义引用由应用层保证
--    （bulletin_id 指向 qwt_announcements 中 category='FLASH' 的条目，写入前经
--    BulletinLookupService 校验可见性）。
-- ⑤ 时间戳由 Java 侧传 LocalDateTime（全库约定，禁 DB now()）。

CREATE TABLE qwt_bulletin_reactions (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6) NOT NULL,
    updated_at datetime(6) NOT NULL,
    deleted tinyint(1) NOT NULL,
    user_id bigint NOT NULL,
    bulletin_id bigint NOT NULL,
    reaction_code varchar(30) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_br_user_bulletin UNIQUE (user_id, bulletin_id)
);

-- 列表页聚合走 (bulletin_id, deleted) 前缀（整页 IN 查询 + GROUP BY reaction_code）
CREATE INDEX qwt_idx_br_bulletin ON qwt_bulletin_reactions (bulletin_id, deleted);
