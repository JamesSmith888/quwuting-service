---
name: quwuting-venue-daily-sync
description: 去舞厅（quwuting）每日舞讯采集与门店维护工作流。当需要从舞讯源（默认砂舞线报网 xianbao360）或用户提供的非结构化文本中提取「当日营业门店」信息，与平台门店比对，列出未录入新店（一键录入）与可更新状态门店（状态反转）时使用。核心四步：采集舞讯 → 按量拉取平台门店候选 → 城市+名称+地址比对 → 差异清单 + 用户确认后写库。
agent_created: true
---

# 去舞厅 · 每日舞讯采集与门店维护

## 背景 / 为什么需要每日维护

去舞厅是「出门前查舞厅」场景：用户核心任务是「这家今天营业吗」。舞厅营业状态
**每天变化**（当天有舞会/没舞会、临时休息、停业恢复），舞讯源（砂舞线报网等）
**每日发布当日营业清单**——「门店时效性基本是一天当天的」（用户明确口径，无当天
才可放宽）。平台状态不跟进就过期（真实事故：菲琳/玫瑰天堂快照 CEASED 而平台已
OPEN，用户投诉「明明营业却显示停业」）。

本 Skill = 对话式自动化维护闭环：Agent 采集 → 比对 → 差异清单 → **用户确认** →
写库（新增门店 + 状态反转）。后端已有 Web 管理后台的「门店同步」链路（报告 + 人工确认），
但该链路不会新增门店；本 Skill 负责新店录入 + 状态保鲜，采集/比对全程 Agent 自身能力
（WebFetch + LLM），不依赖任何运维脚本。

## 🚫 红线（最高优先，违反即事故）

1. **生产环境操作需用户明确确认**：后端地址默认本地 `http://localhost:8080`
   （develop）。只有用户明确说「用生产/线上/正式环境」才可切
   `https://api.starseek.online`，且**写库动作（新增/反转）必须用户逐项确认后执行**。
2. **时效性**：舞讯优先用**当天**发布的文章；无当天才放宽到最近日期（并在汇报中
   注明实际报告日期，防止把旧舞讯当新数据写库）。
3. **状态更新单向保守**：只做「资讯 OPEN + 平台 CEASED/SUSPENDED → OPEN」的反转
   （舞讯是营业名单，未上榜 ≠ 停业，**严禁**把营业中的门店标停业/休息）。低置信
   匹配（CONTAINED/FUZZY）默认不反转，列人工复核。
4. **新增门店保守**：只建 name+city（舞讯能提供的基础信息），不编造地址/电话/时段/
   照片等字段。批量建档的精细化信息留人工补全。
   ⚠️ **例外（2026-09-06 滁州 15 家实证）**：用户直接贴**完整表格**（含地址/营业时间/
   状态列）时 = 表格信息即用户提供的可信数据，**不是 Agent 编造**——按 Step 4B 全量
   建档（name/city/district/address/status + 营业时间一次补全），反而不要只建 name+city
   把用户给的信息丢掉。判别 = 输入形态：零星店名 vs 完整多列表格。
5. **幂等不重复**：新增前必查候选库确认「同城同名不存在」；写库后如实汇报
   CREATED/EXISTED/FAILED 明细。

## 🔄 双位置同步约定（2026-09-07 用户要求，最高优先）

本 Skill 存在**两份副本**，任何一份有变动都必须保持同步，**Git 侧以项目路径为准**：

- **项目路径（Git 管理，源文件）**：`/Users/xin.y/WeChatProjects/quwuting-service/quwuting-venue-daily-sync/`
- **用户路径（WorkBuddy 运行时加载）**：`~/.workbuddy/skills/quwuting-venue-daily-sync/`

**同步规则（每次会话开始时 + 每次改动后执行）**：
1. 会话开始：`diff` 两边三件套（SKILL.md / scripts/qw_api.py / reference/*-dict.json），
   以**较新的一方**覆盖另一方（通常项目路径更新，正向拷贝项目 → 用户级）。
2. 会话中改动 SKILL.md / 字典 / 脚本后：**先写项目路径，再整体拷贝到用户路径**
   （`cp -R`，排除 .DS_Store / __pycache__），保证 Git 侧始终是权威版本、可提交。
3. 判别新旧：比对字典 JSON 的 `updated` 字段 + 文件 mtime；无法判断时问用户。

## 前置条件

- 后端地址：`BASE_URL`（本地 http://localhost:8080；切生产前必须问用户）
- 管理凭据：`WEB_ADMIN_PASSWORD` 环境变量对应的账号密码（后端 `web-auth.username`
  默认 admin；本地起服务时在 shell 导出 `WEB_ADMIN_PASSWORD`）。Skill 通过
  `POST /web-auth/password-login` 换 JWT，再以 Bearer 调 `/admin/**`。
- 辅助脚本：`scripts/qw_api.py`（Python3 标准库，无第三方依赖）——登录/导出/新增/
  状态反转的 HTTP 封装；也可以直接用 curl（见接口速查表）。

## 工作流（四步 + 确认）

### Step 1 采集舞讯（当日优先）

**方式 A（默认）· xianbao360 网页**：
1. 访问归档页 `https://www.xianbao360.com/archives/date/YYYY/MM`（当前年月），
   找到**发布日期 = 今天**的文章（标题形如「全国砂舞厅开门营业最新消息-2026.09.01」）。
   无当天文章才取最近一篇，并记录实际报告日期。
2. WebFetch 抓取文章正文。正文特征：按「城市词 + 门店名列表」行式组织，行内以
   空白/分号/冒号/逗号分隔；`{晚}/{早午}` = 场次标记，`*` = 近期调整较大，
   `（中）（西）` = 城市内分区。**页脚/JS/统计脚本混入时只取正文部分**。
3. 提取为结构化清单 `[{city, name, note}]`：city = 标准城市名（「成都市」而非
   「成都」——对齐平台 `GET /venues/cities` 的词表）；name = 门店名（保留粘连
   token 原样，如「乐8新艺城」，比对阶段再拆）；note = 场次/标记等原始信息。

**方式 B · 用户提供非结构化数据**：用户给聊天记录/文本/图片文字时，LLM 提取
同样的 `[{city, name, note}]` 清单，并逐条标注来源（用户原话/哪个群/哪张截图），
提取存疑条目单独列出问用户。

> 🚫 **不依赖 quwuting-ops 的 Python 管线脚本**（2026-09-01 用户明确）：采集/解析/提取
> 一律用 Agent 自身能力（WebFetch + LLM），**禁止**调用 `quwuting-ops/venue-opening` 的
> `main.py` / adapter / matcher。后端状态写库也直接调 `/admin/**` 接口（不走管线 CLI）。

### Step 2 拉取平台门店候选（按量加载）

1. 登录：token 获取顺序 = `--token` 参数 / `ADMIN_TOKEN` 环境变量 / **`/tmp/qw_token.json`
   缓存（跨会话有效；⚠️ `qw_api.py` 不自动读缓存——须 `ADMIN_TOKEN=$(python3 -c
   "import json;print(json.load(open('/tmp/qw_token.json'))['token'])")` 或 `--token`
   传入，且变量名必须是 `ADMIN_TOKEN`（写 `TOKEN=` 无效，2026-09-06 实测），401 才
   重新 login（`/tmp/qw_login.py` 远程读生产 web-auth 换新 token）**。token 有效性
   一句话验证：`curl -H "Authorization: Bearer $TOKEN" $BASE_URL/admin/venue-sync/reversals?limit=1`。
2. 拉取候选，**两档路径（2026-09-04 实证）**：
   - **覆盖城市 ≤5**：按城市逐个拉取 `--city 成都市`（一城一页足够，totalElements 超
     页内数按 page 递增）；
   - **覆盖城市 >5（全国级舞讯常态）**：**直接不带 city 全量翻页**（size=500，page
     0/1/…拉完，平台现量 ~1000 家两页完事）——export 自带 `id ASC` 稳定排序，全量翻页
     安全；20+ 城时逐城请求纯属浪费。⚠️ 全量拉取后用 `GET /venues/cities`（公开）核对
     平台词表，**平台未覆盖城市的舞讯条目直接剔出比对集**（归表④存档），切勿混入邻近
     城市比对（2026-09-04 险些把滁州 4 家错归马鞍山）。
   ⚠️ **候选拉取必须稳定排序**：export 接口自带 `id ASC`（稳定，首选）；若降级用公开
   `GET /venues`，**必须带 `sort=newest`**——默认 recommended 排序无 id tie-break，
   热度分相同（多为 0）时翻页漂移会漏门店（2026-09-01 实测成都 107 家翻页漏 26 家，
   导致 10 家「假新店」误判）。
3. 建内存索引：`city → [(venueId, name, district, address, status)]`。
   顺带用 `GET /venues/cities`（公开，无需登录）核对城市名是否为平台标准词表。

### Step 3 比对（城市 + 名称 + 地址，规则 + 语义）

对每条舞讯记录，在**同城候选**内比对（同名不同城的店是两家店，必须同城过滤）：

> 🔑 **数据源匹配字典优先查阅（2026-09-01 起）**：比对前先查数据源对应字典
> `reference/<数据源>-venue-dict.json`（如 `reference/xianbao360-venue-dict.json`）。
> 字典条目 = 历次执行沉淀的「舞讯店名 → 平台门店」映射（错别字/异体字/后缀/粘连/已确认
> 包含关系），命中即按字典标注的 kind 定置信度（typo/variant/suffix/exact → 高置信 ALIAS；
> contained → 低置信；unmatched_resolved → 新店候选），**优先于规则层判定**；同城同名但
> 字典未收录的新案例，比对后**回写字典**（本 Skill 的经验沉淀机制，防同类重复误判）。
> ⚠️ **DICT_MAP 必须脚本化构建（2026-09-04 教训）**：比对脚本直接 `json.load` 字典文件、
> 按 `(city, news_name)` 自动查映射——**禁止把字典条目手工抄写进比对脚本**（手抄必漏，
> 2026-09-04 漏掉「喜见→芭黎人歌舞厅（喜见）」导致误入 UNMATCHED）。舞讯名与字典
> news_name 不全等时按「基础名前缀/包含」匹配（字典「喜见（喜见音乐烤吧）」须命中舞讯
> 「喜见」）。**字典命中失败 ≠ 平台没有**：先按条目 `venue_id`（新条目必带此字段）查
> 现状——平台名可能已改带别名后缀或整店改名（2026-09-04 蓝迪/蓝波湾两例），查到即
> 用新名更新映射。推荐实现：**两段式脚本**——match 脚本（舞讯结构化+字典映射+规则匹配）
> 结果落盘 JSON，analyze 脚本再深挖（反转候选/CONTAINED 明细/UNMATCHED 兜底），互不重跑。
> 字典中 removed_duplicates 段 = 用户确认删除的重复数据，命中即跳过（不再建库/反转）。
> 字典中 uncertain_entries 段（2026-09-02 起）= 用户不确定/存疑的映射，含
> tentatively_mapped（暂按映射处理）/ misprint（攻略疑似写错）/ unverified（未核实）
> 三类，附 reason + next_action；命中此段的条目按 next_action 复核，不重复误判。

| 置信度 | 判定规则 | 动作 |
|---|---|---|
| EXACT | 归一化名称全等，或去通用后缀后全等（「蓝堡」=「蓝堡舞厅」） | 高置信 |
| ALIAS | 明显别名/简称/错别字（「蓉城大舞厅」=「成都蓉城舞厅」、平台与舞讯一字之差） | 高置信 |
| CONTAINED | 包含/被包含关系（「枫亚」⊂「枫亚之夜歌舞厅」）或粘连 token 拆分（「乐8新艺城」=「乐8量贩ktv」+「新艺城」） | 低置信，人工复核 |
| UNMATCHED | 同城无任何候选命中 | 新店候选 |

比对要点：
- 名称归一化：去空白/全角空格、小写、全角括号转半角、剥通用后缀
  （量贩ktv/歌舞厅/演艺大舞厅/音乐舞厅/娱乐厅/交谊舞厅/交谊舞/大舞厅/音乐茶楼/音乐吧/
  歌舞城/大众舞厅/舞厅/酒吧/俱乐部/club/ktv）。
  ⚠️ 「交谊舞」须单独成后缀（2026-09-01 实测漏剥导致「永乐交谊舞」被判 CONTAINED
  而非 EXACT——「永乐交谊舞」=「永乐舞厅」实为同一家）。
- LLM 语义兜底：错别字、谐音、简称、方言名——规则无法覆盖时用语义判断，但必须
  在汇报里给出「为什么认为是同一家」的依据，低置信一律归 CONTAINED。
- 地址维度：舞讯带地址时与候选 address/district 交叉验证（同城同名但地址迥异的
  两家店，不能因同名就命中——以地址为准降置信）。
- 每个 opening 允许多命中（粘连 token），取置信度最高者为主命中，其余列备注。
- **UNMATCHED 快速兜底（2026-09-04 实证 8/10 归位）**：对 UNMATCHED 条目用「首二字
  子串 + 长度差 ≤6」搜同城候选——错字/异体/括号别名（金卡乐→金卡罗、星曜乐→星耀乐、
  玛莎→富都汇（玛莎嗨嗨）、随圆→随园）基本一搜一个准；归位后按 typo/variant/alias
  回写字典，别急着进表③。
- **短前缀空格勿拆**：「es 星海壹号」「ls丽莎」「M莎莎」类「字母/数字前缀 + 中文」
  之间空格通常是同一家店的前缀标记，按一家处理（两种写法都录入匹配，同命中一家即合并）。
- **同城命名家族模式**：南宁「XX舞汇」系（舞讯写短名 乐舞/欢舞/海角/天涯/天籁…）等
  家族模式下短名⊂长名是常态，批量 CONTAINED 属预期（多为已 OPEN 进表④，不逐条问）。
- **removed_duplicates 命中先查正本**（2026-09-04 实证）：当时删的是重复份，正本可能
  仍在库且 OPEN（长沙 913/919/934）——先搜同城同名，有正本按正常流程处理，无正本才跳过。

### Step 3.5 存疑门店联网核实（2026-09-02 起，UNMATCHED 录入前防"假新店"）

UNMATCHED 条目标记为「新店候选」前，若用户要求或对门店真实性存疑，先**联网核实**
（2026-09-02 靠此法把成都摩尔顿/绵阳蓝迪/重庆五七 3 家"舞讯新名"识破为平台已有
门店，避免重复建库）。高效数据源组合（按优先级）：
1. **砂舞指南站 shawudaohang.com**（最准）：站内含门店别名/曾用名/营业时间/今日
   营业状态（如「享达娱乐音乐酒吧 别名:蓝迪音乐茶吧」）——WebSearch 站内或抓页；
2. **腾讯地图 POI**（搜索"<店名> 舞厅/音乐茶吧"常在结果末附坐标+评分+营业时段，
   判断平台候选缺地址的门店真身）；
3. **xianbao360 地址汇总页**（archives/183 等"xx砂砂舞舞厅地址和导航汇总"）——
   成都/西安等城市的门店地址权威来源。

核实结论类型 → 字典回写：
- **非新店**（舞讯名=平台店 错别字/异体/别名/改名）→ kind `typo`/`alias`/`renamed`，
  不建库，仅更新平台店（改名/补地址由用户确认后走 updateVenue）；
- **确为新店**（拿到确切地址/坐标）→ 表③ 正常录入，`resolved` 回写。
- 核实后无任何线索 → 保留 `uncertain_entries`（status=unverified + reason +
  next_action），宁可不录不误录。

### Step 4 表格化差异清单 + 用户确认（本 Skill 的强制环节）

把 Step 3 结果整理为**简洁对比表格**（对话内渲染：HTML 交互表格或 markdown 均可），
按「写库动作」分类四张表，用户浏览确认后**直接执行**（可合并确认，但不得跳过确认写库）。

**表格通用规范**：每表 ≤5 列（列名对齐平台字段口径）；**空表不渲染**（该类无条目时
一句话说明即可）；行数多时表格内只列关键字段，venueId 等机器字段折叠进明细。

| # | 分类 | 内容 | 列（≤5） | 确认交互 |
|---|---|---|---|---|
| ① | **可直接更新**（100% 确定） | EXACT/ALIAS 命中 + 舞讯 OPEN + 平台 CEASED/SUSPENDED（would_reverse） | 舞讯店名 \| 城市 \| 平台门店 \| 平台当前状态 \| 置信度 | 用户回复「执行」即全部反转，**不逐项确认** |
| ② | **需用户确认**（低置信） | CONTAINED 命中 + would_reverse（需 forceReversal 放行） | 舞讯店名 \| 城市 \| 候选门店 \| 匹配依据 \| 平台当前状态 | **逐条确认/剔除**，确认的才 forceReversal=true |
| ③ | **平台未维护**（新店候选） | UNMATCHED + keyword 交叉验证同城确无同名 | 舞讯店名 \| 城市 \| note/地址 \| keyword 验证 \| 建议动作 | 用户回复「录入」即 batch-create 一键录入，可剔除个别 |
| ④ | **参考信息**（无需动作） | 命中但平台已 OPEN（状态一致）；CONTAINED 且平台 OPEN；平台未覆盖城市（仅舞讯零星点名，无完整表格） | 舞讯店名 \| 城市 \| 平台状态/说明 | 仅展示不写库，供掌握全貌 |

> ⚠️ **表④「平台未覆盖城市」≠ 一律不建库（2026-09-06 修订）**：09-04 先例是舞讯零星
> 点名、平台无该城 → 剔出归表④。**但用户若直接贴该城完整表格（多列：名称/状态/地址/
> 营业时间）则意图 = 建档**，走 **Step 4B** 城市首覆建档（滁州 15 家实证），勿再归表④
> 不建库。判别主键 = 输入形态：零星店名 vs 完整表格。

分类主键 = **would_reverse 与置信度**：高置信（EXACT/ALIAS）+ 反转 → 表①；
低置信（CONTAINED）+ 反转 → 表②；UNMATCHED → 表③；其余全部 → 表④。
（无 would_reverse 需求的命中，如平台已 OPEN，一律归表④，避免噪音。）

🚨 **舞讯与用户实况冲突（2026-09-04 密他先例）**：舞讯点名营业但用户确定当天关门时，
**以用户为准、不写库**，把该条从反转清单剔除并记入字典 `uncertain_entries`
（reason 注明「舞讯与实况冲突以用户为准」+ next_action「下次出现先问用户实况再反转」）——
舞讯源不是真理，用户实况优先。

用户确认后执行：

- 表① 状态反转：
  `python3 scripts/qw_api.py status-reverse --items 'JSON数组' --base-url <BASE_URL>`
  items 元素 `{"venueId","reportDate","sourceId","status":"OPEN","confidence"}`。
  ⚠️ **批量更新标识**：status-reverse 自动注入 `"source":"AGENT_BATCH"`（2026-09-01 V8：
  状态日志 change_source 列），管理后台「更新记录」据此展示「批量更新」标签，与
  管理端人工写库（ADMIN）区分。可用 `--change-source <值>` 覆盖。
- 表② 状态反转（用户逐条放行的条目）：
  同上，条目追加 `"forceReversal":true`。
- 表③ 新店录入：
  `python3 scripts/qw_api.py batch-create --items 'JSON数组' --base-url <BASE_URL>`
  items 元素 `{"name","city","district?","address?","status?"}`。同城同名已存在会
  返回 EXISTED（幂等兜底，正常现象——Step 3 已过滤，多因归一化差异）。
  ⚠️ **请求体是 `{"items":[...]}` 包装不是裸数组**（2026-09-07 实测）：绕过
  qw_api.py 直接 curl 时若发裸数组，后端反序列化失败返回 code=5000（非 400，
  具有迷惑性）——优先走 qw_api.py 封装，脚本已正确包装。
  **后端语义**：仅「资讯 OPEN + 平台 CEASED/SUSPENDED」反转，其余静默跳过；
  审计 changedBy=null（Agent 来源），关注者自动收站内信、缓存自动失效。
  ⚠️ **录入后补全技巧（2026-09-02 实测）**：batch-create 只建基础字段
  （name/city/district/address/status），**营业时段/经纬度不落库**——需要时另走
  `POST /venues/{id}/update`（body=CreateVenueRequest 全量字段：name/status/city/
  district/address 必须回填现值 + `businessHours:[{name,open,close}]` + longitude/
  latitude），否则会把已建字段覆盖成 null。businessHours 跨天契约：
  close < open = 次日凌晨（如 19:00-04:00 原样存取）。
  ⚠️ **详情 GET 响应嵌套（2026-09-06 首轮 15 家全 FAIL 教训）**：`GET /venues/{id}`
  的字段在 `data.venue` 子对象，不在 data 顶层——回填前取值取错层会读到空
  name/city，触发「城市不能为空」类 400，浪费时间；正确 = `cur = data["venue"]`。
  整店带营业时间的完整建档走 Step 4B，勿在本步重复造轮子。

> **数据更新公告联动（2026-09-01 全局公告系统，docs/agents/34）**：batch-create
> 新增成功 / status-reverse 反转成功会触发后端自动生成「数据更新公告」（SYSTEM 来源，
> 同日防重，一天一条）。开关 `announcement.data_update.enabled` 默认 false——
> 未开启时不产生公告（写库无感知）；若用户已开启，写库后小程序端会看到
> 「今日舞讯更新」公告，属预期行为，汇报时可顺带提及（新增 N 家 / 恢复 M 家）。

### Step 4B 新增门店 · 城市首覆完整表格建档（专用最优路径，2026-09-06 滁州 15 家实证，用户验证通过）

**适用情形**：用户**直接贴完整门店表格**（含 门店名称/状态/公告/营业时间/地址 多列），
且该城不在平台词表（`GET /venues/cities` 无）或平台该城零门店。这**不是**舞讯文本
零星点名场景（零星点名只建 name+city，见红线 4）；**完整表格 = 用户建档授权**，可
一次性全量建档。路径按序执行可一次成功，勿走舞讯比对四表（表①②③④）绕圈。

1. **城市词表核对 → 判定首覆**：`GET /venues/cities`（公开）无该城 → 不带 city 全量
   `export` 翻页（size=500）确认该城零门店 → 判定「城市首覆」。⚠️ **勿把新城市店并入
   邻近城市**（滁州≠马鞍山，2026-09-04 险些错归）；建库城市用标准词表格式「滁州市」。
   ⚠️ **关键字命中 ≠ 该城已覆盖（2026-09-06 六城实战）**：首覆核查用店名/街道关键字
   搜全量，命中几乎全是**外城同名/近似店**（台州蓝堡系命中 6 城、鞍山检索命中「马鞍山」
   整城、大连帝豪/琳琳命中南通/青岛同名）——判别看命中行的 `city` 是否 = 目标城，
   **同城零命中才算首覆**；同名店勿混入（多城同名店列表在 Step 3 比对要点有「同名不同城
   是两家店」规则，建档同样适用）。连续多城批量建档时（用户连贴多张表格），**口径确认
   一次后同会话后续批次直接沿用**（状态映射/环境/名称照录），无需每批重问——但表格
   出现新状态值或新环境意图时仍须停下确认。
2. **状态口径先问用户（一次性确认，勿替拍板）**：完整表格状态列出现过的值 → 存储态映射
   （2026-09-06 六城 52 家实战，三种状态全遇过）：
   - 「营业中」→ **OPEN**；
   - 「今日停业/暂停营业」→ 需用户选，**推荐统一 SUSPENDED**（2026-09-06 用户拍板）——
     理由：①贴舞讯「今日停业/暂停」语义；②未来舞讯报恢复走 status-reverse 可自动
     OPEN；③CLOSED 不在批量反转范围（恢复须全量 update，维护负担重）。备选 CLOSED/
     按公告细分见下；
   - **「未到营业时间」→ 存储态必须 OPEN**（2026-09-06 鞍山舒馨/亿嘉园、长春万仁合/
     双赫实证）：这是平台**前端派生态**（NOT_OPEN_YET，仅 OPEN 且当前时刻不在营业时段
     内出现，见 miniprogram/utils/venueStatus.ts）——存储态填 OPEN + businessHours，
     时段外小程序自动显示「明天 X 开门」；填 SUSPENDED/CLOSED 会导致徽标语义错误
     （门店在营业列表消失）。**勿把「未到营业时间」当停业**。
   - 同一批表格状态混合（营业中+未到营业时间+今日停业）时逐店映射，不整批一刀切。
3. **batch-create 全量建档**：items = `{name（照录表格）, city（标准词表名）, district
   （从地址提取区县）, address（原文全量）, status}`。同城同名幂等返回 EXISTED 属正常。
4. **营业时间补全（必须，batch-create 不落时段）**：
   - 逐家 `GET /venues/{id}` → ⚠️ **字段嵌套在 `data.venue`，不是 data 顶层**（2026-09-06
     首轮 15 家全 FAIL「name/city 不能为空」即因此取值取空）；
   - `POST /venues/{id}/update`，body=CreateVenueRequest 全量：name/status/city/
     district/address **用 GET 现值回填**（防覆盖成 null）+ `businessHours:
     [{name:"", open, close}]`（单时段 name 空串；跨天 close<open=次日凌晨原样存取）。
5. **核验**：`GET /venues?city=<城>&size=30&sort=newest` 逐家核对 status/district/
   businessHours；`GET /venues/cities` 应出现该城。
6. **字典回写（防下次舞讯重复误判，必做）**：entries 增补建档映射（每店
   `{city, news_name=店名, platform_name=店名, venue_id, kind=exact}`）+ **历史舞讯
   短名家族 alias**（本次：澜/澜夜→澜夜酒吧、丽莎→丽莎舞厅酒馆、幻新→幻新酒吧、
   老炮→老炮酒吧——09-04 舞讯已报道过短名，直接解析不重判）；旧「未覆盖城市不建库」
   聚合条目 → kind=`resolved`，note 注明建档 venueId 区间。下次舞讯命中即定位，不再
   进新店候选。
7. **公告**：Step 6 询问；若今日已发 DATA_UPDATE 且不含这批，给三选项（offline 旧条
   后发完整版 / 另发专项 / 不发——新增多为停业态时可建议等恢复再报）。

### Step 5 汇报

- 汇总：采集日期/来源、舞讯条目数、EXACT/ALIAS/CONTAINED/UNMATCHED 分布、
  反转 N 家、新增 M 家、跳过 X（EXISTED/FAILED 明细）。
- 遗留：CONTAINED 待复核清单、FAILED 原因、数据存疑点——列给用户后续处理，
  不静默吞掉。

### Step 6 公告发布（2026-09-02 确立；2026-09-07 用户拍板改分级策略）

**分级策略（2026-09-07 用户明确，写进惯例）**：
- **公告已发但需补充门店**（2026-09-07 用户口径「更新非重发」）：直接
  `POST /admin/announcements/{id}/update` 原地更新正文（PUBLISHED 状态可全字段
  编辑并即时生效，publishAt 锁定；标题/分类/置顶同请求体必填回传），**不要**
  offline+create 重发新公告。
- **表①「可直接反转/确定的数据」写库成功后：无需询问，直接按固定模板
  create+publish 公告**（发布前仍先 `GET /admin/announcements` 核对今日是否已有
  DATA_UPDATE，仅当今日已发且信息不全才 offline 旧条后发完整版）。
- **表②逐条放行 / 表③新店录入 / 低置信或混合批次**等不确定数据写库后：**仍须
  主动询问用户**是否发公告（不确定数据的公告口径需用户把关）。

「数据更新公告」入口 = 全局公告系统（docs/agents/34，MANUAL 来源）。

- **后端接口已具备，无需新增**（2026-09-02 核查）：
  `POST /admin/announcements/create`（body: `{title, content, category: "DATA_UPDATE",
  pinned?}`）→ `POST /admin/announcements/{id}/publish`（缺省立即发布）。管理端
  Web 后台「公告管理」入口同链路。
- 询问话术：汇报完成后附「**是否发一条公告？**（标题如『X月X日舞讯更新』）」；
  用户确认后执行 create+publish（本地 develop 环境也照做，用户确认即视为授权）。
- 用户未确认 → 不创建公告（尊重「未要求不动」红线；公告是面向全体用户的对外动作）。

**📋 数据更新公告固定模板（2026-09-03 用户要求固化；每次写库后发公告一律用此模板）**

标题：`{M}月{D}日舞讯更新`（如「9月3日舞讯更新」，category=DATA_UPDATE，pinned=true）

正文（markdown，**店名一律可点击跳详情**——链接语法见下）：

```
【新增门店 {N} 家】
- [门店名](venue://<venueId>)（城市·区县）
- …

【恢复营业 {M} 家】
- [门店名](venue://<venueId>)（城市·区县，可附状态/备注）
- …

以上门店点击可直接查看详情，营业状态以现场实际情况为准。
```

- **门店可点击链接 = `[店名](venue://<venueId>)`**（2026-09-03 前端已支持，公告链接能力见
  docs/agents/34「正文链接」+ 小程序 `utils/announcementLinks.ts`）：归一化
  `venue://12` → 详情页路径，towxml 渲染为 navigator 直达；外链/未知协议自动降级纯文本。
  分组文案可选「新增门店/恢复营业/状态更新」；**若仅一类门店，只列存在的分组标题**；
  今日停业等非 OPEN 新增店也列出并在括号注明（如「苏州昆山，今日停业」）。
- **大批量新增（≥10 家跨多城）可按城市分组渲染**（2026-09-06 54 家实战，#19 公告）：
  `**城市名**（N 家）` 小标题 + 组内逐店 `- [店名](venue://id)（城市·区县[，状态]）`，
  链接格式不变；停业店统一注「，暂停营业」、OPEN 不注——比 50+ 行平铺更可读。
  城市小标题须用全称（滁州市）与门店括号内简称（滁州·来安县）并存不冲突。
- 同一天先前已发过的简版公告（无门店列表）会被本条完整公告**替代**——发布前先
  `POST /admin/announcements/{旧id}/offline` 下线旧条，避免同日重复/信息不全。
  2026-09-06 实证：下午 #18（2 新增+8 恢复）与晚间 52 家新店合并为 #19「新增 54 家+
  恢复 8 家」完整版，offline #18 → create #19 → publish，一天一条。
- **发布前先 `GET /admin/announcements?page=0&size=10` 扫一眼列表**（2026-09-04 实操）：
  看今天日期是否已有 DATA_UPDATE 公告——仅当今日已发且信息不全（status=PUBLISHED）
  才需 offline 替换；status 取值 DRAFT/PUBLISHED/OFFLINE，OFFLINE 与昨日 PUBLISHED
  均无碍，直接 create+publish。

### 接口速查表（全部 requireAdmin，Bearer 鉴权）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /web-auth/password-login | 登录换 JWT（body: {username, password}） |
| GET | /venues/cities | 平台城市词表（公开） |
| GET | /admin/venue-sync/venues/export?city=&status=&page=&size= | 候选门店按量加载（size≤500，轻量字段） |
| POST | /admin/venue-sync/venues/batch-create | 批量新增门店（同城同名幂等） |
| POST | /admin/venue-daily-openings/batch | 批量状态反转（后端 DailyOpeningService 权威语义） |
| GET | /admin/venue-sync/reversals?limit= | 更新记录（本次反转可在其中核验） |
| POST | /admin/announcements/create | 创建数据更新公告（MANUAL，Step 6 用） |
| POST | /admin/announcements/{id}/update | 原地更新公告（PUBLISHED 全字段即时生效，补充门店用，勿重发） |
| POST | /admin/announcements/{id}/publish | 发布公告（缺省立即发布） |

统一响应包 `{code, message, data}`，code=0 成功。401 = token 过期，重新登录。

## 常见问题

- **export 一页装不下**：size 上限 500，按 page 递增拉完；Skill 场景一城一页足够。
- **城市名对不上**：舞讯常用简称（「蓉」「渝」「杭」），先映射到平台标准城市名
  （`GET /venues/cities` 词表）再查候选——城市不匹配必然 UNMATCHED。
- **同城同名判重兜底**：batch-create 服务端归一化判重，返回 EXISTED 属预期
  （Step 3 漏判或名称归一化差异），不视为错误，如实汇报即可。
- **怀疑「假新店」**：UNMATCHED 条目标记为新店候选前，若平台该城门店较多，用
  `GET /venues?keyword=<店名>` 交叉验证一次（keyword 匹配 name/address/description，
  能搜到同名校对但未进候选说明候选拉取有遗漏——检查排序稳定性或翻页完整性）。
  ⚠️ **keyword 参数必须 URL 编码**（中文直接拼 URL 会 400 Bad Request）：curl 需
  `--data-urlencode` 或手工 percent-encode；推荐用 Python
  `urllib.parse.urlencode({'keyword': 店名, 'page': 0, 'size': 20, 'sort': 'newest'})`
  （2026-09-01 实测「缤达→宾达舞厅」错别字即靠此法验证）。
- **反转没生效**：后端只反转 CEASED/SUSPENDED → OPEN；平台已 OPEN 的条目静默跳过
  （正确行为，不是 bug）。
- **CLOSED 门店恢复营业（2026-09-04 帝境路径）**：CLOSED 不在批量反转范围（后端仅
  CEASED/SUSPENDED→OPEN），须走 `POST /venues/{id}/update` 全量回填（body=CreateVenueRequest，
  防 businessHours/经纬度被清成 null）。**全字段来源 = 公开接口 `GET /venues/{id}`
  （无需 token）**——返回 name/status/city/district/address/longitude/latitude/
  businessHours 全量现值，把 status 换成 OPEN 后原样回填，再核验字段无丢失。
- **status-reverse HTTP 500**：`confidence` 必须用后端枚举值 **EXACT/ALIAS/CONTAINED/FUZZY**，
  传 `HIGH`/`HIGH_CONFIDENCE` 等自定义值会 500（2026-09-02 实测；表① 用 EXACT，
  表② 用 CONTAINED+forceReversal=true）。
- **双源交叉验证**（2026-09-02 起推荐）：用户给多份舞讯（如长文本 + 公众号）时，
  先同城合并去重，`sources=AB`（双源命中）比单源高置信；单源条目仍按规则处理但
  在汇报标注来源。粘连 token（「蓝月千里缘」=蓝月+千里缘、「虹光天天和」=虹光+天天和、
  「食品华新」=食品+华新、「金笑夜色」=金笑+夜色）按长文本拆分后再比对。
- **字典 uncertain_entries 段**（2026-09-02 用户要求主动维护）：用户「不确定但暂按
  映射处理」或「攻略疑似写错」的条目，除 entries 里登记映射外，同时写入
  `uncertain_entries`（status: tentatively_mapped / misprint / unverified + reason +
  next_action），后续舞讯出现时按 next_action 复核，避免重复误判。
- **重复写库**：同一门店被重复提交（如与 Web 后台「门店同步」同日处理）时，状态反转
  以最后执行为准（applyBatch 幂等早退：已 OPEN 的条目静默跳过）；新增以 batch-create
  同城同名判重兜底（EXISTED），无锁冲突风险。
