-- ── V31：舞伴认可「每日一票」运营开关（2026-08-15 新增） ──────────────
-- 需求（用户）：舞伴认可 = Reaction 风格表情 chip 单票（对齐门店列表 reaction 逻辑）——
-- 默认每天只能点一枚表情（换票语义），且是否允许多选由运营在 FAB「运营配置」页即时配置。
--
-- 根因：认可模型每日唯一约束 (user_id, dancer_id, recognition_date) 天然限定
-- "一日一认可"，但"一枚表情"是应用层语义（同 reaction.daily.single 先例——
-- 配置驱动的代码路径，数据层不硬编码产品规则，见 V22 注释）。
--
-- 本迁移仅插入配置默认行（'true' = 一票制生效）；关闭后 DancerService.toggleRecognize
-- 走多选路径（每枚表情独立 toggle，今日标签清空 → 删除认可记录）。无需数据卫生：
-- 认可模型无"同日同人同店多表情"的历史数据（旧模型每次认可 0-3 标签同写一条认可）。
INSERT INTO qwt_ops_config (key, value, updated_by, updated_at)
VALUES ('dancer.recognition.daily.single', 'true', NULL, now());
