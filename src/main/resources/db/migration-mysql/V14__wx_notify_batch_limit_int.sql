-- 修正 V13 列类型：tinyint → int（2026-09-08 启动期 schema validate 抓到）
--
-- 事故：V13 将 qwt_users.wx_notify_batch_limit 建为 tinyint，而 User 实体字段是
-- Integer（Hibernate 按 Java 类型期望 INTEGER 列），本地启动（mysql profile，
-- hbm2ddl.auto=validate）直接拒绝：
--   SchemaManagementException: wrong column type in [wx_notify_batch_limit];
--   found [tinyint (Types#TINYINT)], expecting [integer (Types#INTEGER)]
-- 教训：**编译期零暴露**——native DDL 不参与 javac，实体 Java 类型与 DDL 列类型
-- 的匹配只在启动期 validate 时校验；新迁移的列类型必须与实体字段 Java 类型对齐
-- （Integer ↔ int / Long ↔ bigint / Boolean ↔ tinyint(1)）。
--
-- 为什么不直接改 V13 文件：V13 已在开发库（qwt_mysql）执行（2026-09-08 13:38
-- 用户启动触发，Flyway 已记录 checksum）——**已应用迁移的文件不可再改**（改动 =
-- validate checksum mismatch，启动失败）；故历史不可变，以 V14 追加修正。
-- 生产库尚未部署：届时 V13 → V14 连续执行，最终形态即 int，无需额外处理。
--
-- int 与 V11 available_count 口径一致（同为"数量"语义小整数）；DEFAULT 3 不变，
-- 存量值全为默认 3，类型放宽（tinyint→int）无损。

ALTER TABLE qwt_users
    MODIFY COLUMN wx_notify_batch_limit int NOT NULL DEFAULT 3 COMMENT '突发窗口内最多下发的微信通知条数（3=默认/5/0=不限）';
