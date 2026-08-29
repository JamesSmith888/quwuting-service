-- 修正 V59 列类型：档位/修改次数字段 smallint → integer
-- 原因：实体 VenueCrowdReport 用 Integer 映射（Hibernate 期望 int4），
-- V59 误用 smallint 导致 schema validation 失败（found int2, expecting integer）。
-- 项目内无 smallint 先例（其余表均为 integer），统一对齐为 integer。
-- 注意：V59 已在部分环境应用，禁止回改 V59 文件（Flyway checksum 校验），只能追加本迁移。

ALTER TABLE qwt_venue_crowd_reports
    ALTER COLUMN female_level TYPE integer,
    ALTER COLUMN male_level TYPE integer,
    ALTER COLUMN modify_count TYPE integer;
