-- V36：两个服务端安全护栏的运营配置（2026-10-01）。
-- ① 批量置「暂停营业」影响面熔断阈值（docs/agents/33-venue-sync-skill.md「关门方向影响面熔断」；
--    消费方 = SuspendBlastRadiusGuard）；② 快讯内容红线执行开关（见文件末段）。
--
-- 背景：关门方向是「白名单差集推断」（某城被舞讯覆盖、某店不在名单内 ⇒ 判为未营业），
-- 来源漏发一个城市的半份名单就会让整城门店在一次调用里被改成暂停营业，并给收藏者推送
-- 微信服务通知。此前服务端只限制单批 500 条，影响面护栏全在 Skill 提示词里。
--
-- 判据：某城本批暂停数 ≥ min_count 且 占该城当前营业中门店比例 > max_ratio_percent% ⇒
-- 熔断（整批拒绝 1036），调用方 dryRun 核对后在 confirmedCities 中逐城确认即可放行。
--   · min_count = 5：小样本（一城只暂停两三家）比例天然大、噪声大，不参与熔断；
--   · max_ratio_percent = 50：单城过半营业门店同日被推断关门 = 异常信号；100 = 关闭比例熔断。
-- 注意：qwt_ops_config.key 是 MySQL 保留字，INSERT 列名必须反引号（V1 baseline 第 5 条契约）。
INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at) VALUES
('venue.suspend_guard.min_count', '5', NULL, now()),
('venue.suspend_guard.max_ratio_percent', '50', NULL, now());

-- 快讯内容红线执行开关（2026-10-01，消费方 = BulletinContentPolicy；docs/agents/47-bulletins.md §1.2）：
-- 默认 'false' = 沿用 2026-09-10 用户拍板的「不引入自动敏感词拦截、发布前人工把控」；
-- 开启后快讯 create / update / agent-publish 命中红线词表即拒绝（1035）。是否开启是产品决定。
INSERT INTO qwt_ops_config (`key`, value, updated_by, updated_at) VALUES
('bulletin.content_redline.enabled', 'false', NULL, now());
