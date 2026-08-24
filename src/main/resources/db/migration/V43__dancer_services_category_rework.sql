-- ============================================================================
-- V43: 舞伴服务范围改版（2026-08-24 晚）——类别重构 + 包时子类别 + 合规用词
--
-- 背景（需求：服务范围优化——① 用词全部修改避免敏感词、降低小程序审核风险；
-- ② 服务类别新增「舞厅跳舞/酒吧/线上陪聊」并改为平铺快捷选择；③「包时」升级
-- 为大类，子选项 = 酒吧/舞厅/私影；④ 计费方式简化为快捷按钮 200/300/400/自定义；
-- ⑤ 地点范围按舞伴所在城市快捷选择 5KM/10KM/20KM/手动录入）：
--
-- 1. 类别枚举重定义（qwt_dancer_services.category，枚举列无 CHECK 约束，
--    应用层防御——旧值在本迁移内完成数据映射）：
--      PACKAGE     包时（大类，必带子类别 sub_category：酒吧/舞厅/私影）
--      DANCE       舞厅跳舞
--      BAR         酒吧
--      ONLINE_CHAT 线上陪聊（原 ONLINE）
--      OTHER       其他
-- 2. 新增 sub_category 列：仅 PACKAGE 有意义（BAR/DANCE_HALL/PRIVATE_CINEMA），
--    其余类别恒空——label 短标签仍为消息拼接唯一文案源（admin 可自定义，
--    如「酒吧包时」默认名 = 子类别名 + 包时）。
-- 3. 旧数据映射（保证线上已录入服务平滑迁移，label 保留自定义文案）：
--      DANCE_HALL(舞厅包时)   → PACKAGE + sub DANCE_HALL
--      PRIVATE_CINEMA(私影包时) → PACKAGE + sub PRIVATE_CINEMA
--      KTV(KTV包时)           → OTHER（KTV 不再单列类别，label「KTV包时」保留）
--      ONLINE(线上服务)       → ONLINE_CHAT（label 为旧默认「线上服务」时跟随改
--                                为「线上陪聊」，唯一索引冲突防御）
--      OTHER(其他场景)        → OTHER（原样保留）
-- ============================================================================

-- ── 包时子类别列（仅 PACKAGE 有意义；BAR/DANCE_HALL/PRIVATE_CINEMA） ────────
ALTER TABLE qwt_dancer_services
    ADD COLUMN sub_category varchar(20);

-- ── 旧类别数据映射（先映射类别，再按需修订默认 label） ───────────────────────
UPDATE qwt_dancer_services
    SET category = 'PACKAGE', sub_category = 'DANCE_HALL'
    WHERE category = 'DANCE_HALL' AND deleted = false;
UPDATE qwt_dancer_services
    SET category = 'PACKAGE', sub_category = 'PRIVATE_CINEMA'
    WHERE category = 'PRIVATE_CINEMA' AND deleted = false;
UPDATE qwt_dancer_services
    SET category = 'OTHER'
    WHERE category = 'KTV' AND deleted = false;
UPDATE qwt_dancer_services
    SET category = 'ONLINE_CHAT'
    WHERE category = 'ONLINE' AND deleted = false;

-- 线上服务默认 label「线上服务」→「线上陪聊」（仅旧默认文案，自定义 label 不动；
-- 同舞伴下唯一索引冲突防御——冲突行保持旧 label 由 admin 手动修订）
UPDATE qwt_dancer_services
    SET label = '线上陪聊'
    WHERE category = 'ONLINE_CHAT' AND label = '线上服务' AND deleted = false
      AND NOT EXISTS (
          SELECT 1 FROM qwt_dancer_services x
          WHERE x.dancer_id = qwt_dancer_services.dancer_id
            AND x.label = '线上陪聊' AND x.deleted = false
            AND x.id <> qwt_dancer_services.id
      );
