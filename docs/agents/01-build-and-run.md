# 构建、运行与开发测试数据

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 构建与运行

```bash
# 编译
./mvnw clean compile

# 运行测试（contextLoads 需连库，约定带 dev profile 执行，
# 同时验证 SchemaIntegrityChecker 对真实库的完整性检查）
./mvnw test -Dspring.profiles.active=dev

# 打包（跳过测试）
./mvnw clean package -DskipTests

# 本地启动（需先配置 application-dev.yaml 数据源）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

> 生产部署流程见「配置管理 → 生产部署」章节。

---


---
## 开发测试数据

`src/main/resources/db/seed-dev.sql` 提供开发环境种子数据（5 个场所、3 个用户、4 条动态、3 条收藏、6 条 Reaction），覆盖已认领 / 未认领、各场所状态、商家 / 平台动态等场景。使用方式：应用以 dev profile 启动一次（自动建表）后，在 Supabase SQL Editor 或 psql 中手动执行。脚本末尾通过 `setval` 重置 IDENTITY 序列，避免后续自增 ID 冲突。

`src/main/resources/db/repair-schema-identity.sql` 是 2026-08-04 迁移事故的**幂等修复脚本**（回填 NULL id 脏行、重建主键、恢复 IDENTITY、序列定位、恢复 NOT NULL 与默认值），也是任何手工建库/迁库后结构不达标时的标准修复入口。见「Schema 完整性与数据库迁移规范」。

---

