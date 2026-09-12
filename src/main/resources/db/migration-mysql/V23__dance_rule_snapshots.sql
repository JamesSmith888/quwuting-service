-- ============================================================================
-- V23: 计价规则快照上云（2026-09-13，quwuting 仓 docs/agents/43-dance-timer.md §48）
--
-- 根因：计价规则（规则列表 + 当前选用）此前只落单设备 wx.storage（易失层），
-- 用户「清空小程序缓存 / 换机 / 卸载重装」后本地证据全失，ensurePresets 重新
-- seed 出厂集、getDefaultRule 兼容阶梯回退预置位——用户报障「清缓存后选择变回
-- 4 分 20 元」。本地视角无法区分「全新设备」与「丢了配置的老设备」，必须由
-- 第二事实源消歧。与消费账本（V16，本目录）同一持久性架构：本地为源、云端为镜。
--
-- 设计要点：
-- ① 快照模型：每用户一行（user_id 唯一），snapshot_json 承载整份规则配置
--    （{ version, rules[], selectedRuleId, presetTombstones[] }，客户端生成）。
--    配置是低频小数据（< 10 条规则），条目级增量（游标/幂等键）的复杂度远超
--    收益——写 = 幂等整体覆盖（POST，全仓 HTTP 语义只允许 GET/POST），读 = 一次拉回（GET），冲突由客户端
--    「服务端 updated_at 水位」判新旧。
-- ② snapshot_json 是不透明配置 blob：服务端只校验非空与大小上限（≤ 32KB），
--    结构校验归客户端（读写同侧）——服务端强 schema 会把客户端演化绑死在
--    服务端版本上（与 spend 逐条归一化不同：那是条目级统计口径，必须服务端
--    收敛；这是整体快照，损坏面 = 单用户单份，客户端写前读后自校即可）。
-- ③ 墓碑随快照走（presetTombstones）：用户删过的出厂预置是持久意图，恢复时
--    若不带墓碑，声明式预置对齐会把它们补回来——复现 43 §12「规则自己多了」。
-- ④ updated_at 兼作同步水位（服务端时钟，客户端比较双方都用它，规避设备
--    时钟偏移）；由 BaseEntity 维护，PUT 幂等覆盖即自然推进。
-- ⑤ 无外键（全库约定）；user_id 语义引用由登录态保证（接口恒 UserContext.requireAuth，
--    禁客户端传入——与 spend 同一隐私边界：数据用户自己可见）。
-- ============================================================================

CREATE TABLE qwt_dance_rule_snapshots (
    id bigint NOT NULL AUTO_INCREMENT,
    created_at datetime(6),
    updated_at datetime(6),
    deleted tinyint(1) NOT NULL,
    user_id bigint NOT NULL,
    snapshot_json mediumtext NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT qwt_uk_dance_rule_snapshot_user UNIQUE (user_id)
);
