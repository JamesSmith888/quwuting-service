-- ============================================================================
-- V46: 服务范围合规用词（2026-08-26）——包时→按时段、私影→影咖、线上陪聊→线上聊天
--
-- 背景（需求：微信审核灰产敏感词——「包时」「私影」「线上陪聊」均为扫黄打非/
-- 陪侍类服务黑话，服务范围类别/子类别/label 全链路脱敏）：
--
-- 1. 类别默认标签：PACKAGE 包时→按时段、ONLINE_CHAT 线上陪聊→线上聊天
--    （枚举 defaultLabel 应用层已改，无需迁移）。
-- 2. 子类别默认标签：PRIVATE_CINEMA 私影→影咖（私人影院正规业态名）。
-- 3. 存量 label 数据刷新（本迁移）——派生 label（「酒吧、KTV包时」）与 admin
--    自定义 label（如「KTV包时」「线上陪聊」）统一替换，全平台不再出现旧词；
--    全局子串替换即可（舞伴服务语境下旧词无正当用法，不误伤）。
-- 4. venue 标签同步脱敏：tags 数组文本中的「可包时」→「可按时段」（13-governance
--    禁止灰色联想标签，seed 与生产存量一并修）。
-- 5. 服务地点范围快捷文案统一：「5KM左右」→「附近5KM」（产品文案优化，非合规；
--    精确匹配快捷值，不误伤自定义文案）。
-- ============================================================================

-- 1. dancer 服务 label：包时→按时段 / 私影→影咖 / 线上陪聊→线上聊天
UPDATE qwt_dancer_services
    SET label = REPLACE(REPLACE(REPLACE(label, '包时', '按时段'), '私影', '影咖'), '线上陪聊', '线上聊天')
    WHERE deleted = false
      AND (label LIKE '%包时%' OR label LIKE '%私影%' OR label LIKE '%线上陪聊%');

-- 2. venue 标签「可包时」→「可按时段」（JSON 数组文本子串替换）
UPDATE qwt_venues
    SET tags = REPLACE(tags, '可包时', '可按时段')
    WHERE deleted = false AND tags LIKE '%可包时%';

-- 3. 服务地点范围快捷文案统一（2026-08-26 产品文案优化）：「5KM左右」→「附近5KM」
--    （「附近」强调服务意愿半径，比约数「左右」更贴「地点范围」语义；精确匹配快捷值，
--    不误伤 admin 自定义地点文案，如「本区舞厅」）
UPDATE qwt_dancer_services
    SET location_scope = '附近5KM'
    WHERE deleted = false AND location_scope = '5KM左右';
UPDATE qwt_dancer_services
    SET location_scope = '附近10KM'
    WHERE deleted = false AND location_scope = '10KM左右';
UPDATE qwt_dancer_services
    SET location_scope = '附近20KM'
    WHERE deleted = false AND location_scope = '20KM左右';
