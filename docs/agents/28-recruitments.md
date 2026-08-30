# 28 · 门店招工（recruitment 域）

> **渐进式披露详情文档** —— 由 [AGENTS.md](../../AGENTS.md) 主题索引引用。
> 维护纪律：本文件只承载单一主题的详细设计；新增细节写到这里，**禁止写回 AGENTS.md**；本文件膨胀超过 ~300 行时，请拆出子主题另建文档，并同步登记到 AGENTS.md 索引表。

## 定位与合规（先读）

**定位 = 用工信息展示（黄页），非招聘服务。** 仅管理员直发（P0 无任何用户写入通道，个人主体 UGC 红线第四次同构应用）；无投递/报名/简历闭环——一旦出现"报名"交互即构成招聘服务，需人力资源服务许可证，主体不变不做（P2 红线）。

三层审核风险与缓解：

| 风险 | 缓解 |
|------|------|
| 类目机审扫「招聘/简历/投递/报名」词族 | 对外文案统一「招工/急聘」；接口/表名保留 recruitments 技术命名（path 是技术路径不命中敏感词，对齐舞友之家先例）；**入口接 opsconfig 远程开关（P1，提审一键隐藏）** |
| 舞厅招聘=诈骗重灾区（押金/培训费话术） | 发布风险词扫描（`RISK_WORDS`：押金/保证金/培训费/服装费/办卡/充值/返费/介绍费/进群/私聊），命中返回 **1010**，管理员人工确认 `confirmed=true` 强制放行 |
| 站外导流（微信号明文） | 联系方式点击才下发（见下）；描述导流话术被风险词拦截 |

**刻意不做**：残疾限制等就业歧视字段（对标外部真实样例「聋哑人（不允许）」——平台不容纳歧视性结构化字段）；职位要求只收敛「性别 + 年龄区间」两个中性维度。

## 字段模型（V61，对标真实招工样例）

`qwt_recruitments`：venue_id（**必挂真实门店**，可信度锚点）/ position_types（受控枚举 JSON 数组串：舞伴/领舞/舞蹈老师/DJ/服务员/收银/保洁/其他，管理员勾选不可手写）/ headcount / term（长期合作/短期/面议）/ gender_limit(ANY/MALE/FEMALE) / age_min/max / salary_type + salary_text / accommodation、travel_paid（三态布尔：null 未说明）/ description / contact_name + contact_phone + contact_wechat（**电话与微信并存，发布前至少其一**）/ urgent / status / published_at / expires_at（**必填，默认 30 天**）/ created_by。

`qwt_recruitment_contacts`：获取留痕（UNIQUE(recruitment_id, user_id)，原生 upsert 幂等一记）——管理端「N 人获取联系方式」效果反馈数据源（UV 口径）。

**状态机**：DRAFT →（publish）→ PUBLISHED →（offline）→ OFFLINE →（可重发布）。**过期不落状态**：用户侧谓词 `status='PUBLISHED' AND expires_at > now()` 硬过滤（僵尸信息是信任杀手）；过期记录保留（审计/举报回溯），管理端「已过期」视图 = PUBLISHED 且到期，`/renew` 一键续期 +30 天（已过期从 now 起算）。

## 联系方式纪律（对齐舞伴联系方式）

- 详情/列表接口**恒不下发** contact 字段，`hasContact` 驱动「获取联系方式」入口渲染
- `POST /recruitments/{id}/contact`（需登录）幂等留痕后实时返回真实值；前端复用解锁结果页交互语言：拨打（wx.makePhoneCall）+ 复制微信号
- **免费，不套积分解锁**（招聘信息是平台价值供给；轻量独立端点，勿塞 POST /points/unlock 的需求单流程）
- 获取计数 = 平台直发模式下管理员唯一的效果反馈闭环

## API

用户侧（`/recruitments`，列表/详情公开读）：

| 端点 | 说明 |
|------|------|
| `GET /recruitments?city=&venueId=&page=&size=` | 急聘置顶 + publishedAt 倒序 + id 兜底；EXISTS 子查询过滤门店软删 |
| `GET /recruitments/{id}` | 非可见态（非 PUBLISHED / 已过期）返回 1001 |
| `POST /recruitments/{id}/contact` | 需登录；幂等留痕 + 实时返回 |

管理端（`/admin/recruitments`，全部 requireAdmin，仅 GET/POST）：

| 端点 | 说明 |
|------|------|
| `GET /admin/recruitments?status=&venueId=&keyword=&expired=&page=&size=` | expired=true =「已过期」独立视图；列表含 contactFetchCount（GROUP BY 批量防 N+1） |
| `GET /admin/recruitments/{id}` | 编辑回显（含联系方式真实值） |
| `POST /admin/recruitments` | 创建（落草稿，expiresInDays 1-365 默认 30） |
| `POST /admin/recruitments/{id}/update` | 全量覆盖编辑（有效期不经此接口，走 renew） |
| `POST /admin/recruitments/{id}/publish` | body `{confirmed}`；风险词未确认 → 1010（message 携带命中词） |
| `POST /admin/recruitments/{id}/offline` | 手动下架 |
| `POST /admin/recruitments/{id}/renew` | 续期 +30 天 |

## 展示派生（服务端权威，前端零拼接）

genderText（ANY→null 不渲染 / 限女 / 限男）、ageText（18-35岁 / N岁以上 / N岁以下）、salaryText（salary_text 优先回落薪资类型 label）、headcountText（招N人）、accommodationText/travelText（三态）。positionLabels 服务端权威 label。

## 前端规划（P0 待实施）

- `pages/recruit-list`（城市筛选 + 卡片流）/ `pages/recruit-detail`（门店卡 + 联系区 + 免责固定条 + 举报 + 分享三件套）
- `pages/admin-recruit-list`（状态 tab + 已过期视图 + 获取人数）/ `pages/admin-recruit-edit`（门店选择器 + 职位多选 + 风险词确认弹窗）
- venue-detail 入口行「门店招工 N 条 >」（与 announcements/posts 同构）；免责条固定文案：「信息由平台整理发布，仅供参考。正规用工不收取押金、培训费——遇收费要求请勿支付并举报」
- P1：opsconfig 提审开关、已认领门店 owner 提交意向→管理员代发、过期站内信提醒
