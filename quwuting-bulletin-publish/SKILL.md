---
name: quwuting-bulletin-publish
description: 去舞厅（quwuting）行业快讯发布工作流。当需要把人工收集的行业情报（某市/某店停业、开闭店、营业时段调整等「服务可得性」信息）投放到小程序 tabBar「快讯」入口时使用。核心链路：登录生产管理端 → 内容边界裁决 → 构造 dedupKey → 一步 agent-publish（幂等）→ 汇报。也覆盖管理端 create/publish/update/offline 与「删除后重投」。禁止用于发布事件经过/原因类内容。
agent_created: true
---

# 去舞厅 · 行业快讯发布

> 权威文档 = 后端 `quwuting-service/docs/agents/47-bulletins.md` + `bulletin/` 包
> （`BulletinService` 状态机）。小程序消费面 = tabBar 第三个 Tab「快讯」
> （`pages/bulletins` 列表 + `pages/bulletin-detail` 详情，均只读）。

## 🚫 红线（最高优先，违反即事故）

1. **内容边界（比技术实现更重要）**：快讯**只描述「服务可得性」**——哪家店 / 哪个时段
   开或关。**禁止**描述事件经过、原因、定性、当事人、执法信息。
   - ✅ 「XX 舞厅自 9 月 10 日起暂停营业，恢复时间待定」
   - ✅ 「近期 XX 城区多家门店营业时间调整为 19:00 起」
   - ✅ 「XX 舞厅今日临时关闭一天」
   - ❌ 「XX 舞厅因××被查」「XX 舞厅停业整顿」
   - ❌ 「XX 舞厅发生××事件」「据传 XX 出事了」
   - **原因不明就不写原因**。详情页有免责声明，但**平台是发布者**，失实内容仍构成
     名誉权风险（《民法典》1024/1025 条）——涉单店负面信息一律不发，宁缺勿险。
2. **发布是面向全体用户的对外动作**：用户本轮明说「发快讯 / 投放 / 发这条」即视为授权，
   无需逐字段确认；用户未要求 → 不创建。
3. **环境即生产**：快讯唯一生效环境 = `https://api.starseek.online`（本地 develop 发了
   用户看不到，除非用户明说"发本地测试"）。用户确认 = 授权打**快讯域**接口，
   **仅限快讯域**，不得顺手动门店数据。
4. **幂等优先**：Agent 投放一律带 `dedupKey`——重跑安全（命中返回已存在条目，不重复投放）。
5. **双位置同步约定**（同 `quwuting-announcement-publish`）：
   - 项目路径（Git，源文件）：`/Users/xin.y/WeChatProjects/quwuting-service/quwuting-bulletin-publish/`
   - 用户路径（WorkBuddy 加载）：`~/.workbuddy/skills/quwuting-bulletin-publish/`
   - 改动先写项目路径，再 `cp -R` 到用户路径（排除 `.DS_Store`/`__pycache__`）。

## 登录与 token

与公告域同一条链路（**完整口径见 `quwuting-announcement-publish` 的「登录与 token」节**）：

- `BASE_URL=https://api.starseek.online`
- token 顺序：`ADMIN_TOKEN` 环境变量 → `/tmp/qw_token.json` 缓存 → 401 后重新登录
  （`POST /web-auth/password-login`，body `{username, password}`，密码取
  `WEB_ADMIN_PASSWORD` 环境变量，**勿回显密码**）。
- 有效性一句话验证：`curl -H \"Authorization: Bearer $TOKEN\" $BASE_URL/admin/bulletins?page=0&size=1`
  → 200 可用，401 重登。

## 快讯模型（速记）

- **category 恒 `FLASH`**：接口不接受调用方指定（服务端固定）。
- **source**：管理端创建恒 `MANUAL`；`agent-publish` 通道恒 `AGENT`（均服务端固定，
  请求体不可伪造）。
- **与公告的三处不同**：无置顶（纯时间流）、无已读回执（不进红点 / 不计未读数）、
  多 `city` 与 `venueId` 两个展示字段。
- **字段**：

  | 字段 | 约束 | 说明 |
  |---|---|---|
  | `title` | ≤50 字，必填 | 一句话说清：哪家店 / 什么时段 / 开还是关 |
  | `content` | markdown，≤50KB，必填 | 正文；`[店名](venue://<id>)` 可跳门店详情 |
  | `city` | ≤32 字，可空 | 列表卡片城市标签（一期不做筛选，仅展示） |
  | `venueId` | 门店 ID，可空 | 列表卡片锚点；**必须真实存在**（后端校验，不存在直接 400） |
  | `dedupKey` | ≤64 字，可空 | Agent 幂等键；**Agent 投放约定必带** |
  | `publishAt` | LocalDateTime | 未来时刻 = 定时发布；缺省 = 立即发布 |
  | `offlineAt` | LocalDateTime | 自动下线；**快讯建议设 24–72h**（不设则长期保留） |

- **状态机**：`DRAFT →（publish）→ PUBLISHED →（offline / offlineAt 到点）→ OFFLINE`
  →（重新 publish 唯一复活通道）→ PUBLISHED；任意态可软删。

## dedupKey 约定

格式：`{来源}:{日期}:{主题键}` —— 日期用 `YYYY-MM-DD`，主题键用简短英文/拼音。

- `xianbao:2026-09-10:cq-stop`（砂舞线报网 · 重庆停业）
- `manual:2026-09-10:cq-t19-hours`（人工 · 重庆 19 点后营业）

规则：
- **同一件事重跑 → 用同一个 key** → 幂等返回已存在条目，不会重复投放。
- **同一件事后续有更新**（如"恢复时间待定"→"9/15 恢复"）→
  ① 走管理端 `POST /admin/bulletins/{id}/update` 原地改（推荐，用户在列表里只看到一条）；
  ② 或换新 key 发第二条（会并列显示，慎用）。
  ⚠️ **改了内容却复用旧 key = 静默不生效**（命中幂等直接返回旧条目），这是最常见的"发了没反应"。
- 软删某条后，同 key 可以重新投放（唯一索引只约束未删条目）。

## 工作流（四步）

### Step 1 登录 + 内容裁决

1. 取 token（见上）。
2. **对每条待发信息做内容裁决**（红线 1）：
   - 是「服务可得性」→ 通过；
   - 含事件/原因/定性 → **剔除该表述**，只保留"开或关"的事实部分；
   - 整条都是事件 → **不发**，向用户说明原因。
3. `GET /admin/bulletins?page=0&size=10` 扫一眼近期条目（防重 + 确认主题键是否已被占用）。

### Step 2 构造 payload

- **中文 JSON 用 python 构造**（`json.dumps(..., ensure_ascii=False)`），勿手工拼 shell 引号。
- `venueId` 拿不准就**核实**（`GET /venues/{id}` 返回 200 才写）；核实不了就留空，
  正文里也别写 `venue://` 假 id。
- `city` 用门店词表里的**标准城市名**（与门店数据一致，避免"重庆"与"重庆市"两种写法）。
- `offlineAt` 建议 = 发布时刻 + 48h（快讯时效性强，长期滞留会让列表变噪音）。
- **正文写法**（markdown 契约同公告域）：段间空行分段（单换行会被合并）；
  门店链接只写 `[店名](venue://<id>)`；**禁放外链**（navigator 不能跳外链，会被降级为纯文本）。

### Step 3 投放

Agent 场景（推荐，一步完成 + 幂等）：

```bash
python3 - <<'PY'
import json, subprocess, os
token = open('/tmp/qw_token.json').read()
token = json.loads(token)['token']
payload = {
    "title": "重庆城区门店营业动态",
    "content": "接门店通知，XX 舞厅自 9 月 10 日起暂停营业，恢复时间待定。\n\n近期 XX 城区多家门店营业时间调整为 19:00 起。",
    "city": "重庆",
    "venueId": None,
    "dedupKey": "xianbao:2026-09-10:cq-stop",
    "offlineAt": "2026-09-12T14:00:00"
}
body = json.dumps(payload, ensure_ascii=False)
r = subprocess.run([
    'curl','-s','-X','POST','https://api.starseek.online/admin/bulletins/agent-publish',
    '-H', f'Authorization: Bearer {token}',
    '-H', 'Content-Type: application/json',
    '--data-binary', '@-'
], input=body.encode(), capture_output=True)
print(r.stdout.decode())
PY
```

返回 `data.id` + `data.status`（`PUBLISHED` = 已发布；`DRAFT` = 传了未来 publishAt，
到点由后端 30s 调度自动发布）。

管理端人工场景（需要草稿态 / 手动核对）：

```bash
# ① 建草稿
curl -s -X POST "$BASE_URL/admin/bulletins/create" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"...","content":"...","city":"重庆","offlineAt":"2026-09-12T14:00:00"}'
# → data.id；② 再发布（缺省立即）
curl -s -X POST "$BASE_URL/admin/bulletins/{id}/publish" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" -d '{}'
```

⚠️ 只 `create` 不 `publish` 会停在草稿（用户端不可见）——两步必须连做。

### Step 4 汇报

- 汇报：快讯 id、status、publishAt、offlineAt、city、dedupKey。
- 提醒：快讯出现在小程序底部导航第三个 Tab「快讯」；要撤下可
  `POST /admin/bulletins/{id}/offline`（或等 offlineAt 自动下线）。
- 若命中幂等（返回的是已存在条目）→ 明确告诉用户"该 dedupKey 已存在，未重复投放"，
  并说明要用新 key 或走 update 才能改内容。

## 接口速查表（全部 requireAdmin，Bearer 鉴权，仅 GET/POST）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /web-auth/password-login | 登录换 JWT（body: {username, password}） |
| POST | /admin/bulletins/agent-publish | **一步发布（幂等，Agent 首选）** |
| GET | /admin/bulletins?status=&source=&page=&size= | 快讯列表（防重 / 核查，id 倒序） |
| GET | /admin/bulletins/{id} | 详情 / 编辑回显 |
| POST | /admin/bulletins/create | 建草稿（source=MANUAL） |
| POST | /admin/bulletins/{id}/update | 原地更新（DRAFT 全字段；PUBLISHED 除 publishAt 外可改；OFFLINE 禁改） |
| POST | /admin/bulletins/{id}/publish | 发布（body 可带 publishAt 定时；OFFLINE 复活同语义） |
| POST | /admin/bulletins/{id}/offline | 手动下线 |
| POST | /admin/bulletins/{id}/delete | 软删（删后同 dedupKey 可重投） |

统一响应包 `{code, message, data}`，code=0 成功。401 = token 过期，重新登录。

## 常见问题

- **「发了没反应」/ 列表里没有新条目**：九成是 `dedupKey` 撞了旧条目 → 幂等返回已存在，
  未新建。改内容要用**新 key** 或走 `update`。
- **400「关联门店不存在」**：`venueId` 是脏值。核实 `GET /venues/{id}` 后改，
  或留空不关联。
- **400「快讯内容包含不允许的标签」**：正文含 `<script` 或 `<iframe` 原始标签。
- **update 报错 / OFFLINE 改不了**：已下线快讯禁改，需先 `publish` 复活再改。
- **PUBLISHED update 会清空 offlineAt**：请求体不回传 `offlineAt` 会把自动下线时间置空
  （快讯变长期保留）——**只要还想自动下线，update 必须把原 offlineAt 一并回传**。
- **offlineAt 报「必须晚于当前时间」**：用了过去时刻，改未来。
- **正文里门店链接点不动**：放了外链（已被降级为纯文本，不报错但不可点），
  或 `venue://` 的 id 不存在。
- **该发公告还是快讯？** 判断线 = **是否需要平台为内容真实性背书**：
  平台权威内容（数据更新 / 新功能 / 规则）→ 走公告（`quwuting-announcement-publish`，
  强触达 + 平台背书）；行业情报（时效性营业动态）→ 走快讯（本 Skill，弱触达 + 不背书）。
  拿不准就问用户。
