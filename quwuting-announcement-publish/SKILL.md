---
name: quwuting-announcement-publish
description: 去舞厅（quwuting）全局公告发布工作流。当需要向小程序全体用户发布公告（数据更新公告 / 新功能公告 / 运营通知 / 停业通知等）时使用。核心链路：登录生产管理端 → 防重检查 → 按 category 撰写正文（markdown，门店可点 venue:// 链接）→ create + publish → 汇报。也覆盖"补充门店到已发公告"（原地 update）与"下线公告"（offline）。
agent_created: true
---

# 去舞厅 · 全局公告发布

> 提取自 `quwuting-venue-daily-sync` Step 6（2026-09-08），独立成 Skill 以支持非舞讯场景
> （新功能公告、运营通知等）。权威文档 = 前端 `quwuting/docs/agents/34-announcements.md` +
> 后端 `announcement/` 包（AnnouncementService 状态机）。数据更新公告固定模板的维护以
> `quwuting-venue-daily-sync` 为准，两处改动须双向同步。

## 🚫 红线（最高优先，违反即事故）

1. **公告是面向全体用户的对外动作**：发布前必须用户明确确认（用户本轮指令明说
   「发公告/直接发」即视为授权，无需再逐字段确认）；用户未要求 → 不创建。
2. **环境即生产**：公告唯一生效环境 = `https://api.starseek.online`（本地 develop 发了
   用户看不到，除非用户明说"发本地测试"）。其他写库类操作的生产红线不适用于"发布公告"
   本身——用户确认发公告 = 授权打生产公告接口，但**仅限公告域**，不得顺手动门店数据。
3. **防重先查后发**：发布前必 `GET /admin/announcements?page=0&size=10` 扫一眼——
   同日同类公告已存在时按「补充 vs 重发」分流（见 Step 2），严禁同日重复轰炸。
4. **双位置同步约定**（同 quwuting-venue-daily-sync）：
   - 项目路径（Git，源文件）：`/Users/xin.y/WeChatProjects/quwuting-service/quwuting-announcement-publish/`
   - 用户路径（WorkBuddy 加载）：`~/.workbuddy/skills/quwuting-announcement-publish/`
   - 改动先写项目路径，再 `cp -R` 到用户路径（排除 .DS_Store/__pycache__）。

## 登录与 token

- 生产地址：`BASE_URL=https://api.starseek.online`（公告域固定生产，见红线 2）。
- token 获取顺序：`ADMIN_TOKEN` 环境变量 → **`/tmp/qw_token.json` 缓存**（跨会话有效，
  2026-09-08 实证昨日 token 次日仍 200）→ 401 后重新登录。
- 登录：`POST /web-auth/password-login`，body `{username, password}`（username 默认
  `admin`，password = `WEB_ADMIN_PASSWORD` 环境变量；当前 shell 未导出时请用户提供，
  **勿把密码回显到输出**）。
- 有效性一句话验证：`curl -H "Authorization: Bearer $TOKEN" $BASE_URL/admin/venue-sync/reversals?limit=1`
  → 200 可用，401 重登。
- ⚠️ 缓存读取：`python3 -c "import json;print(json.load(open('/tmp/qw_token.json'))['token'])"`，
  传入变量时变量名必须 `ADMIN_TOKEN` 或直接 `TOKEN=...`（脚本内自定义）。

## 公告模型（速记）

- **分类 `category`**：`NOTICE`（运营/新功能/通知）/ `DATA_UPDATE`（数据更新）。
- **来源**：管理端创建恒 `MANUAL`（SYSTEM 只能走后端 createDataUpdateAnnouncement 内部
  通道，API 不可伪造）。
- **状态机**：`DRAFT →（publish，立即或定时）→ PUBLISHED →（offline 手动 / offlineAt
  到点 30s 强转）→ OFFLINE →（重新 publish，唯一复活通道）→ PUBLISHED`。
- **置顶 `pinned`**：**只有 pinned=true 的公告进首页导航栏公告行**（强触达位）；不置顶
  只在公告中心（我的页入口）。发布新公告默认问用户要不要置顶；数据更新公告惯例 pinned=true。
- **自动下线 `offlineAt`**：MANUAL 公告建议显式设置（SYSTEM 数据更新公告按
  ops-config `auto_offline_hours` 默认 24h 自动过期）——新功能/运营公告长期 pinned 会
  持续霸占首页（2026-09-08 新功能公告实证：设 72h）。校验：必须晚于 now 且晚于 publishAt。
- **字段约束**：title ≤50 字；content = markdown 原文（≤50KB，towxml 渲染）；
  `CreateAnnouncementRequest = {title, content, category, pinned?, publishAt?, offlineAt?}`。

## 正文写法（markdown 契约）

- **段落分隔 = 空行**：markdown 单换行会被合并成同段；要分行显示必须段间空行。
- **门店可点击链接 = `[店名](venue://<venueId>)`**：归一化为详情页路径，towxml 渲染成
  navigator 直达（能力见 docs/agents/34「正文链接」）。
- **链接白名单**：`venue://<id>` 与 `/pages/...` 绝对路径放行；http(s)/外链一律降级纯文本
  （navigator 不能跳外链，留链 = 点击报错）——公告正文**禁放外链**。
- 分组标题可选「新增门店/恢复营业/状态更新」；仅一类门店时只列存在的分组。
- 大批量（≥10 家跨多城）按城市分组渲染（`**城市名**（N 家）` + 组内逐店列表）。

## 🎮 可点击演示（2026-09-08 起，新功能/运营公告的可选能力）

**适用**：需要用户"上手试试"的新功能公告（首例 = 微信自动提醒）。公告详情页会把
正文里内嵌的 ```` ```qw-demo ```` 演示块渲染成**可真实点击的演示区**（纯原生组件，
详见前端 docs/agents/34「可点击演示区块」）。**数据更新公告不带演示。**

**写法**：在正文叙述**之后**追加一段 fenced 代码块（语言标记 `qw-demo`），块内 = 一行
紧凑 JSON（演示剧本，线性 scene）。前端在交 towxml 前抽取：正文拆两段照常渲染、
演示区插在块所在位置。**为什么放叙述后**：未升级老版本客户端没有抽取逻辑，会把整块
渲染成灰底 JSON 代码块——放正文后面，老用户跳过它仍能读完正文。

**剧本字段白名单**（只允许下列原子与字段，前端 schema v1 强校验，多余字段/未知原子/
非法 JSON/版本≠1 → 整块静默失效 = 老版本灰块效果，不崩页面）：

| 原子 | 字段 | 说明 |
|---|---|---|
| `venue_row` | name*、status*、statusTone?(open/closed/suspended/renovating/ceased)、location?、distance?、changed?、signal?、favorite? | **门店列表项 1:1 复刻**（2026-09-09 新增）。唯一交互 = 店名右边的收藏星，点一下翻本地态并自动推进；永不真收藏 |
| `toggle_row` | label*、desc?、on?(初始开=true) | 开关行；打开后自动推进下一幕 |
| `explain_card` | lines*(1~6 行)、title?、tag? | 说明卡（如额度语义） |
| `mock_push` | title*、desc*、meta?、tone?(accent/success/warning) | 模拟微信服务通知推送卡 |
| `cta` | text*、toast?、target?({type:page/tab,url}) | 动作按钮；target 限 `/pages/...`（tab 仅 index/me），外链会被剥 |

**红线**：①演示是 mock——**永不真弹系统授权**（wx.requestSubscribeMessage 等真实
能力不在演示里发生），真实动作靠 `cta` 收敛到真实页；②文案须让用户知道"这只是演示"
（组件头栏已常驻「模拟操作 · 不会真的发送或改动数据」）；③每幕可带 `note`（≤120 字）
做操作引导。

**怎么引导用户动手**：演示的第一步必须让用户知道点哪里——剧本文案用 `note`
（≤120 字）写明具体位置（如「先点店名右边的星星，把它收藏」），并在正文演示块
**之前**用一句加粗引导（「先点门店名右边的**星星**把它收藏」）。第 1 幕优先用
`venue_row` 复刻真实列表项，别用抽象开关——用户在公告里认得出"列表里那张卡"，
演示可信度才成立。

**微信自动提醒示例剧本**（2026-09-09 公告 #22 实发版本，直接改字段复用）：

- `mock_push.desc` 支持 `\n` 分行（组件 `.ad-push-desc` 已声明 `white-space: pre-line`），
  服务通知按「内容行 + 时间行」两段写，两条信息挤一行会黏连。
- `cta.target.url` 里的门店 id **必须核实存在**（`GET /venues/{id}` 返回 200；2026-09-09
  实测生产库 id 12/31 均「场所不存在」）——拿不准就**不写 target**，只留 toast，
  跳转失败虽只回落 toast，但假 id 是脏数据。

````markdown
```qw-demo
{"v":1,"title":"微信自动提醒 · 可点击演示","scenes":[{"atom":"venue_row","name":"寻梦缘","status":"营业中","statusTone":"open","location":"成都·武侯区 · 人民南路四段","distance":"1.2km","signal":"15:20 · 6人报过 · 舞友上报","favorite":false,"note":"先点店名右边的星星，把它收藏（仅演示，不会真的收藏）"},{"atom":"explain_card","tag":"状态变化","title":"这家店从「营业中」变为「暂停营业」","lines":["舞厅营业时间常临时变动，每次变化我们都会记下来。","变化那一刻，已收藏的你会收到微信服务通知。","在微信里就能看到，不用反复打开小程序刷。"]},{"atom":"mock_push","meta":"服务通知 · 去舞厅","title":"营业变更","desc":"寻梦缘 · 暂停营业\n2026年9月9日 15:40","tone":"warning","note":"↑ 这就是你在微信里收到的样子"},{"atom":"cta","text":"去真实开启","toast":"门店详情页 → 收藏后点「微信提醒」","note":"以上均为模拟，不会真的发送微信"}]}
```
````

**发布前自查**：JSON 合法（python json.load 过一遍）；`"v":1`；scenes 1~12 幕；
字段全在白名单内；演示块在正文叙述后；cta 不要真跳才发布——跳转失败只回落 toast。
**🚨 红线：演示块是内部 DSL，绝不能让用户看到源码。** 客户端任一步解析失败（非法 JSON /
未知原子 / v≠1 / 超长 / 双块）以及**老版本客户端不认识新原子**，都会走"静默丢弃演示块"，
正文保持完整可读——这是刻意设计。所以：**带演示块的公告必须先确认演示能力已在已发布的
客户端版本里**，否则老版本用户只看到"没演示的干净正文"（不会看到 JSON，但演示白做）。
**改 `.ts` 后必须回拷 `.js`**（小程序跑 `.js`，`utils/*.js` 入库为运行时真相）：
`npx tsc -p tsconfig.json --noEmit false --outDir /tmp/tscAll` 后只 cp 本次涉及的文件；
`npx tsc --noEmit` 有既存错误会让开发者工具编译不出最新产物 → 先清干净再验。
**本地零依赖验证（推荐，比自查靠谱）**：用项目已编译抽取器跑一遍正文——
`cd quwuting/miniprogram/utils && node -e "const m=require('./announcementDemo.js');const r=m.extractAnnouncementDemo(require('fs').readFileSync('/tmp/xxx.md','utf8'));console.log(r.spec?'OK '+r.spec.scenes.length:'FAIL null')"`，
`spec=null` 说明剧本不会被渲染（多半是字段越界/未知原子/v≠1）→ 演示块被静默丢弃，改到 OK 再发。
**预览给用户看效果**：`quwuting/docs/previews/announcement-demo-wx-notify.html`
（单文件、token 1:1 还原 + 微信服务通知真实外壳，用户点开即可体验，改剧本时同步它）。

## 📋 数据更新公告固定模板（每次写库后发 DATA_UPDATE 一律用此模板）

标题：`{M}月{D}日舞讯更新`（category=DATA_UPDATE，pinned=true）

正文（markdown，店名一律可点击跳详情）：

```
【新增门店 {N} 家】
- [门店名](venue://<venueId>)（城市·区县）
- …

【恢复营业 {M} 家】
- [门店名](venue://<venueId>)（城市·区县，可附状态/备注）
- …

以上门店点击可直接查看详情，营业状态以现场实际情况为准。
```

## 工作流（四步）

### Step 1 登录 + 防重检查

1. 取 token（见上）。
2. `GET /admin/announcements?page=0&size=10`：列出 id/status/category/pinned/publishAt/title。
   - **今日已有 DATA_UPDATE 且本次也是数据更新**：信息不全（本轮新增门店未含）→
     二选一：①`POST /admin/announcements/{id}/update` **原地更新正文**（PUBLISHED 可全字段
     编辑并即时生效；publishAt 锁定不传新值；title/content/category/pinned 同请求体必填
     回传旧值 + 新正文）；用户口径「更新非重发」，**不要 offline+create 重发**。②用户要求
     完整重发才 offline 旧条再 create。
   - **OFFLINE / 昨日及更早的公告**：无碍，直接 create+publish。
3. 无重复 → 走 Step 2。

### Step 2 撰写（按场景）

- 数据更新 → 固定模板（见上），门店列表逐条 `venue://` 链接。
- 新功能 / 运营通知 → **面向零基础用户**：极简口语化、禁专业术语、短句为主（新功能类
  总字数 ≤100 字）、突出用户价值原话、禁长篇说明。标题可带 🎉 等 emoji 增加辨识度。
- **🚨 功能类公告必须交代「怎么开启」的具体操作路径（2026-09-08 用户反馈，公告 #22
  二次更新实证）**：不能只讲价值不说怎么做——用「①方式一 / ②方式二」+ 分步有序列表
  写清每一步点哪里；**路径必须对照真实 UI 核实**（菜单渲染条件/开关前置依赖要写进
  步骤，如详情页「微信提醒」仅站内开关开启后显示 → 路径须先写「打开营业状态通知」；
  步骤里的 UI 元素名用「」标出，与界面文案逐字一致）。
- **markdown 美化惯例**：加粗核心价值句（**…**）；操作步骤用有序列表（1. 2. 3.）；
  两种方式用 ①② 编号小标题；段间空行分段（towxml 单换行合并）。
- 正文写完先给用户过目（除非用户已给定文案），确认后进入 Step 3。

### Step 3 create + publish

```bash
curl -s -X POST "$BASE_URL/admin/announcements/create" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"title":"...","content":"...","category":"NOTICE","pinned":true,"offlineAt":"2026-09-11T20:00:00"}'
# → data.id；再 POST /admin/announcements/{id}/publish（body 可空 = 立即发布）
```

- 缺省 `publishAt` = 存草稿不发布，须再调 publish；**"创建即发布"两步必须连做**，
  只 create 不 publish 用户看不到。
- 中文 body 用 python/heredoc 构造 JSON（`ensure_ascii=False`），勿手工拼 shell 引号。
- offlineAt 格式 `yyyy-MM-ddTHH:mm:ss`（LocalDateTime）。

### Step 4 汇报

- 汇报：公告 id、status=PUBLISHED、publishAt、offlineAt（自动下线时刻）、置顶与否。
- 提醒：pinned 公告会出现在首页导航栏公告行；不想要了可
  `POST /admin/announcements/{id}/offline` 手动下线，或等 offlineAt 到点自动下线。

## 接口速查表（全部 requireAdmin，Bearer 鉴权）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /web-auth/password-login | 登录换 JWT（body: {username, password}） |
| GET | /admin/announcements?page=&size= | 公告列表（防重检查） |
| POST | /admin/announcements/create | 创建公告（body: {title, content, category, pinned?, publishAt?, offlineAt?}） |
| POST | /admin/announcements/{id}/publish | 发布（缺省立即发布；OFFLINE 复活同语义） |
| POST | /admin/announcements/{id}/update | 原地更新（PUBLISHED 全字段即时生效，publishAt 锁定；补充门店用，勿重发） |
| POST | /admin/announcements/{id}/offline | 手动下线 |

统一响应包 `{code, message, data}`，code=0 成功。401 = token 过期，重新登录。

## 常见问题

- **只 create 忘 publish**：公告停留 DRAFT，用户端不可见——两步连做（见 Step 3）。
- **update 报错**：PUBLISHED 态 update 时 title/content/category/pinned 均为必填
  （回传旧值），漏传会校验失败；publishAt 传入新值会被忽略/校验拦截，不要传。
- **PUBLISHED update 会清空 offlineAt（2026-09-08 #23 实证）**：update 请求体若不回传
  `offlineAt`，已设的自动下线时间会被置 NULL（公告变长期有效）——**只要还想自动下线，
  update 必须把原 offlineAt 一并回传**；不需要自动下线才留空。
- **offlineAt 设了过去时间**：`1001 自动下线时间必须晚于当前时间`——用未来时刻。
- **OFFLINE 态想改文案**：禁改（update 只对 DRAFT/PUBLISHED 生效）；重新 publish
  进入新发布周期后再 update。
- **正文链接点击报错**：大概率放了外链（已降级纯文本不会报错）或 `venue://` id 不存在
  ——发布前核对 venueId 真实性（`GET /venues/{id}` 200 才可用）。
- **同日两条 DATA_UPDATE**：历史上靠 offline 旧条解决（如 #18→#19 合并）；优先用
  update 原地补充，避免用户在公告中心看到同日两条。
