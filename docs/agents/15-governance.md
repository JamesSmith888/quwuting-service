# 治理（禁止操作 / AI 代理常见错误 / 验证清单）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 禁止操作

- 禁止使用 PUT、PATCH、DELETE HTTP 方法
- 禁止 Controller 直接调用 Repository
- 禁止 Entity 直接作为 API 响应体返回
- 禁止 `@Query(nativeQuery = true)` 无注释使用
- 禁止 `ddl-auto: create` / `update` 出现在生产配置（生产与 dev 统一为 `validate` + Flyway 迁移策略，见「Schema 演进与数据库完整性」章节）
- 禁止在 yaml 文件中硬编码密码、Token 等敏感信息
- 禁止在 Entity 上使用 `@Data`（会破坏 JPA equals/hashCode 契约）
- 禁止枚举用 `@Enumerated(EnumType.ORDINAL)`（数据库值依赖顺序，易出错）
- 禁止 import `com.fasterxml.jackson.databind.*` 或 `com.fasterxml.jackson.core.*`（Spring Boot 4.x 使用 Jackson 3.x `tools.jackson.*`）
- 禁止在 pom.xml 显式引入 `com.fasterxml.jackson.core:jackson-databind`（由 Spring Boot BOM 管理）
- 禁止表名/索引名省略 `qwt_` 前缀（多项目共享同一 Supabase 数据库）
- 禁止在 `@Column` 中写 MySQL 特有 `columnDefinition`（如 `tinyint`），应省略让 Hibernate 按方言映射
- 禁止在 `@Column` 的 `columnDefinition` 中携带 DEFAULT / NOT NULL（与 `@ColumnDefault` / `nullable` 双声明同一约束，Hibernate 拼接生成非法 DDL，Postgres 报 "multiple default values specified"）——列默认值一律用 `@ColumnDefault` 单一通道声明（见「Schema 演进」章节）
- 禁止在用户 API 响应中引入头像等社交属性字段（产品为黄页工具，UserInfoResponse 仅含 id/openId/nickname/role）
- 禁止在 Venue 模型中使用"人均消费"（price）/"最低消费"（minConsumption）字段——舞厅领域无此概念，消费模型为 tickets（门票规则）+ partnerFees（舞伴费用多模式列表，unit 区分 MINUTE/SONG）
- 禁止添加 CORS 配置（`addCorsMappings`）——唯一客户端为微信小程序（`wx.request` 不受同源策略约束），CORS 仅降低防御深度
- 禁止在生产环境使用 `mvn spring-boot:run`（双 JVM 内存翻倍 + 无进程守护，2026-07-25 OOM 事故根因）
- 禁止生产 `java -jar` 启动时省略 JVM 内存参数（JVM flags 由 `deploy/deploy.sh` 硬编码在 ExecStart 中统一管理，至少含 `-Xmx`）
- 禁止单行多列聚合查询以 `Object[]` 为返回类型（Spring Data JPA 4.x 语义变更致 ClassCastException），必须使用接口投影
- 禁止把用户个人交互状态（"我是否已赞"、"我的评分"等）和场所级公共聚合数据放进同一个缓存 key——个人状态必须实时查询，缓存只允许存放与请求者身份无关的聚合结果
- 禁止在同一个 Service 类内部通过 `this` 调用被 `@Cacheable`/`@CacheEvict` 标注的方法——自调用会绕开 Spring AOP 代理，缓存注解静默失效；被缓存的方法必须拆到另一个 Bean 中
- 禁止列表页关联统计（如标签点赞数）按场所逐条查询——批量查询整页涉及的 ID（`IN (...)`），避免 N+1
- 禁止原生查询/JPQL 投影接口的 DATE/TIMESTAMP 列 getter 声明为 `java.sql.Date`/`java.sql.Timestamp`——Hibernate 6+ 默认映射为 `java.time.LocalDate`/`LocalDateTime`，类型不符会在运行时抛 `UnsupportedOperationException`（见「投影接口 getter 类型」章节）
- 禁止「近 N 天」滚动窗口统计只传 `since` 不传 `until` 上界——必须显式传排他上界；`VenueHeatService` 热度统计为**实时口径**（`until = 请求时刻 now`，2026-08-13 起，见「统计口径：实时」章节），列表排序/热门标记的 SQL 镜像保持**截至昨日**（`until = 今天 0 点`，稳定比较基准）——两个消费方各自锚定，禁止混用
- 禁止用户状态上报修改 `Venue.status` 字段——上报是独立信号层，`Venue.status` 变更权属管理员/认领人（见「场所状态上报」章节）
- 禁止在 `ActiveReportSummary`（公开响应）中返回 `note` 字段——note 仅管理端可见，审核安全要求（见「场所状态上报 → 审核安全」）
- 禁止降低 `RequestTimingFilter` 的优先级或在其中加入业务逻辑——必须保持 `HIGHEST_PRECEDENCE` 且纯观测，否则计时漏掉前置处理或引入额外延迟（见「请求耗时日志」章节）
- 禁止在拦截器中对每个请求发起无缓存的 DB 查询——跨洲往返 300~700ms × 每请求 = 接口延迟翻倍。拦截器中的高频查找必须使用本地缓存（Caffeine，见 `AuthInterceptor` 用户缓存模式）
- 禁止在同一方法上堆叠多个 `@CacheEvict` 注解——Spring Boot 4.x 中 `@CacheEvict` 不可重复（`@Repeatable`），编译报错。必须用 `@Caching(evict = { ... })` 包装（见「查询性能优化 → @CacheEvict 不可重复」）
- 禁止在 fire-and-forget 端点（如 `POST /venues/{id}/view`）中做冗余的场所存在性检查——由调用方（详情页 `GET /venues/{id}`）已校验，fire-and-forget 端点的 DB 查询是纯延迟负担
- 禁止对有唯一约束的幂等写入用 check-then-act（先 SELECT 存在性再 INSERT + catch 冲突）——一律 `INSERT ... ON CONFLICT DO NOTHING` upsert，单次往返且无并发窗口（见 `VenueViewRepository.upsertView`）
- 禁止跨表的多个单值聚合各发独立查询——收敛为一条标量子查询 mega-query（见 `VenueRepository.countHeatCounters`）；接口延迟 ≈ 串行往返 × 300~500ms，往返数是第一设计约束
- 禁止用 `@CacheEvict` 失效 venueHeat / tagStats——二者是服务内嵌 LoadingCache（非 Spring 托管），必须显式调用属主服务 `invalidate(venueId)`；漏掉任何一条写路径的逐出都会造成统计滞后（见「查询性能优化 → 写路径缓存逐出」矩阵）
- 禁止 `@Cacheable` 省略 `sync = true`——非 sync 的并发冷请求会重复回源（thundering herd），且无法获得单飞语义
- 禁止内嵌 LoadingCache 的 loader 方法挂 `@Transactional`/`@Cacheable` 等 AOP 注解——loader 经 `this::` 引用直接调用，绕开代理静默失效；loader 内的查询各自走隐式只读事务（见 `VenueHeatService.computeHeat`）
- 禁止用 GUI 工具（DataGrip/DBeaver/Supabase 控制台）拖拽或导出-导入方式复制表结构迁移数据库——此类工具按普通列类型重建表，系统性丢失 IDENTITY/序列/主键/NOT NULL，且 Hibernate validate 无法发现（见「Schema 完整性与数据库迁移规范」）；逻辑迁移一律 `pg_dump -Fc` + `pg_restore`
- 禁止绕过或降级 `SchemaIntegrityChecker`（如改为仅告警、加开关跳过）——主键机制损坏时一切写入必然失败或产生 id=NULL 脏数据，fail-fast 是唯一正确语义（见「Schema 完整性与数据库迁移规范」）
- 禁止手工建库/迁库后不启动服务验证——启动即触发 Schema 完整性检查，是手工变更结构后的强制验收步骤
- 禁止 Reaction 字典使用二元对立的点赞/倒赞图标（如 👍👎）——采用具体、中性的正负向 Reaction 共存，避免攻击性评价引发商家纠纷（见「Reaction 快速反馈系统」章节）
- 禁止允许用户自由创建 Reaction 代码——字典由后端 `ReactionCode` 枚举唯一维护，新增/调整条目需过审核安全过滤（参照 `ReportReason` 命名规避敏感词的先例）
- 禁止对 Reaction 做周期性清零（如"每周/每月重置计数"）——采用永久保留原始记录 + 多时间窗口（今日/7天/30天/全部）实时统计的时间衰减方案，见「Reaction 快速反馈系统 → 时效性设计」根因说明
- 禁止 Reaction 的四个时间窗口套用热度模块「统计口径：截至昨日」的排他上界约定——Reaction 是实时众包信号，窗口锚点为真实"此刻"，与热度滚动窗口是两套独立的时间语义
- 禁止在 JPQL/HQL（`@Query` 非 native）中写数据库表名或 snake_case 列名——根实体必须用实体名、列必须用 Java 属性名（camelCase），否则启动期 `UnknownEntityException`；nativeQuery 不受此限（见「双查询拆分 → JPQL 共享片段的 HQL 语法约束」）
- 禁止在 JPQL/HQL 中写裸整数时间量减法（`CURRENT_DATE - 30`）——必须带单位后缀 `CURRENT_DATE - 30 day`，否则 Hibernate 7 启动期抛 `SemanticException: ... not a temporal amount`（见「双查询拆分 → JPQL 共享片段的 HQL 语法约束」）

---


---
## AI 代理常见错误

| 错误 | 正确做法 |
|------|----------|
| 用 PUT/DELETE 定义接口 | 一律改为 POST，路径加语义动词 `/disable` `/update` |
| Controller 直接 `return entity` | Service 转换为 DTO，Controller 包装 `ApiResponse.ok(dto)` |
| Repository 用 nativeQuery | 改写为 JPQL `@Query` |
| Entity 加 `@Data` | 改为 `@Getter` + `@Setter`，手写或不写 equals/hashCode |
| DTO 用 Lombok `@Data` | 改用 Java record |
| 密码写入 yaml | 改为 `${ENV_VAR}` 占位符 |
| `ddl-auto: update` 出现在 prod | 必须是 `validate` 或 `none` |
| Service 抛出 checked exception | 统一抛 `BusinessException`（RuntimeException 子类） |
| import `com.fasterxml.jackson.databind.*` | 改为 `tools.jackson.databind.*`（Jackson 3.x），注解除外 |
| 表名/索引名不加 `qwt_` 前缀 | 共享数据库必须加前缀：`qwt_venues`、`qwt_idx_city` |
| Entity `columnDefinition` 写 MySQL 语法 | 不写 `columnDefinition`，让 Hibernate 按方言自动映射 |
| 给用户 API 加头像 / 社交属性字段 | 产品为黄页工具非社交；UserInfoResponse 仅含 id/openId/nickname/role；storage 模块仅服务场所图片，不涉及用户头像 |
| Venue 消费字段用 `price`（人均）/ `minConsumption`（低消） | 舞厅领域无此概念；用 `tickets`（门票规则 JSON 列表）+ `partnerFees`（舞伴费用多模式 JSON 列表，unit 区分 MINUTE/SONG），共享 DTO record 在 `venue/dto/` |
| 在登录链路获取 / 要求前端上送昵称 | 微信 jscode2session 不返回资料；昵称经 `POST /user/profile` 由用户主动提交，角色等变更经 `GET /user/me` 静默同步 |
| 原生查询投影接口 DATE/TIMESTAMP 列声明 `java.sql.Date`/`java.sql.Timestamp` | 改用 `java.time.LocalDate`/`LocalDateTime`——Hibernate 6+ 默认映射为 java.time 类型，声明遗留类型会在首次命中该查询时运行时报错 |
| 「近 N 天」统计只传 `since` 让窗口自然到"现在" | 同时传 `since` + `until` 排他上界：`VenueHeatService` 热度统计用实时 `until = now`（2026-08-13 起，含今日）；列表排序/热门标记 SQL 镜像用 `until = 今天 0 点`（截至昨日，稳定比较基准） |
| JPQL 数学函数传可空坐标参数（`radians(:latitude)` + null） | PG 将 null 参数推断为 bytea 直接报错；拆成带坐标 / 无坐标两个查询，Service 分流，坐标形参用原生 `double` |
| 城市筛选用 LIKE 模糊匹配"兼容"非标准名 | 精确匹配 + 写入端统一 region picker 标准名；脏数据走一次性清洗 SQL，不在查询端容错 |
| 单行多列聚合查询返回 `Object[]` 再下标强转 | 用 Repository 嵌套接口投影（getter 名 = SELECT alias），编译期类型安全，不受 Spring Data JPA 版本语义变更影响 |
| 把 likedByMe/myScore 等个人状态塞进以 `{venueId, userId}` 为 key 的聚合缓存 | 聚合数据（venueId 为 key）与个人状态（永远实时查询）彻底分离，写操作对聚合缓存做 `@CacheEvict`，不要只依赖 TTL |
| 在同一 Service 类内 `this.xxx()` 调用本类的 `@Cacheable` 方法 | 绕开 AOP 代理导致缓存静默失效；把被缓存的方法拆到独立 Bean（如 `TagAggregateStatsService`），从外部注入调用 |
| 列表页展示的关联统计按 venueId 循环单独查询 | 收集整页 venueId 后一次 `IN (...)` 批量查询（如 `VenueReactionService.batchGetBadges`） |
| 用 venuefeedback 做实时状态上报（误用异步审核做实时信号） | venuefeedback = 异步管理员审核流程；venuestatusreport = 实时 4h TTL 众包信号。两者共存，不可混用（见「场所状态上报」章节） |
| JPQL 子查询根实体写数据库表名（`FROM qwt_venue_views`）/ 列写 snake_case | HQL 必须用实体名 + camelCase 属性名（`FROM VenueView vv ... vv.venueId`），启动期查询校验抛 `UnknownEntityException`；nativeQuery 才用表名 |
| HQL 时间量减法写 `CURRENT_DATE - 30` | 带单位后缀 `CURRENT_DATE - 30 day`（Hibernate 7 裸整数报 `SemanticException: not a temporal amount`）；PostgreSQL native SQL 的日期减整数不受影响 |
| 用户上报后修改 Venue.status | 用户上报是独立信号层，不改 Venue.status；管理员后续可决定是否据此手动更新（见「独立信号层」章节） |
| 拦截器中对每个请求 `findById` 查库（无缓存） | 用 Caffeine 内嵌缓存（2min TTL），缓存命中 <1ms，见 `AuthInterceptor` 用户缓存模式 |
| 在同一方法上写两个 `@CacheEvict` | Spring Boot 4.x 中 `@CacheEvict` 不可重复，编译报错；用 `@Caching(evict = { ... })` 包装 |
| 收藏列表逐个 `findByIdAndDeletedFalse` 查场所（N+1） | 用 `findByIdInAndDeletedFalse(List<Long>)` 批量查询，1 次往返替代 N 次 |
| fire-and-forget 端点做冗余场所存在性检查 | 由调用方（详情页 GET /venues/{id}）已校验，fire-and-forget 端点不做重复 DB 查询 |
| 有唯一约束的幂等写入先 SELECT 再 INSERT | `INSERT ... ON CONFLICT DO NOTHING` upsert 单次往返（见 `upsertView`），check-then-act 多一次往返且有并发窗口 |
| 跨表聚合一个表一条查询串成长链 | 单值聚合收敛为标量子查询 mega-query（见 `countHeatCounters`），多行形态才独立查询 |
| 用 @CacheEvict 失效 venueHeat / tagStats | 内嵌 LoadingCache 不走 Spring 缓存，显式调用 `venueHeatService.invalidate` / `tagAggregateStatsService.invalidate` |
| 新增写操作后忘记逐出聚合缓存 | 对照「查询性能优化 → 写路径缓存逐出」矩阵补 invalidate，缓存新鲜度主保障是写路径显式逐出 |
| `@Cacheable` 不写 sync / 给内嵌缓存 loader 挂 AOP 注解 | `@Cacheable` 一律 `sync = true`（单飞）；loader 经 this:: 直调绕开代理，不挂事务/缓存注解 |
| 用 DataGrip 等 GUI 工具拖拽复制表做数据库迁移 | `pg_dump -Fc` + `pg_restore` 保留 identity/序列/主键；GUI 拖拽系统性丢失这些属性且 Hibernate validate 查不出来（见「Schema 完整性与数据库迁移规范」） |
| 遇到 `AssertionFailure: null identifier` 去改业务代码 | 根因在数据库：id 列丢失 IDENTITY/主键（多为迁移事故）。执行 `db/repair-schema-identity.sql` 修复，不从代码层绕 |
| 带显式 id 导入数据后不重置序列 | `setval` 到 max(id)，否则下一次插入主键冲突（修复脚本已内置；手工导入必须做） |
| 恢复"标签点赞"功能或用 1-10 打分做实时众包体感 | 已被 Reaction 快速反馈系统替代（`venuereaction` 模块），新增此类需求一律走 Reaction toggle，不复用 taginteraction |
| Reaction 计数做"每周/每月重置清零" | 原始记录永久保留，按今日/7天/30天/全部四个真实时间窗口实时统计（时间衰减方案），见「Reaction 快速反馈系统」 |
| Reaction 时间窗口套用「统计口径：截至昨日」 | Reaction 窗口锚点为真实"此刻"（今天0点/7天前/30天前），与热度滚动窗口是两套独立时间语义 |
| 实体删除字段后不同步迁移脚本处理遗留列（javadoc 写"已移除"但列仍在） | `ddl-auto: update` 不删列/不取消 NOT NULL、`validate` 不校验列级 NOT NULL → 遗留 NOT NULL 列在运行期插入时才爆炸且被 DataIntegrityViolation 兜底误吞。实体删字段必须同步 `db/migrate-*.sql` 迁移，注释写真实状态（见「Schema 完整性与数据库迁移规范 → 实体字段移除 ≠ 列被删除」） |
| `catch (DataIntegrityViolationException)` 整类吞掉当"并发幂等" | 只允许吞唯一键竞态（SQLState 23505，见 `TagInteractionService.isUniqueViolation`）；NOT NULL/列约束/外键违规必须上抛，否则真实根因被静默掩盖成 200 + 业务码（2026-08-05 liked 列事故） |

---


---
## 验证清单（每次修改后检查）

- [ ] 无 PUT、PATCH、DELETE 方法注解
- [ ] Controller 只做参数绑定，业务逻辑在 Service
- [ ] 所有接口返回 `ApiResponse<T>`
- [ ] 新增 Entity 没有使用 `@Data`
- [ ] 枚举字段使用 `EnumType.STRING`
- [ ] 无敏感信息硬编码在 yaml
- [ ] Jackson 相关 import 使用 `tools.jackson.*`（注解 `com.fasterxml.jackson.annotation` 除外）
- [ ] 新增表名/索引名带 `qwt_` 前缀
- [ ] Entity 无 MySQL 特有 `columnDefinition`
- [ ] 新增 Repository 方法有对应 Service 单元测试
- [ ] `./mvnw test` 通过
- [ ] 新增表/列：`db/migration/V{n}` 迁移脚本 + `./mvnw spring-boot:run`（dev）启动日志出现 `Migrating schema ... version "V{n}"` + `Successfully applied`（**迁移必须真实执行**——2026-08-08 教训：仅声明 flyway-core 不会触发迁移）
