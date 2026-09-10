# 47 · 行业快讯（bulletins）

> 2026-09-10 设计定稿并落地。承接自用户需求：「有些行业资讯需要往平台发，但不能直接发在
> 公告里」——例如「××市因××全部停业」「××舞厅营业动态」。公告是平台权威发布（强触达 +
> 平台背书），这类高频时效情报混进去会稀释公告权威、消耗用户注意力预算，故独立成域。
>
> ⚠️ **本文件在两仓各存一份**（`quwuting/docs/agents/` 与 `quwuting-service/docs/agents/`），
> 内容保持完全一致，改动须双向同步（同 skill 双位置约定的理由：两仓是独立 Git 仓库）。
>
> 后端实现：`org.quwuting.quwutingservice.bulletin` 包；数据结构复用公告表
> （`qwt_announcements` + `category='FLASH'`）；迁移 `V18__bulletins.sql`。
> 姊妹文档：`34-announcements.md`（公告域，接口契约冻结、本域不改动它）。
> 发布 Skill：`quwuting-bulletin-publish`。

## 一、领域定位与边界（最重要的一节）

快讯与公告是**两个语义域**，靠一条判据分界：

> **分界线 = 是否需要平台为这条内容的真实性背书。**

| 维度 | 公告（NOTICE / DATA_UPDATE） | 快讯（FLASH） |
|---|---|---|
| 内容性质 | 平台权威内容（数据更新 / 新功能 / 规则） | 行业情报（停业 / 开闭店 / 时段调整） |
| 发布者 | 平台官方 | 平台编辑整理（转载性质） |
| 频率 | 低频 | 高频 |
| 触达 | 强：首页悬浮公告条 + 红点 + 已读回执 | 弱：tabBar 入口 + 列表，**无红点无已读** |
| 平台背书 | 有（平台担责） | 无（列表/详情标注"仅供参考"） |
| 排序 | pinned 优先 | 纯时间流，无置顶 |
| 生命周期 | 建议设 offlineAt 防霸屏 | 自然沉底，建议 24–72h 自动下线 |

### 🚨 内容边界（比技术实现更重要）

**快讯只描述「服务可得性」**——哪家店 / 哪个时段 开或关。

- ✅ 「XX 舞厅自 9 月 10 日起暂停营业，恢复时间待定」
- ✅ 「近期 XX 城区多家门店营业时间调整为 19:00 起」
- ❌ 「XX 舞厅因××被查」/「XX 舞厅停业整顿」
- ❌ 「XX 舞厅发生××事件」

理由：平台是**发布者**，失实内容直接构成名誉权风险（《民法典》1024/1025 条）；
事件类信息必然牵涉执法/治安，属审核高危区，叠加舞厅行业属性（见
`MEMORY-REVIEW-COMPLIANCE.md` 第六节）等于自我加码。**原因不明就不写原因；涉单店
负面信息一律不发。**

> 按用户 2026-09-10 拍板：**不引入自动敏感词拦截**（词表化会把风控责任转移到代码，
> 且易产生漏网的虚假安全感），改由发布前人工把控 + 管理端界面常驻边界提醒
> （`BulletinListView` 顶部 policy-tip / `BulletinEditView` 正文区 md-policy）。

### 审核合规前提

个人主体小程序曾被驳回「涉及用户自行生成内容的发布/分享/交流，属社交范畴」
（详见 `MEMORY-REVIEW-COMPLIANCE.md`）。快讯域**合规成立的前提是只读单向**：

- 小程序端**零输入控件、零评论、零点赞、零转发到列表**，用户不能产生任何内容；
- 全部写操作只在 admin-web 与 Agent 接口；
- 形态是「官方单向发布的内容展示」，与公告同构，**不新增审核面**。

⚠️ 任何"开放投稿 / 用户爆料上墙"的改法都会立刻把本域变成 UGC，**禁止**。

## 二、数据模型（V18）

复用 `qwt_announcements`，新增三列 + 一个 Agent 幂等唯一键（公告条目三列恒为 NULL，零影响）：

| 列 | 类型 | 用途 |
|---|---|---|
| `city` | varchar(32) | 城市标签（列表卡片展示；**一期仅展示，不做筛选/定向**） |
| `venue_id` | bigint | 关联门店（可空；列表卡片锚点，服务端校验真实性） |
| `dedup_key` | varchar(64) | Agent 幂等去重键（非空才参与唯一约束） |

```sql
-- 生成列唯一索引：仅对「未软删 + FLASH + dedup_key 非空」生效（V7 DATA_UPDATE 同款先例）
uk_key_qwt_idx_ann_flash_dedup = IF((deleted=0) AND (category='FLASH') AND (dedup_key IS NOT NULL),
                                    MD5(dedup_key), NULL) STORED
CREATE UNIQUE INDEX qwt_idx_ann_flash_dedup ON qwt_announcements (uk_key_qwt_idx_ann_flash_dedup);
CREATE INDEX qwt_idx_ann_category_status_publish ON qwt_announcements (category, status, publish_at);
```

枚举扩值（varchar 存储，**无需迁移**）：

- `AnnouncementCategory` += `FLASH`
- `AnnouncementSource` += `AGENT`（Agent 投放通道专用，服务端固定，请求体不可指定）

## 三、与公告域的互斥契约（关键设计）

**两个域共用 `AnnouncementRepository`，靠 `category` / `excludeCategory` 双向隔离：**

| 查询方法 | 公告侧 | 快讯侧 |
|---|---|---|
| `findVisiblePage` | `category=null, excludeCategory=FLASH` | `category=FLASH, excludeCategory=null` |
| `findPageByFilters` | 同上 | 同上 |
| `countUnread` | `excludeCategory=FLASH`（**快讯无已读回执，绝不能计入公告未读数**，否则红点永不收敛） | 不使用 |

服务端还有一道防御：`AnnouncementService#rejectFlashCategory` —— 公告管理端
`create`/`update` 若收到 `category=FLASH` 直接 400，防止从公告入口造出"无已读回执却
进公告列表"的脏条目。反向同理：`BulletinService#findAny/findPublished` 校验
`category == FLASH`，非快讯条目一律 404，**不能借快讯接口读写公告**。

> 实现取舍：`BulletinService` 与 `AnnouncementService` 存在少量重复代码（内容校验 /
> 调度窗口校验 / 状态流转）。这是**有意为之**——两个域可各自独立演进，不因改一个
> 而牵动另一个；且**公告域接口契约（含已上线的 `quwuting-announcement-publish` skill）
> 全程零改动**，只改了 Repository 查询条件。

## 四、接口契约

### 用户端（需登录，只读）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/bulletins?page=&size=` | 可见列表（PUBLISHED + 已生效，时间倒序，`publishAt DESC, id DESC`） |
| GET | `/bulletins/{id}` | 详情（markdown 原文；未发布/已下线/已删/非 FLASH → 404） |

- **无已读回执**（刻意）：不进红点、不计未读数，新鲜度由列表的相对时间表达。
- **一期不支持城市筛选**：`city` 随条目返回，仅作列表卡片展示标签。

### 管理端（需 ADMIN）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/admin/bulletins?status=&source=&page=&size=` | 列表（id 倒序，恒只含 FLASH） |
| GET | `/admin/bulletins/{id}` | 详情 / 编辑回显 |
| POST | `/admin/bulletins/create` | 建草稿（source 固定 MANUAL） |
| POST | `/admin/bulletins/{id}/update` | 更新（状态机见下） |
| POST | `/admin/bulletins/{id}/publish` | 发布（body 可带 `publishAt` 定时；缺省立即） |
| POST | `/admin/bulletins/{id}/offline` | 下线 |
| POST | `/admin/bulletins/{id}/delete` | 软删除 |

### Agent 通道

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/admin/bulletins/agent-publish` | **一步 create + publish**（agent 场景不需要草稿态），source 固定 AGENT，`dedupKey` 幂等 |

请求体：`{title, content, city?, venueId?, dedupKey?, publishAt?, offlineAt?}`

- `publishAt` 未来时刻 → 定时（状态 DRAFT，由既有 `@Scheduled` 30s 调度强转 PUBLISHED）；
  缺省/过去时刻 → 立即发布。
- `source` 由服务端固定 `AGENT`，**请求体不接受指定**（防伪造来源）；`operator_id`
  记调用管理员审计。
- 鉴权沿用现有管理端 JWT（skill 已有 `/tmp/qw_token.json` 链路），**不引入新鉴权体系**。

### 状态机（与公告域同构）

```
DRAFT --publish(立即/定时)--> PUBLISHED --offline / offlineAt 到点--> OFFLINE
  ^                                                                    |
  +--------------------- publish（唯一复活通道） -----------------------+
任意状态 --delete--> 软删
```

- **DRAFT**：全字段可改（含 `publishAt`）。
- **PUBLISHED**：除 `publishAt` 外全字段可改并即时生效（`publishAt` 已生效不可改，
  要改定时请先下线再重新发布）。
- **OFFLINE**：禁改，需 `publish` 复活。
- 重新发布时会**清空过期的遗留 `offlineAt`**（否则 30s 调度会立刻再度下线）。

## 五、幂等设计（Agent 契约）

两层防护，与公告域 DATA_UPDATE 同日防重同款：

1. **查询前置**：`findFirstByDedupKeyAndDeletedFalseOrderByIdDesc(dedupKey)` 命中 →
   直接返回已存在条目，**不改写其任何字段**。
2. **唯一索引兜底**：并发撞键由 V18 生成列唯一索引拦下（`DataIntegrityViolationException`
   → 回查取回已存在条目返回）。

**为什么命中不改写字段**：重跑语义是「确保这条存在」，不是「覆盖」——
采集源事后纠错应由人工走管理端 `update`，不能被重跑悄悄盖掉。

**约定**：`dedupKey` 格式 `{来源}:{日期}:{主题键}`，例 `xianbao:2026-09-10:cq-stop`。
内容有更新时要么换新 key（发第二条，并列显示），要么走 `update` 原地改——
**改内容却复用旧 key 会静默不生效**（最常见的"发了没反应"）。

## 六、小程序端

### tabBar 第三个 Tab（首页 / 快讯 / 我的）

改动落点与**几何契约**：

- `custom-tab-bar/index.ts` 的 `list` 插入第 2 项（index=1）；
- `app.json` 的 `tabBar.list` 同步（双处镜像，见该文件头契约）；
- 各 tab 页 `onShow` 上报选中位：首页 0、**快讯 1**、**我的 2**（原为 1，本次后移）；
- 图标：新增 TDesign 官方 `sound` / `sound-filled` 进
  `scripts/generate-icons.js` 的 `ICON_NAMES` 白名单并重跑 `npm run gen:icons`
  （白名单校验防拼写漂移；产物 `components/qwt-icon/icons.js` 为构建产物禁手改）。

⚠️ **胶囊宽度保持 360rpx 不变**，三格平分（每格 120rpx ≥ 88rpx 命中下限）——
因此 `left: calc(50% - 180rpx)` 减数、`TAB_BAR_HEIGHT_RPX`（124rpx）、
`timer-float` 的锚定位几何**全部无需改动**。（若将来加宽胶囊，必须同步
`.tab-bar` 的 width 与 left 减数，并复核与右下角计时悬浮窗的水平关系。）

### 页面

| 页面 | 说明 |
|---|---|
| `pages/bulletins` | 列表（tab 页）。卡片 = 城市标签 + 相对时间 + 标题；触底分页；尾部免责声明 |
| `pages/bulletin-detail` | 详情。towxml 渲染 markdown + `normalizeAnnouncementLinks` 归一化 `venue://` 链接 |

- 相对时间：今天 `HH:mm` / 昨天 `昨天 HH:mm` / 更早 `MM-DD HH:mm`。
- 详情页与公告详情的差异：无已读回执、无 `qw-demo` 演示块、尾部多一条免责声明。
- 复用公告详情踩过的两个坑（已在本页 wxss 内一并覆盖）：towxml 主题自带
  `text-align: justify`（中文两端对齐事故）已在页面级覆盖为 `left`；
  `.h2w__main` 自带 `margin/padding` 已归零（边距所有权归 `.article` 白带）。

### 合规红线（复查清单）

- [ ] 小程序端快讯相关页面**无任何 input/textarea/发布按钮**
- [ ] 无评论、点赞、转发到列表的入口
- [ ] 只有 `listBulletins` / `getBulletinDetail` 两个读接口，`services/bulletin.ts` 无写方法
- [ ] 页面 `onShareAppMessage` 只分享"板块"而非鼓励二次传播（有意不定义 `onShareTimeline`）

## 七、管理端（quwuting-admin-web）

- 菜单：「快讯管理」（`AppLayout.MENUS` 登记，图标 `newspaper-o`）。
- 路由：`/bulletins`、`/bulletins/edit/:id?`（可选参数路由，同公告域先例——避免
  双路由导致 Vue Router 4 静默丢弃 params）。
- 服务：`src/services/bulletin.ts`。
- 列表页顶部常驻**内容边界提醒**；编辑页正文区常驻同类提醒。
- 城市用 `/venues/cities`（公开词表）做 action-sheet 选择，保证与门店城市口径一致
  （避免"重庆"与"重庆市"两种写法并存）。
- 列表展示 `dedupKey`，便于排查「为什么没重复发」。

## 八、红线汇总

1. **只读单向**：小程序端零输入控件、零互动；写操作只在 admin-web / Agent 接口。
2. **只写服务可得性**：不写事件经过与原因；涉单店负面信息一律不发。
3. **不引入用户内容**：任何"投稿/爆料上墙"改法都是审核死线。
4. **公告契约冻结**：本域不修改公告域接口/DTO/状态机语义；仅 Repository 查询加
   隔离条件（已确保公告侧行为不变）。
5. **`venueId` 必须真实**：服务端已校验（不存在 → 400），脏 id 会让列表卡片跳 404。
6. **Agent 必带 `dedupKey`**：重跑保护；改内容必须换 key 或走 update。
7. **迁移只在 `db/migration-mysql/`**（V18），PG 目录冻结。

## 九、验证状态与遗留

**已完成（静态层面）**：后端 `./mvnw -q clean test-compile` 通过；
小程序 `npm run check` 全绿（tokens 76 项 / positioning 22 项）+ tsc 编译产物回拷；
管理端 `vue-tsc` 无本域类型错误（构建失败项为既存的 `echarts` 依赖未安装，与本次无关）。

**待用户真机验证（本地不做运行期验证，按验证深度红线）**：

- [ ] 迁移 V18 在本地库执行成功（`./mvnw ... -Dspring-boot.run.profiles=mysql spring-boot:run` 启动时 Flyway 应用）
- [ ] tabBar 第三格渲染与选中态动画（三格平分 360rpx 胶囊的视觉密度）
- [ ] 快讯列表/详情 towxml 渲染、深色主题、免责声明
- [ ] 管理端快讯管理菜单 → 新建 → 发布 → 小程序端可见 全链路
- [ ] Agent 接口幂等：同 dedupKey 连发两次只产生一条
- [ ] 公告域回归：公告中心/首页公告条**不出现快讯**，未读红点**不被快讯影响**

**遗留（用户可在需要时开启）**：

- 城市筛选 / 按用户城市定向（字段已就绪，一期按用户拍板不做）
- ops-config 提审期隐藏入口开关（公告域有先例；本域暂未加，因为入口即 tabBar，
  提审期如需隐藏要改版本，不适合用开关"审一套跑一套"——见 COMPLIANCE 红线）
- 阅读统计（快讯无已读回执，暂无阅读率口径）
