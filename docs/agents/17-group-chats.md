# 舞友群（groupchat 模块，微信引流）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

---

## 定位

运营配置的微信群引流内容（V33 新增）：维度 = 全国（NATIONWIDE）/ 城市（CITY）/
地域（REGION），用户端长按二维码识别加入。群是**平台维度**内容，不绑定单店/单人，
与门店/舞伴域解耦。

## 根因（为什么"长按识别"而非"一键加群"）

微信小程序平台**没有**"程序内直接拉起加入微信群"的开放能力——群相关开放能力仅有
分享到群（shareAppMessage / shareTicket），加群唯一通道 = 群二维码（长按识别 /
扫一扫）。这是平台安全模型设计，非实现缺陷。本模块的正确形态 = 存储群二维码 +
用户端长按识别引导（前端 `<image show-menu-by-longpress>`，详见前端
`docs/agents/17-group-chats.md`）。二维码存在天然失效场景（7 天有效、扫码满 200 人），
后端不做失效探测（微信无状态接口），靠运营换图 + 前端文案降级。

## 数据模型（qwt_group_chats，V33 迁移）

| 列 | 类型 | 说明 |
|----|------|------|
| id | bigint IDENTITY PK | 主键沿用项目惯例（SchemaIntegrityChecker 统一校验 IDENTITY） |
| name | varchar(64) NOT NULL | 群名称（如「杭州舞友群」） |
| scope | varchar(20) NOT NULL | 维度枚举 STRING（枚举类列禁 CHECK，扩维度免迁移） |
| city | varchar(50) 可空 | scope=CITY 必填；picker region 标准行政区划名（与舞厅城市词表一致） |
| region | varchar(50) 可空 | scope=REGION 必填；运营自定义地域名（如「长三角」） |
| qr_code_url | varchar(500) NOT NULL | 群二维码图（落库必须挂 ImageContentValidator 内容校验） |
| description | varchar(200) 可空 | 群简介 / 引导语 |
| display_order | int NOT NULL DEFAULT 0 | 运营排序（@ColumnDefault 声明通道；列表按 displayOrder, id 稳定排序） |
| enabled | boolean NOT NULL DEFAULT true | 上下线开关（false = 下架，公开读不可见；区别于 deleted 软删） |
| updated_by | bigint 可空 | 最近操作管理员（对齐 OpsConfig 先例，审计运营操作） |
| created_at / updated_at / deleted | BaseEntity | 软删模型（deleted=true 彻底移除，管理端列表不可见） |

无唯一约束：同一城市/维度允许多个群（运营按活跃度配置多群分流）。

## 接口清单（仅 GET/POST）

| 接口 | 鉴权 | 说明 |
|------|------|------|
| GET /group-chats | 无 | 公开分组读 → `{ nationwide: [], city: [], region: [] }`（enabled=true AND deleted=false，按 displayOrder, id 排序） |
| GET /admin/group-chats | ADMIN | 管理端列表（deleted=false 含已下线） |
| POST /admin/group-chats | ADMIN | 创建（校验后落库） |
| POST /admin/group-chats/{id}/update | ADMIN | 全量更新（enabled 不在请求内，由 toggle 独立管理） |
| POST /admin/group-chats/{id}/toggle | ADMIN | 上下线翻转（公开读立即生效） |
| POST /admin/group-chats/{id}/delete | ADMIN | 软删（deleted=true） |

错误码：沿用 1001（参数校验/资源不存在），无新错误码。

## 服务规则（GroupChatService）

- **维度一致性校验** `validateDimension`（创建/更新共用，单一入口防规则漂移）：
  CITY → city 必填 + region 禁填；REGION → region 必填 + city 禁填；NATIONWIDE →
  两者禁填。前端管理页有同一份校验（先行拦截减少无效请求），改语义两端同改。
- **图片内容校验**：`qrCodeUrl` 落库前必过 `ImageContentValidator`（08-12 安全
  加固约定——新增图片 URL 落库字段必须挂载校验）；上传分类 `FileCategory.GROUP_QR`
  （前端豁免二次压缩，清晰度敏感）。
- 写路径记录 `updatedBy`（管理员 id）；`updatedAt` 显式刷新（对齐 OpsConfig 先例，
  软删/上下线也刷新，管理端列表可见操作时间）。
- 公开读无后端缓存（读多写少、数据量小，前端会话内缓存承担）。

## 存储

`FileCategory.GROUP_QR("group-qr")`（2026-08-17 新增）——群二维码图片，与
VENUE_QR / DANCER_CONTACT_QR 同二维码语义（清晰度敏感，前端豁免二次压缩）。
