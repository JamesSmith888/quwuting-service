-- ============================================================================
-- V44: 服务范围二轮（2026-08-25 晚）——包时子类别多选（新增 KTV/其他）+ 删除酒吧类别
--
-- 背景（需求：① 包时-包时场景新增 KTV、其他，且支持多选——一条包时服务可覆盖
-- 多个场景（如「酒吧、KTV包时」）；② 服务类别删除「酒吧」——酒吧不再单列类别，
-- 收编为包时子类别）：
--
-- 1. sub_category 列扩宽 varchar(20) → varchar(100)：现改为<b>逗号连接的多值枚举
--    code 串</b>（如 'BAR,KTV'），应用层 join/split 读写（V43 遗留的单值 = 单元素
--    列表，天然兼容，无需数据迁移）。
-- 2. 旧「酒吧」类别服务（category='BAR'，V43 后仅存量）→ 归入 OTHER（label「酒吧」
--    等自定义文案保留不动——类别删除只影响分类语义，不丢展示名；包时快捷计费/
--    地点等字段原样保留，admin 可按需编辑）。
-- 3. 子类别枚举新增 KTV / OTHER 为纯枚举扩展（无 CHECK 约束），无需数据迁移。
-- ============================================================================

ALTER TABLE qwt_dancer_services
    ALTER COLUMN sub_category TYPE varchar(100);

UPDATE qwt_dancer_services
    SET category = 'OTHER'
    WHERE category = 'BAR' AND deleted = false;
