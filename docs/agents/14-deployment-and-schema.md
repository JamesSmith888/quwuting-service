# 配置、部署与 Schema 演进

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 配置管理

```
src/main/resources/
  application.yaml          ← 公共配置（不含敏感信息，所有 profile 共享；敏感键为无空默认占位哨兵）
  application-dev.yaml      ← 本地开发（不提交 git，已加入 .gitignore，含本地敏感值）
  application-mysql.yaml    ← 本地 MySQL 迁移验证（不提交 git，含本地敏感值）
  application-prod.yaml     ← 生产（已提交；敏感键为 ${VAR} 无空默认占位，由外部配置覆盖）
${APP_DIR}/config/
  application-prod.yaml     ← 生产敏感值（服务器本地维护，gitignored；Spring Boot 外部化配置，
                              优先级高于 classpath 同名文件；模板 deploy/config-prod.example.yaml）
```

- 基础 `application.yaml` 的 `ddl-auto` 为 `validate`（2026-08-07 起 Flyway 迁移 + validate 校验策略，dev/prod 一致）——schema 变更由 `db/migration/V{n}` 脚本版本化执行，Hibernate 启动时只校验实体与表结构一致；规则见「Schema 演进与数据库完整性」章节
- 禁止在任何 yaml 文件中硬编码数据库密码、密钥等敏感信息；**敏感值一律放 gitignored yml**：
  本地 = classpath `application-dev.yaml` / `application-mysql.yaml`；生产 = 服务器
  `${APP_DIR}/config/application-prod.yaml`（**2026-08-31 起弃用环境变量注入**，
  systemd unit 不再挂 EnvironmentFile，deploy.sh 检查外部配置存在）。
- 环境变量占位符禁止带空默认值（`${SECRET}` 而非 `${SECRET:}`），确保遗漏配置时启动即失败
  ——外部 config 未覆盖、环境变量未注入时，占位解析失败即启动报错（哨兵）。

### 生产部署

基础设施：阿里云 ECS（内嵌 Tomcat，`java -jar` 方式运行）+ nginx 反代（`https://api.starseek.online:443` → `127.0.0.1:8080`）。Cloudflare Tunnel 已于 2026-08-13 移除（用户删除 CF 侧 hostname 配置，DNS A 记录直连 ECS，服务器 cloudflared 已停用）。

**强制约束（2026-07-25 OOM 事故后确立）**：

- **禁止在生产环境使用 `mvn spring-boot:run`**。该命令会同时运行 Maven JVM + fork 应用 JVM，双倍内存开销且无进程守护。生产唯一启动方式为 systemd 管理的 `java -jar`。
- **JVM 必须配置内存上限**。由 `deploy/deploy.sh` 在 ExecStart 中硬编码 `-Xmx` / `-XX:MaxMetaspaceSize` / `-XX:+ExitOnOutOfMemoryError`，禁止使用无约束的默认值。
- **必须通过 systemd 管理进程**（自动重启、资源硬限制、日志归集）。systemd unit 由 `deploy/deploy.sh` 自动探测 JAVA_HOME 后内联生成（避免硬编码 `/usr/bin/java` 导致 status=203/EXEC）。
- **生产敏感值放外部 config yml，不用环境变量**（2026-08-31 起）：服务器维护
  `${APP_DIR}/config/application-prod.yaml`（gitignored，Spring Boot 外部化加载，
  优先级高于 classpath），模板 `deploy/config-prod.example.yaml`；systemd 不注入敏感变量。

**部署流程（一键脚本）**：

```bash
# 首次部署（服务器上执行）
# 1. 确保仓库已 clone 到 /root/quwuting-service
git clone https://github.com/JamesSmith888/quwuting-service.git /root/quwuting-service

# 2. 创建外部敏感配置（不进 git）：复制模板并填真实值（DB/微信/JWT/Supabase）
cp deploy/config-prod.example.yaml /root/quwuting-service/config/application-prod.yaml
vi /root/quwuting-service/config/application-prod.yaml

# 3. 一键部署（打包 + 创建用户 + 写 systemd unit + 启动）
sudo bash deploy/deploy.sh

# 后续升级（服务器上执行）
git pull
sudo bash deploy/deploy.sh --no-user --no-unit   # 仅重新打包+重启
```

脚本支持 `--no-user`（跳过用户创建）、`--no-unit`（跳过 unit 重写）、`--no-package`（跳过 Maven 打包）。默认使用 `prod` profile（2026-08-30 起，MySQL 生产；可通过 `SPRING_PROFILE` 环境变量覆盖）。

**deploy.sh 关键机制说明**：

| 机制 | 实现 | 作用 |
|------|------|------|
| JAVA_HOME 自动探测 | `readlink -f $(which java)` | 避免硬编码 `/usr/bin/java`（tarball JDK 无此路径，导致 status=203/EXEC） |
| 专用系统用户 | `appuser`（nologin shell） | 进程隔离，最小权限运行 |
| JVM 参数硬编码 | ExecStart 中 `-Xmx512m -Xms256m -XX:MaxMetaspaceSize=192m` | 限制堆大小，防止 OOM Kill |
| `-XX:+ExitOnOutOfMemoryError` | JVM flag | JVM 内 OOM 立刻退出，让 systemd 拉起而非僵死 |
| `-XX:+HeapDumpOnOutOfMemoryError` | JVM flag | 留 hprof 到 `/var/log/quwuting-service/`，事后分析 |
| `Restart=always` + `RestartSec=10s` | systemd 守护 | 异常退出后 10s 自愈 |
| `StartLimitBurst=5` / `StartLimitIntervalSec=120` | 2 分钟内最多重启 5 次 | 防止配置错误导致无限重启循环 |
| `MemoryMax=950M` / `MemoryHigh=800M` | cgroup 硬/软限制 | JVM 逃逸时兜底（仍 < 物理 2GB 一半），OS 不被拖死 |

**HikariCP 连接池**：统一在 `application.yaml` 基础配置声明（2026-08-10 起，禁止在环境 yaml 重复——防漂移）：maximumPoolSize=5, minimumIdle=2, idle-timeout=5min, max-lifetime=15min, connection-timeout=10s, leak-detection-threshold=30s, **keepalive-time=60s + validation-timeout=3s**。低流量小程序 + 高延迟 DB（~150ms/往返）场景下 5 连接足够，减少内存占用；keepalive 探活是"数据库抖动不死连接"的关键（见「连接池与数据库抖动韧性」）。

nginx 站点配置：`/etc/nginx/conf.d/api.starseek.online.conf`（443 ssl，证书 `/etc/nginx/ssl/api.starseek.online.pem`（DigiCert DV），反代 `127.0.0.1:8080`；80 端口 301 跳 https）。

### Supabase 连接池兼容性（强制）

Supabase 提供三类接入点，JDBC 配置必须与池化模式匹配，否则运行期报 `prepared statement "S_N" already exists`（SQLState 42P05）：

| 接入点 | 端口 | 模式 | JDBC 要求 |
|--------|------|------|-----------|
| `aws-1-<region>.pooler.supabase.com` | 6543 | 事务池化 | URL **必须**附加 `prepareThreshold=0` + `connectTimeout=5&socketTimeout=8&tcpKeepAlive=true`（2026-08-10 起，见「连接池与数据库抖动韧性」） |
| `aws-1-<region>.pooler.supabase.com` | 5432 | 会话池化 | 无特殊要求 |
| `db.<project-ref>.supabase.co` | 5432 | 直连 | 无特殊要求，用户名用 `postgres` |

根因：PG JDBC 驱动默认启用服务端命名预编译语句（`prepareThreshold=5`，同一 SQL 执行 5 次后提升为命名语句 S_1/S_2…），而事务池化会在事务之间更换物理后端连接，命名语句的命名空间挂在物理后端上，多路复用必然冲突。`prepareThreshold=0` 禁用服务端命名预编译，是事务池化环境下的标准解法。

新增/修改数据源配置时（含生产 `${DB_URL}`），若 URL 指向 6543 端口，必须检查 `prepareThreshold=0` 与三项超时/保活参数（`connectTimeout`/`socketTimeout`/`tcpKeepAlive`）是否存在——缺任一项即回归 2026-08-10 事故（见下节）。

---


---
## 连接池与数据库抖动韧性（2026-08-10 事故根因修复）

### 事故还原（2026-08-10 22:57，本地 dev 实例实证）

Supabase 事务池化（6543）抖动（用户确认：Supabase 当前不稳定为已知外部条件）→ 请求中途连接被对端关闭/网络中断（`SQLState 08006` / `EOFException`）→ 但应用表现出一串可预防的放大缺陷：

1. **无 socket 超时**：JDBC URL 仅有 `sslmode=require&prepareThreshold=0`，驱动阻塞在 TCP 重传上 **19s 才报错**（远超前端 10s 超时），请求挂死（日志 `GET /points/me -> 200 cost=19187ms [SLOW]`）。
2. **keepalive 关闭**：连接池无主动探活，死连接不会被提前剔除，直到被某请求拿到并失败（HikariCP 事后剔除可自愈，但用户已感知错误）。
3. **生产调优从未生效**：systemd 以 `--spring.profiles.active=dev` 运行，生效的是 `application-dev.yaml`（无任何 HikariCP 配置 → Spring Boot 默认值：keepalive 关闭、max-lifetime 30min、connection-timeout 30s）；`application-prod.yaml` 里的调优（5 连接/15min/10s/leak 检测）从未生效。
4. **错误被伪装成 200**：GlobalExceptionHandler 兜底异常未设置 HTTP 状态，服务器错误以 `HTTP 200 + code 5000` 返回——监控/代理不可见，前端 GET 重试无从触发。

### 修复（五层，缺一不可）

| 层 | 改动 | 效果 |
|----|------|------|
| JDBC URL | `connectTimeout=5&socketTimeout=8&tcpKeepAlive=true`（dev/prod 双配置） | 网络故障 **≤8s 快速失败**（< 前端 10s 超时），不再挂死 19s |
| 连接池 | 基础配置 `application.yaml` 统一声明 hikari 块：`keepalive-time: 60000` + `validation-timeout: 3000` + 5/2/5min/15min/10s/leak30s | 每 60s 对空闲连接执行 `isValid` 探活（PG JDBC 42.7.11 的 isValid 是真实往返，已验证），死连接**借出前即被剔除**，socket 不被池化器/NAT 空闲回收 |
| 异常响应 | `DataAccessResourceFailureException` → **HTTP 503 + code 5003**「服务暂时不可用，请稍后重试」；兜底 Exception → **HTTP 500**（不再 200） | 服务器错误语义正确；前端 5xx 重试可触发 |
| 前端请求层 | 幂等 GET 遇到 HTTP 5xx 自动重试 1 次（300ms，与网络层失败同预算） | 数据库抖动瞬时故障**用户无感自愈**；POST 等非幂等不重试 |
| 部署 | 生产已切 prod profile + RDS MySQL（2026-08-30 完成）；敏感值放服务器 `config/application-prod.yaml`（2026-08-31 起，外部化配置） | 消除"生产跑 dev 配置"隐患（show-sql 开启、密钥在 jar 内、logging 收敛未生效） |

### 强制约定

- **HikariCP 调优禁止在各环境 yaml 重复声明**——唯一事实源是 `application.yaml` 基础配置（dev/prod 全环境生效），防漂移。
- 任何指向 6543 池化器的 JDBC URL（含 `${DB_URL}` 的值）必须含 `prepareThreshold=0&connectTimeout=5&socketTimeout=8&tcpKeepAlive=true`。
- 兜底异常必须返回 5xx（HTTP 语义），禁止再以 200 伪装服务器错误。
- 前端请求层：5xx 重试仅限幂等 GET；POST/PUT 等禁止。
- 生产已运行 prod profile + RDS MySQL（2026-08-30 切换完成，见上表"部署"行）；敏感配置统一 gitignored yml（本地 application-dev/mysql.yaml、生产服务器 config/application-prod.yaml），**禁止新增环境变量注入敏感值**。

---


---
## Schema 演进与数据库完整性（2026-08-07 起：Flyway 显式迁移 + validate）

### Schema 演进策略（Flyway 版本化迁移，Hibernate 只校验）

**核心决策（2026-08-07）**：schema 演进由 **Flyway 显式版本化迁移**管理（`classpath:db/migration/V{n}__描述.sql`，应用启动时自动按序执行），Hibernate `ddl-auto` 从 `update` 改为 **`validate`**（启动时校验实体映射与实际表结构一致，不一致即拒绝启动，fail-fast）。**废止 2026-08-05 确立的 `ddl-auto: update` 自动演进策略**——其固有缺陷（不能删列/改约束、无版本历史/回滚、多实例并发启动 DDL 竞态、schema 变更与业务代码耦合在启动路径）是生产稳定性隐患（详见下「根因分析（2026-08-07 引入 Flyway）」）。

**2026-08-08 修复（Spring Boot 4 的 Flyway 集成 + 多应用共库）——两条硬依赖**：

1. **pom 必须使用 `spring-boot-starter-flyway`（Boot 4 拆分模块），禁止只声明 `flyway-core`**。Spring Boot 4 将 Flyway 自动配置从 `spring-boot-autoconfigure` 拆为独立模块（`spring-boot-flyway` / `spring-boot-starter-flyway`）——仅声明 flyway-core 时 Flyway 在 classpath 上但**从不执行**（无 AutoConfiguration 消费 `spring.flyway.*`），`spring.flyway.table` 等配置全部静默失效。**2026-08-08 事故**：pom 只有 flyway-core，V2/V3 之前靠手动执行生效（幸存者偏差），V4 建新表 `qwt_messages` 被跳过 → Hibernate validate 启动失败 "missing table [qwt_messages]"。
2. **`spring.flyway.table` 必须为应用专属历史表（`qwt_flyway_schema_history`）**。本数据库与**其他应用共用**（同一 Supabase 项目 postgres 库），默认表 `flyway_schema_history` 已被其他应用占用（历史最大版本远高于本应用 V4）——Flyway 读到其历史后把所有 ≤ 该版本的迁移视为已应用直接跳过（`out-of-order=false`）。**多应用共库场景：每个应用必须用独立历史表**，否则后接入的应用迁移永不执行。

**Flyway 双路径（对已有库零破坏）**：

- **已有库**（生产/开发，schema 非空）：`baseline-on-migrate: true` + `baseline-version: 1`——首次启动把 `V1__baseline_schema.sql` 标记为基线（**跳过执行**，当前库结构即基线），从 V2 起应用增量迁移。baseline 不校验存量结构，已存在表零影响。
- **全新环境**（空库）：无 baseline，从 V1 起顺序执行，一次性建成与实体映射一致的全量结构。

**新增表/列/索引/约束的唯一通道 = 新增 `V{n}` 迁移脚本**（禁止依赖 ddl-auto 自动演进；禁止在迁移脚本外手工改库）。变更流程：改实体 → 写迁移脚本（与实体声明严格一致，命名/类型/默认值/索引约束见 V1 baseline 头注释）→ 本地启动验证（观察 `DbMigrate` 日志确认迁移真实执行）→ 部署时随应用自动应用。**验证红线**：启动日志必须出现 `Migrating schema ... to version "V{n}"` 与 `Successfully applied`；迁移"配置了但从未执行"是 2026-08-08 事故的深层根因（V2/V3 靠手动执行掩盖了 Flyway 未生效）。

**三条硬规则（延续 2026-08-05 事故教训，保证迁移正确性）**：

1. **新增 NOT NULL 列必须携带默认值，唯一声明通道是 `@ColumnDefault`**——`@Column(nullable = false) + @ColumnDefault("'XXX'")`（枚举类列；`@ColumnDefault` 的值是原始 SQL 表达式，字符串要带引号如 `"'PENDING'"`）。迁移脚本中对应 `ADD COLUMN ... NOT NULL DEFAULT ...`，PostgreSQL 快速默认值不重写表，存量行自动落默认值。**禁止裸 `@Column(nullable = false)` 无默认值**（对已有数据的表加列会直接失败——这是历史 migrate-*.sql 存在的根本原因，如今规则上杜绝）。**禁止在 `columnDefinition` 中携带 DEFAULT/NOT NULL 等与 JPA 元数据重叠的语义**——Hibernate 会把元数据派生的 `default ...` / `not null` / 枚举 `check` 追加到 columnDefinition 原文之后，双声明生成非法 DDL（`... DEFAULT 'X' default 'X' ...` → Postgres "multiple default values specified"）。Java 字段初始化器只负责内存态默认值、**不参与 DDL 生成**，不能替代 @ColumnDefault。`columnDefinition` 仅限方言特有类型片段（如 `jsonb`），禁止写 DEFAULT/NOT NULL
2. **新增可空列直接加列**（`nullable = true` 或缺省），迁移无阻塞
3. **实体移除字段 ≠ 列被删除**：validate 不校验列级 NOT NULL、Flyway 迁移不自动删列。移除字段时必须保留实体映射兜底（@Deprecated 字段 + Java 默认值，insert 继续写该列避免违反遗留 NOT NULL），**禁止**只移除映射导致 insert 违反遗留 NOT NULL 列（历史 `liked` 事故模式，见下文「实体字段移除」小节）；确需删列时在迁移脚本中显式 `DROP COLUMN`（评估影响后）

**索引演进**：实体 `@Index` 声明与迁移脚本中的 `CREATE [UNIQUE] INDEX` 一一对应；新增索引走 V{n} 脚本（`IF NOT EXISTS` 防御性幂等）。

**枚举类列禁止 CHECK 约束（2026-08-10 确立，事故根因见 V10 迁移头注释）**：Flyway 管理的 schema（V1 baseline 起）一律**不声明、不维护**枚举列的 DB CHECK 约束——`@Enumerated(EnumType.STRING)` 的隐式 check 只在 Hibernate 生成 DDL 时出现（`ddl-auto:create/update` 时代产物），`ddl-auto=validate` **不校验约束表达式**，Flyway 迁移链不跟进。**扩枚举 = 改 Java 枚举即可，永远不需要 DB 迁移**；枚举值合法性由应用层（Jackson 反序列化 + 实体枚举映射）把关。Hibernate 遗留的 4 个存量约束（`qwt_venue_feedbacks_status_check` / `qwt_dancers_status_check` / `qwt_dancer_venues_relation_check` / `qwt_venue_shares_event_type_check`）已由 V10 清理；**禁止**在任何迁移脚本中新增同类 CHECK。

**DDL 失败即启动失败**：`spring.jpa.properties.hibernate.hbm2ddl.halt_on_error: true` 保留（基础配置已统一）；Flyway 迁移失败同样默认拒绝启动——双重 fail-fast。

### 根因分析（2026-08-07 引入 Flyway，为什么废止 ddl-auto:update）

**为什么当初选了 `ddl-auto: update`（2026-08-05 决策）**：status 列事故后，团队为避免"手写 SQL 与实体不一致"再次发生，决策"新表/新列一律由 update 自动完成，不手动执行 SQL"。该决策在当时解决了"手动 SQL 易错"的痛点，但引入了一组更深的隐患：

1. **update 的能力边界是"只加不减"**：不能删列、不能 MODIFY 约束（NOT NULL→可空、改类型）、不能回滚——schema 只会单向漂移，历史遗留列（`liked`/`handled`/`avatar_url`）只能靠实体映射兜底，永远无法清理，schema 与代码的偏差不可逆地累积。
2. **无版本历史与可审计性**：schema 变更不可追溯（哪次发布改了什么列无法回答），多实例并发启动时 DDL 存在竞态窗口。
3. **DDL 与业务代码耦合在启动路径**：任何新实体上线都伴随启动期自动 DDL，出问题就是"启动即改库"，没有"先迁移后发布"的发布纪律。
4. **与手动脚本并存的双轨混乱**：`db/` 目录的历史 migrate/repair 脚本与 update 并存，执行时机依赖人工（"先执行脚本再启动新版"），流程脆弱。

**为什么 Flyway 是长期方案**：版本化迁移把 schema 变更变成**代码库的一部分**（有版本、有历史、可回滚点、可审计），`baseline-on-migrate` 对存量库零侵入，`validate` 把"实体与库不一致"从运行期隐患提前到启动期 fail-fast。这正是"显式优于隐式、可审计优于自动"的生产级标准。

### 事故根因（2026-08-05：columnDefinition 与 @ColumnDefault 双声明）

**现象**：启动时 `ALTER TABLE qwt_venue_feedbacks ADD COLUMN status ...` 报 `ERROR: multiple default values specified for column "status"`，随后同批索引建失败（`column "status" does not exist`）；应用却**照常启动成功**（Hibernate 仅 WARN），反馈模块在残缺 schema 上运行，首次读写即炸。

**根因链（为什么会有这个错误决策）**：

1. **把 `columnDefinition` 当成了承载约束的常规通道**。`columnDefinition` 的语义是"原始 DDL 片段，原样拼接"，是给方言特有类型（如 `jsonb`）的逃生口；团队却用它写 DEFAULT/NOT NULL，与 JPA 元数据（`nullable`、`@ColumnDefault`、`@Enumerated` 的 check 生成）对同一约束形成**双声明**。Hibernate 组装列 DDL 时把元数据派生的 `default ...` 追加到原文之后 → `DEFAULT 'PENDING' default 'PENDING'` → Postgres 直接拒绝。
2. **约定未经真实 Hibernate 版本验证就固化为 AGENTS.md 规则**。旧规则"`columnDefinition` 携带默认值 + 枚举类列再加 `@ColumnDefault`"把两个互斥通道写成"互补"，从未在 update 的 ADD COLUMN 路径上被执行过：`handled` 列早已存在（update 不 alter 存量列），`Venue.status` 只用了 columnDefinition+初始化器（无 @ColumnDefault）恰好没踩雷——**幸存者偏差**让错误约定显得"已被使用验证过"。`status` 是本约定下第一条真正走 ADD COLUMN 路径的新列，一执行即炸。
3. **无 DDL 失败兜底**。SchemaIntegrityChecker 只校验主键机制，Hibernate 默认吞掉 DDL 错误，二者叠加使"schema 未按实体迁移"完全不可见，直到运行期。

**长期防线（本次已落地）**：① 列默认值唯一声明通道 = `@ColumnDefault`，columnDefinition 禁止携带 DEFAULT/NOT NULL（规则见上）；② `halt_on_error: true` 使 DDL 失败即启动失败；③ 本事故后全库 grep 清理了全部同模式字段（`VenueFeedback.handled`、`Venue.status`、`Venue.sortWeight`）。新增列后若担心，可临时用 `show-sql: true` 启动一次观察生成的 ADD COLUMN 是否单默认值。

### 无法避免手动 SQL 的场景（例外清单）

以下场景 **Flyway 迁移脚本无法表达或不宜入链**，允许且必须手动执行，除此之外一律走 V{n} 迁移：

| 场景 | 手段 | 说明 |
|------|------|------|
| 跨库/跨环境逻辑迁移 | `pg_dump -Fc` + `pg_restore` | 完整保留 IDENTITY/序列/主键/NOT NULL/默认值/索引/约束；**禁止 GUI 工具（DataGrip/DBeaver/Supabase 控制台）拖拽复制表结构**——系统性丢失 identity/序列/主键 |
| 主键机制损坏修复 | `db/repair-schema-identity.sql`（幂等） | 回填 NULL id、重建主键、恢复 IDENTITY/序列定位/列默认值；由 SchemaIntegrityChecker fail-fast 兜底发现 |
| 历史遗留一次性脚本 | `db/migrate-*.sql`（已执行，勿再运行） | `migrate-feedback-anonymous.sql` / `migrate-reaction-daily.sql` / `migrate-drop-liked-not-null.sql` 为 **Flyway 引入前**的手动迁移，**均已执行**，仅作历史参考，禁止重复执行（新环境由 V1 baseline 直接得到最终结构） |

**新约定**：`db/migration/` 为 schema 变更的**唯一权威通道**（V1 baseline + V{n} 增量）。`db/` 根目录仅保留 seed 脚本与历史参考脚本。任何 schema 变更（含改列约束、删列）优先写成 V{n} 迁移；确属"无法入链"的场景（如上表）才手动执行并记录到 AGENTS.md。

### 事故根因（2026-08-04 确立，历史背景）

DB 迁近区时用 DataGrip「全选表 → 拖拽到目标库」方式迁移：索引和唯一约束保留了，但 **IDENTITY（序列）、主键、NOT NULL、列默认值全部丢失**——9 张 `qwt_*` 表的 id 列全部退化为可空 bigint。损坏极具欺骗性：

- Hibernate `ddl-auto: validate` 只校验表/列存在、类型、索引、唯一键，**不校验主键/identity/NOT NULL**（见 `AbstractSchemaValidator`），启动期毫无告警
- 读/更新路径不依赖生成主键，一切正常
- 不回读主键的写入（view upsert）静默积累 id=NULL 脏行
- 首个 IDENTITY 插入才爆炸：编辑场所改状态触发状态变迁日志写入 → `AssertionFailure: null identifier`（Hibernate 经 JDBC `getGeneratedKeys()` 回读主键，pgjdbc 附加 `RETURNING *`，但 id 列无 identity，插入成功而主键为 NULL）

同一套库上**一切新增写入**（新建场所、收藏、动态、上报、评分）全部损坏，状态变更只是第一个撞上的。

### SchemaIntegrityChecker（config/SchemaIntegrityChecker.java）

启动时 Schema 完整性检查，**fail-fast**（`ApplicationRunner` 抛异常拒绝启动）：

- 基于 JPA 元模型自动枚举全部实体表（`@Table` 名 + 主键属性名），**零硬编码表名**；新增实体自动纳入检查
- 单次 `pg_catalog` 目录查询（跨洲往返昂贵，禁止逐表查询）校验每张表：主键存在且与实体主键列一致、主键列 NOT NULL、主键列具备 IDENTITY 或序列默认值
- 仅对 PostgreSQL 生效，其他数据库（测试用 H2）跳过
- 主键机制损坏时一切写入必然失败或产生脏数据——拒绝启动优于静默损坏

任何环境（本地/生产）启动服务即完成一次完整性校验。**手工变更过数据库结构后，必须启动一次服务确认检查通过**。

### identity 序列滞后（2026-08-24 事故：新增舞厅必 500）

**事故**：新增舞厅 100% 复现 `{"code":5000,"message":"服务器内部错误"}`，日志 `duplicate key value violates unique constraint "qwt_venues_pkey"`。排查发现 `qwt_venues` max(id)=1101 而 identity 序列 `qwt_venues_id_seq` last_value=95（落后 1006）——应用插入不带显式 id，identity 从 96 起分配，前 1006 个候选全部已被占用 → 必然主键冲突。

**根因**：08-08 切 Supabase 项目时用 `pg_dump -Fc + pg_restore` 恢复数据，恢复行带原始 id（显式 id 插入）→ **`GENERATED BY DEFAULT AS IDENTITY` 不推进序列**，序列停留在新库建表后零星插入到的 95。此坑**只影响写入路径**（读/更新正常）、**启动期零告警**（SchemaIntegrityChecker 只校验"主键是 identity 类型"，不校验"序列值与 max(id) 对齐"）。

**全表巡检 SQL**（任何带显式 id 的数据恢复后必跑）：

```sql
SELECT c.relname AS tbl, COALESCE(p.last_value, 0) AS seq,
       COALESCE((xpath('/row/max/text()', q.maxq))[1]::text::bigint, 0) AS max_id,
       COALESCE((xpath('/row/max/text()', q.maxq))[1]::text::bigint, 0) - COALESCE(p.last_value, 0) AS gap
FROM pg_class c
JOIN pg_sequences p ON p.schemaname = 'public' AND p.sequencename = c.relname || '_id_seq'
CROSS JOIN LATERAL (SELECT query_to_xml(format('SELECT max(id) FROM %I', c.relname), false, true, '') AS maxq) q
WHERE c.relname LIKE 'qwt_%' AND c.relkind = 'r'
ORDER BY gap DESC;
```

**修复**（数据修复非 schema 变更，无需迁移脚本，幂等可重复执行）：

```sql
SELECT setval('qwt_venues_id_seq', (SELECT max(id) FROM qwt_venues));
```

**规则**：跨库/跨项目数据恢复（pg_dump/pg_restore、DataGrip 拖拽等）完成后，**必须**执行上述巡检，所有 `gap > 0` 的表逐张 setval；gap < 0（序列超前）无害无需处理。恢复流程加入 deploy 验证清单。

### 实体字段移除 ≠ 列被删除（2026-08-05 更新为兜底映射模式）

**事故**：`TagInteraction` 在"标签点赞 → Reaction"重构中移除 `liked` 字段，javadoc 声称"liked 列已废弃并移除"，但列从未从库表删除——dev（`ddl-auto: update`）只新增列/约束、**从不删除实体已移除的列、也不把现有列改为可空**；prod（`validate`）不校验列级 NOT NULL。结果：列保持 `NOT NULL` 且无默认值，代码侧 insert 不再提供 `liked` → 任何"首次评分"插入违反 NOT NULL，且被 `score()` 的 `catch (DataIntegrityViolationException)` 误当并发竞态吞掉（事务 rollback-only 后 commit 抛 UnexpectedRollbackException，接口 200 + code=5000 表面成功实为失败），真实根因被掩盖到运行期才爆炸。

**规则（强制，2026-08-05 更新）**：

- 实体移除/弱化字段时，**默认保留字段映射兜底**：@Deprecated 标注 + Java 侧设安全默认值（如 `handled` 遗留列、`avatar_url`/`liked` 先例），保证 insert 继续写该列——**禁止**只移除映射（遗留列 NOT NULL 无默认值时 insert 必炸）
- 兜底字段的 javadoc 必须描述真实库表状态（**禁止把"意图移除"写成"已移除"**——注释必须与库表事实一致）
- 仅当冗余列影响可维护性时，才走「无法避免清单」的手动删列（一次性，不新增迁移脚本文件）
- 现有遗留列：`qwt_tag_interactions.liked`（Java 零引用，NOT NULL 已由 `db/migrate-drop-liked-not-null.sql` 取消）、`qwt_users.avatar_url`（Java 零引用，可空）、`qwt_venue_feedbacks.handled`（状态机引入前遗留，实体 @Deprecated 映射兜底）
- `catch (DataIntegrityViolationException)` **只允许吞唯一键并发竞态（SQLState 23505）**，其余完整性错误（NOT NULL/列约束/外键）必须继续抛出——统一经 `DbConstraintViolations.isUniqueViolation` 判定（2026-08-20 起全库写路径已确定性化为原子 upsert / advisory lock，该 catch 模式只存在于防御性兜底，禁止新增 catch+clear 表达幂等，见 15-governance 错误表）；吞异常必须带具体 SQLState 判定，禁止整类静默吞掉。**Reaction toggle（2026-08-08 收敛）**：`VenueReactionService.toggle` 曾整类吞掉 `DataIntegrityViolationException`（venue 存在性虽已前置校验，但外键/NOT NULL 等新错误形态会同样被静默吞成"已参与"），已改为 `DbConstraintViolations.isUniqueViolation(e)` 判定、非 23505 一律上抛

---

