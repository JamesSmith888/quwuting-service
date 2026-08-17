# AGENTS.md — quwuting-service（后端）

> ⚠️ **本文件是主题索引（渐进式披露顶层），不是详细文档库。**
> 详细设计一律按主题拆分在 `docs/agents/` 下，本文件只放项目定位、最小事实与索引表。
> **任何 Agent 修改本文件前，必须先读文末「⚠️ 维护规则」**。

## 项目定位

Spring Boot 4.1 + Java 25 + Spring Data JPA 后端服务，为去舞厅小程序提供舞厅信息查询 REST API（黄页数据展示，无交易/支付逻辑）。连接 Supabase Postgres（外部不稳定环境，韧性为第一优先级）。

## 最小事实（新 Agent 必读）

- **构建/运行**：`./mvnw clean compile`；`./mvnw test -Dspring.profiles.active=dev`（连库并验证 SchemaIntegrityChecker）；`./mvnw clean package -DskipTests`；`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`
- **HTTP 语义**：只允许 GET 和 POST（禁 PUT/PATCH/DELETE）；统一 `ApiResponse<T>`——业务错 = 200+code、未登录 = 401、404 不存在、DB 瞬时 = 503+5003、未预期 = 500+5000；兜底异常禁以 200 伪装
- **Schema 演进唯一通道 = Flyway 迁移**（`db/migration/V{n}__*.sql`），`ddl-auto=validate`；新 NOT NULL 列唯一通道 = `@ColumnDefault`；枚举类列禁 CHECK（扩枚举免迁移）
- **连接池韧性（Supabase 不稳定）**：6543 JDBC URL 必含 `prepareThreshold=0&connectTimeout=5&socketTimeout=8&tcpKeepAlive=true`；连接池参数唯一事实源 = `application.yaml`（禁环境 yaml 重复声明）
- **技术债**：生产 systemd 跑 dev profile（`application-dev.yaml` 生效），切 prod 需先补 systemd 环境变量注入
- 命名：表 `qwt_` 前缀；根包 `org.quwuting.quwutingservice`

## 主题索引（渐进式披露）

> 需要细节时按主题查阅对应文档；**禁止把细节写回本文件**。

| 主题文档 | 内容 | 何时查阅 |
|---------|------|---------|
| [`01-build-and-run.md`](docs/agents/01-build-and-run.md) | 编译/测试/启动命令、开发测试数据（seed-dev.sql） | 构建、跑测试 |
| [`02-package-structure.md`](docs/agents/02-package-structure.md) | 包结构总览（按功能分包、controller/service/mapper/repository 分层） | 找类、新模块落地 |
| [`03-auth-and-user.md`](docs/agents/03-auth-and-user.md) | 登录流程、软鉴权、微信 API 调用规范、角色、用户资料与用户态刷新 | 鉴权/用户相关 |
| [`04-venue-domain.md`](docs/agents/04-venue-domain.md) | 场所数据模型（核心信息/地址/坐标/营业时间/消费信息）、门店认领与管理权限、场所动态 | venue 域改动 |
| [`05-venue-heat.md`](docs/agents/05-venue-heat.md) | 热度公式与权重、统计口径（实时/截至昨日分家）、各趋势口径、营业稳定性、状态可信度、性能优化 | 热度/统计接口 |
| [`06-listing-and-stats.md`](docs/agents/06-listing-and-stats.md) | 复合评分排序、双查询拆分坑位、标签筛选、城市词表、isHot 热门标记、累计浏览量 | 列表接口 |
| [`07-feedback-and-reporting.md`](docs/agents/07-feedback-and-reporting.md) | 统一用户上报（类型/状态机/防刷/激励下发）、场所状态上报（TTL/采纳联动/紧急公告） | 上报模块 |
| [`08-reaction-and-rating.md`](docs/agents/08-reaction-and-rating.md) | 评分交互（维度/防刷）、Reaction 系统（每日一记模型、字典、聚合缓存） | 评分/Reaction |
| [`09-dancer-and-points.md`](docs/agents/09-dancer-and-points.md) | 舞伴生态（8 表模型/审核/认可/可见性）、**认可单票换票 + 可配置多选**（2026-08-15：Reaction 风格表情 chip 单票——参与/同票取消/异票原子换票，开关 dancer.recognition.daily.single（V31 默认 true）关闭 = 多选；**崩溃根因修复**：@Modifying 批量删除替代派生删除 + pg_advisory_xact_lock 同键串行化 + afterCommit 缓存失效；RecognizeResponse 扩展 replacedFrom/myTags/tags(四窗口) 绝对快照，详情响应 +myTags；旧 tags 列表兼容；表情不复用门店反馈字典）、**舞伴收藏**（独立表/幂等接口/收藏列表仅 NORMAL，能力平权）、**舞伴官方认证**（「信息已核验」：V26 审计日志/状态机可回退/编辑触发待复核/撤销必留痕，2026-08-14）、**舞伴统计**（六图趋势/GET stats/V29 浏览埋点/写路径缓存失效，2026-08-14）、积分系统（账务规则/礼物赠送/合规红线）、**积分解锁公共模块**（门槛/解锁/模糊图）、**创作者收益计划**（激励视频广告/线下结算，2026-08-14） | 舞伴/积分模块 |
| [`10-messaging-and-sharing.md`](docs/agents/10-messaging-and-sharing.md) | 站内信（消息中心）、关注门店营业状态（触发挂点/幂等）、分享追踪 | 通知/分享 |
| [`11-storage.md`](docs/agents/11-storage.md) | 文件存储（前端直传 Supabase、FileCategory、内容校验安全模型） | 上传相关 |
| [`12-api-conventions.md`](docs/agents/12-api-conventions.md) | HTTP API 规范（仅 GET/POST）、统一响应格式、错误码登记表 | 写新接口前 |
| [`13-code-standards.md`](docs/agents/13-code-standards.md) | JPA 实体、多值字段 JSON 列、Repository、DTO、异常处理、请求耗时日志、Jackson 3.x、命名 | 写后端代码前 |
| [`14-deployment-and-schema.md`](docs/agents/14-deployment-and-schema.md) | 配置管理、生产部署、连接池与数据库抖动韧性、Flyway Schema 演进与完整性 | 部署、Schema 变更 |
| [`15-governance.md`](docs/agents/15-governance.md) | 禁止操作、AI 代理常见错误表、验证清单 | 每次修改后、提交前 |
| [`16-ops-config.md`](docs/agents/16-ops-config.md) | 运营配置（feature flag）设施：qwt_ops_config 表、读写接口、管理端入口、键即代码契约 | 新增可配置产品规则时 |
| [`17-group-chats.md`](docs/agents/17-group-chats.md) | 舞友群（V33：微信引流，平台无一键加群 API → 长按识别二维码；scope 三态维度互斥校验；公开分组读 + ADMIN CRUD；qr_code_url 挂 ImageContentValidator；GROUP_QR 存储分类，2026-08-17） | 群聊相关 |

---

## ⚠️ 维护规则（强制：渐进式披露）

本文件曾膨胀至 **2200+ 行**（教训），现重构为索引。**所有 Agent（含未来的 AI 代理）更新本文件时必须遵守：**

1. **本文件只承载索引与轻量描述**：项目定位、最小事实速查、主题索引表。任何详细设计、迭代历史、根因分析、代码示例一律写入对应主题文档（`docs/agents/` 下），本文件只保留「链接 + 一行摘要」。
2. **新增/修改细节 → 先定位主题文档**：按索引表找到对应文档，细节写进文档；同步更新索引表摘要（保持一行）。
3. **新主题 → 新建文档并登记**：无现成文档承载的新主题，在 `docs/agents/` 下新建 `NN-主题.md`（序号延续），复制现有文档头部模板（含维护警告），并在索引表登记一行。
4. **文档膨胀 → 拆分子文档**：单个 `docs/agents/` 文档超过 ~300 行时，拆出独立子主题文档，索引表同步。
5. **禁止把历史演进堆进索引**：每轮迭代的「决策过程/踩坑复盘」只允许进主题文档，且尽量压缩为可复用的规则沉淀，不留流水账。
6. **写完后自查**：本文件应保持在 ~100 行以内；索引表每个主题恰好一行；改动前先读对应主题文档，避免重复/冲突。
