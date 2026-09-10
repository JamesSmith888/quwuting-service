---
name: quwuting-venue-daily-sync
description: 去舞厅（quwuting）每日舞讯采集与门店维护工作流。当需要从舞讯源（默认砂舞线报网 xianbao360）或用户提供的非结构化文本中提取「当日营业门店」信息，与平台门店比对，列出未录入新店（一键录入）与可更新状态门店（状态反转 / 白名单口径置暂停）时使用。核心四步：采集舞讯（含覆盖城市集合）→ 按量拉取平台门店候选 → 城市+名称+地址比对（开门/关门双方向）→ 差异五表 + 全源一致数据直接写库、冲突/单源数据人工核实。
agent_created: true
---

# 去舞厅 · 每日舞讯采集与门店维护

> **本文件 = 红线 + 当前口径 + 操作步骤**（2026-09-10 拆分瘦身）。
> 历轮实证细节、踩坑复盘、沿革全部下沉到 `reference/`——需要「为什么会这么做」时按下表查，
> **不要**把实证细节再写回本文件（主文件保持可一眼扫完）。

## 📚 参考文档索引（按需查，不是必读）

| 文档 | 内容 | 什么时候查 |
|---|---|---|
| `reference/matching-playbook.md` | 名称归一化/置信度/匹配兜底/别名域/字典机制；Step 2-3 的实操细节与踩坑 | 比对归位不准、UNMATCHED、别名回写时 |
| `reference/close-direction-playbook.md` | 关门方向（白名单）范围细化细则 + 历轮实证数字 | 算 `toSuspend`、暂停规模异常时 |
| `reference/build-playbook.md` | 城市首覆建档、营业时间回填、批量建档口径 | 用户贴完整表格要建档时 |
| `reference/announcement-playbook.md` | 公告分级策略沿革、模板、大批量渲染、发布前检查 | 发公告前 |
| `reference/troubleshooting.md` | 常见问题/接口坑/错误码/幂等语义明细 | 遇到报错或行为不符预期时 |
| `reference/run-history.md` | 历轮跑批实录 + 口径变更沿革（09-01 → 09-10） | 想确认某条口径是哪一轮定的 |
| `reference/xianbao360-venue-dict.json` | 数据源匹配字典（entries / uncertain_entries / removed_duplicates） | 比对前必查、写库后回写 |

## 背景 / 为什么需要每日维护

去舞厅是「出门前查舞厅」场景：用户核心任务是「这家今天营业吗」。舞厅营业状态**每天变化**
（当天有舞会/没舞会、临时休息、停业恢复），舞讯源**每日发布当日营业清单**——「门店时效性
基本是一天当天的」（用户明确口径）。平台状态不跟进就过期（真实事故：菲琳/玫瑰天堂快照
CEASED 而平台已 OPEN，用户投诉「明明营业却显示停业」，详见 `run-history.md`）。

本 Skill = 对话式自动化维护闭环：Agent 采集 → 比对 → 差异五表 → 分级执行（全源一致直接写、
其余人工核实）。后端 Web 后台的「门店同步」链路不会新增门店；本 Skill 负责新店录入 + 状态
保鲜，采集/比对全程用 Agent 自身能力（WebFetch + LLM），**不依赖任何运维脚本**。

## 🚫 红线（最高优先，违反即事故）

1. **生产环境操作需用户明确确认**：后端默认本地 `http://localhost:8080`（develop）；只有用户
   明确说「用生产/线上/正式环境」才可切 `https://api.starseek.online`（环境切换本身必须用户明说）。
   环境确定后，写库动作按下方「多来源确认门」分级执行。
2. **时效性**：舞讯优先用**当天**发布的文章；无当天才放宽到最近日期，并在汇报中注明实际
   报告日期（防把旧舞讯当新数据写库）。
3. **状态双向 · 白名单口径（2026-09-10 拍板，推翻旧「单向保守」）**——舞讯 = 当日营业白名单：
   - **开门方向**：舞讯点名营业、平台为 CEASED/SUSPENDED → 反转为 OPEN。
   - **关门方向**：**舞讯点名覆盖的城市**内、**未上榜**且平台为 **OPEN** 的门店 → 置 **SUSPENDED**。
   - 🔒 **城市边界是硬约束**：只对「该城确有门店名单」的城市做关门推断，**未覆盖城市一律不动**；
     舞讯里只有城市名却无名单的（「未有待更新」「待定」「无商家信息」）**视同未覆盖**。
     服务端不做城市推断（分不清「未上榜」与「该城没被覆盖」），**范围一律由 Skill 侧算好再提交**。
   - **非 OPEN 状态不参与关门方向**（CEASED/CLOSED/RENOVATING 保持原状，防「降级」）。
   - **守卫条目（uncertain_entries / removed_duplicates）两个方向都豁免**。
   - 低置信（CONTAINED/FUZZY）不参与任何方向，列人工复核。
   - 代价：舞讯某日漏报某店 → 该店被误标暂停。故**提交前必须自检城市集合与 S 分布**，
     并汇报暂停规模（预期 40–80 家/轮；几百家 = 口径失效，回头查）。
4. **新增门店保守**：默认只建 name+city，不编造地址/电话/时段/照片。
   ⚠️ **例外**：用户直接贴**完整表格**（含地址/营业时间/状态列）= 用户提供的可信数据，按
   `build-playbook.md` 全量建档（别把用户给的信息丢掉）。判别 = 输入形态：零星店名 vs 完整表格。
5. **幂等不重复**：新增前必查「同城同名不存在」；写库后如实汇报 CREATED/EXISTED/FAILED 明细。

## 🔄 双位置同步约定（2026-09-07 用户要求，最高优先）

本 Skill 存在**两份副本**，任何改动都必须保持同步，**Git 侧以项目路径为准**：

- **项目路径（源文件，可提交）**：`/Users/xin.y/WeChatProjects/quwuting-service/quwuting-venue-daily-sync/`
- **用户路径（运行时加载）**：`~/.workbuddy/skills/quwuting-venue-daily-sync/`

**同步规则**：
1. 会话开始：`diff` 两边全部文件（SKILL.md / scripts/ / reference/），以**较新一方**覆盖另一方
   （应包含 `reference/` 下所有文档，不只是三件套）。
2. 改动后：**先写项目路径，再整体 `cp -R` 到用户路径**（排除 `.DS_Store` / `__pycache__`），
   保证 Git 侧始终是权威版本。
3. 判别新旧：比对字典 JSON 的 `updated` 字段 + 文件 mtime；无法判断时问用户。

## 前置条件

- 后端地址 `BASE_URL`（本地 http://localhost:8080；切生产前必须问用户）。
- 管理凭据：`WEB_ADMIN_PASSWORD` 对应的账号密码（后端 `web-auth.username` 默认 admin），走
  `POST /web-auth/password-login` 换 JWT，再以 Bearer 调 `/admin/**`。
- 辅助脚本 `scripts/qw_api.py`（Python3 标准库，零依赖）：`login` / `export` / `cities` /
  `batch-create` / `status-reverse` / `status-suspend`。

## 工作流（采集 → 比对 → 五表 → 分级执行）

### Step 1 采集舞讯（当日优先）

**方式 A（默认）· xianbao360 网页**
1. 归档页 `https://www.xianbao360.com/archives/date/YYYY/MM`（当前年月）找**发布日期 = 今天**
   的文章（标题形如「全国砂舞厅开门营业最新消息-2026.09.01」）；无当天才取最近一篇并记录实际报告日期。
2. WebFetch 抓正文（按「城市词 + 门店名列表」行式组织；`{晚}/{早午}` = 场次标记、`*` = 近期调整大、
   `（中）（西）` = 城市内分区）。页脚/JS/统计脚本混入时只取正文。
3. 提取为 `[{city, name, note}]`：city = 平台标准城市名；name 保留粘连 token 原样（比对阶段再拆）；
   note = 场次/标记。
4. **额外产出「覆盖城市集合」`coveredCities`（关门方向的唯一依据）**：只把**确有门店名单**
   （城市名下至少 1 个门店名）的城市计入；城市名出现但内容为「未有待更新/待定/无商家信息/空白」
   的**一律不计入**。多源取各源覆盖的**并集**。
   ⚠️ **城市名必须先归一（去「市」）再比较**——A 源「成都」/ B 源「成都市」不归一会让同一城
   变成两个 key、集合求交为 ∅，把**双源误判成单源**（静默失败、不报错）。细则见
   `matching-playbook.md § 城市名归一`。

**方式 B · 用户提供非结构化数据**：LLM 提取同样的 `[{city, name, note}]`，逐条标注来源
（用户原话/哪个群/哪张截图），存疑条目单独列出问用户。

> 🚫 **不依赖 quwuting-ops 的 Python 管线脚本**（2026-09-01 用户明确）：采集/解析/提取一律用
> Agent 自身能力（WebFetch + LLM），**禁止**调用 `quwuting-ops/venue-opening` 的 `main.py` /
> adapter / matcher；状态写库直接调 `/admin/**`。

### Step 2 拉取平台门店候选

1. **登录**：token 顺序 = `--token` / `ADMIN_TOKEN` 环境变量 / `/tmp/qw_token.json` 缓存。
   ⚠️ `qw_api.py` **不自动读缓存**，且变量名必须是 `ADMIN_TOKEN`（写 `TOKEN=` 无效）。401 再 login。
   有效性一句话验证：`curl -H "Authorization: Bearer $TOKEN" $BASE_URL/admin/venue-sync/reversals?limit=1`。
2. **拉取候选，两档路径**：
   - 覆盖城市 **≤5**：逐城 `--city 成都市`；
   - 覆盖城市 **>5**（全国级舞讯常态）：**不带 city 全量翻页**（`size=500`，page 递增；平台现量
     ~1000 家两页完事，export 自带 `id ASC` 稳定排序）。⚠️ 全量拉后用 `GET /venues/cities`
     核对词表，**平台未覆盖城市的舞讯条目直接剔出比对集**（归表④），切勿混入邻近城市。
3. 建索引 `city → [(venueId, name, district, address, status, aliases)]`——**export 自带 `aliases`**，
   是舞讯名归位的权威运行时数据，必须带上。

> 为什么必须稳定排序、翻页漂移事故、keyword 编码坑 → `troubleshooting.md`。

### Step 3 比对（城市 + 名称 + 地址 + 别名，规则 + 语义）

对每条舞讯记录在**同城候选**内比对（同名不同城 = 两家店，必须同城过滤）。判定优先级：

1. **平台别名域最高优先**：舞讯名 ∈ 同城候选 `aliases` → 直接判高置信 EXACT 级。
2. **数据源匹配字典**：查 `reference/<数据源>-venue-dict.json`（**脚本化 `json.load` 查表，禁止手抄**），
   命中即按 kind 定置信度。
3. **规则层**（下表）+ **LLM 语义兜底**（错别字/谐音/简称/方言；低置信一律归 CONTAINED）。
4. **UNMATCHED 兜底**（两道，见 `matching-playbook.md`）：首二字子串 + 长度差 ≤6；
   **形近字规则**（同城 + 等长 + 末字同 + 候选唯一）——漏判代价是该店被误当新店建档。

| 置信度 | 判定规则 | 动作 |
|---|---|---|
| EXACT | 归一化名称全等，或去通用后缀后全等 | 高置信 |
| ALIAS | 明显别名/简称/错别字（一字之差） | 高置信 |
| CONTAINED | 包含/被包含，或粘连 token 拆分 | 低置信，人工复核 |
| UNMATCHED | 同城无任何候选命中 | 新店候选 |

**比对要点（精简版，细则见 `matching-playbook.md`）**
- 归一化：去空白、小写、全角括号转半角、剥通用后缀（含「交谊舞」需单独成后缀）。
- 地址维度：舞讯带地址时与候选 address/district 交叉验证，地址迥异不能因同名就命中。
- 粘连 token / 短前缀空格 / 同城命名家族 / 「原X」更名线索 / 多源错别字差异 → 见 playbook。
- 守卫条目：`removed_duplicates` 命中先查正本（正本可能仍在库且 OPEN）；`uncertain_entries`
  按 `next_action` 复核，不重复误判。

### Step 3B 关门方向比对（白名单差集）

算「谁没上榜」——**只对 `coveredCities` 内的城市做**，未覆盖城市完全不处理（硬边界）：

1. 逐城取该城**全部门店**（不限状态）→ `platformSet`；
2. `mentionedSet` = 该城在覆盖该城的各源中被点名、且已归位到平台 venueId 的门店；
3. 差集 `toSuspend = platformSet − mentionedSet`，过闸门：**仅保留 status = OPEN**、剔除守卫条目、
   剔除冲突条目；
4. **冲突判定（M/S 判定，核心）**：令 M = 点名该店的源集合、S = 覆盖该店所在范围的源集合：
   - `M == S` → **确定开门** → 表①（平台 CEASED/SUSPENDED 则反转）；
   - `M == ∅` → **确定关门** → 表⑤（平台 OPEN 则置暂停）；
   - `0 < |M| < |S|` → **源间冲突** → 表②（两个方向都不自动写）；
   - `|S| < 2` → **单源覆盖** → 表②（保留「单源不写库」红线）。
5. **范围细化（写库前必做）**：`coveredCities` 是城市名级粗集合，直接按它取「该城全部门店」会误伤。
   收敛规则：**县级市/县独立判定**（未报到就不纳入，不随母城）、**市辖区随母城**、
   **母城被「按区枚举」时只认枚举的区**（枚举名对不上真实 district 时退回整城）。
   → 细则 + 历轮实证数字见 `close-direction-playbook.md`。
6. **提交前自检**：城市逐个核对确有名单；打印「暂停 N 家 + 城市分布」**与各城 S 分布**
   （某批城市 S 集体退化成单元素 = 城市名口径没归一，属静默失败，只能靠这行发现）。
7. ⚠️ **单源覆盖城市永不自动写库是常态、不是 bug**：两源覆盖城市表天然不同（A 源 ~29 城 /
   B 源 ~135 城），只有 B 覆盖的城（南昌/南宁/东莞/深圳/青岛/郑州…）永远 |S|=1。汇报时必须
   显式说明「本轮 N 个城因单源覆盖未做任何自动写库」。
8. **0 恢复是常态，不要硬凑**：平台 OPEN 通常已与近期舞讯同步，出现「反转 0 / 新增 0」属正常，
   别为凑公告内容硬找动作。
9. **口径修正后可直接重跑整批**：`batch-suspend` 与 `status-reverse` 都幂等（非目标状态静默跳过），
   两方向互不干扰、可放心重跑；想让汇报口径干净再自行 diff 上一批 ID。

### Step 3.5 存疑门店联网核实（UNMATCHED 录入前防「假新店」）

UNMATCHED 标记为「新店候选」前，若存疑先联网核实。数据源优先级：
`砂舞指南站 shawudaohang.com`（最准，含别名/曾用名/今日营业状态）→ `腾讯地图 POI` →
`xianbao360 地址汇总页`。结论回写：非新店 → kind `typo`/`alias`/`renamed` 不建库；
确为新店 → 表③ 录入 + `resolved`；无线索 → 保留 `uncertain_entries`（宁可不录不误录）。

### Step 4 表格化差异清单 + 分级执行（强制环节）

把 Step 3 / 3B 结果整理为**简洁对比表格**，按「写库动作」分类五张表。**空表不渲染**
（无条目时一句话说明）。每表 ≤5 列，venueId 等机器字段折叠进明细。

| # | 分类 | 判定 | 执行 |
|---|---|---|---|
| ① | **可直接更新 · 开门** | `M == S` + EXACT/ALIAS + 平台 CEASED/SUSPENDED | **直接执行**反转，写库后直发公告 |
| ② | **需用户核实**（冲突/单源/低置信，**且需动作**） | 冲突 / 单源 / CONTAINED **且平台为 CEASED/SUSPENDED** | 列「管理员手动核实」清单，逐条放行后才写 |
| ③ | **平台未维护**（新店候选） | `M == S` + UNMATCHED + keyword 交叉验证同城确无同名 | 全源一致**直接 batch-create**；单源新店进人工清单 |
| ④ | **参考信息**（无需动作） | 命中但平台已 OPEN；未覆盖城市的零星点名 | 仅展示不写库 |
| ⑤ | **可直接暂停 · 关门** | `coveredCities` 内 + `M == ∅` + 平台 OPEN（守卫/冲突已剔除） | **直接执行** status-suspend（**不发公告**） |

> 🔑 **表② 必须加「需要动作」闸门（2026-09-10 实证）**：只有平台为 CEASED/SUSPENDED 的
> 冲突/单源/低置信条目才进人工清单；**平台已 OPEN 的一律归表④**——列进去只是噪音
> （不加此闸门，清单会从 4 条膨胀到 136 条，用户无法扫读）。判断口径同 `would_reverse`。

> ⚠️ **表④「平台未覆盖城市」≠ 一律不建库**：若用户直接贴该城**完整表格**（多列）则意图 = 建档，
> 走 Step 4B，勿归表④。

> 🚨 **多来源确认门（2026-09-09 拍板；2026-09-10 收紧为「全源一致」）**：写库门槛 = **至少 2 个源
> 覆盖该城，且覆盖该城的源结论完全一致**（分流规则同 Step 3B 冲突判定）。某源**没有该城名单
> = 弃权**，不计入 S、也不构成反对。低置信（CONTAINED）即使全源命中也仍进人工清单。
> 守卫条目优先级高于本门。

**分类主键** = 全源一致度 × 方向 × 置信度 × 是否需要动作：
- `M==S` + 高置信 + 平台为停业/暂停 → **表①**（直接执行）；
- `M==∅` + 覆盖城市内 + 平台 OPEN → **表⑤**（直接执行）；
- 冲突 / 单源 / CONTAINED → **表②**（人工核实）；
- `M==S` + UNMATCHED → **表③**（直接建档）；单源 + UNMATCHED → 人工清单；
- 其余（平台已 OPEN、未覆盖城市、守卫条目）→ **表④**。

🚨 **舞讯与用户实况冲突 → 以用户为准、不写库**（密他先例），并**回写字典**：
用户对表② 条目定性后逐条 upsert `uncertain_entries`——确认停业 → `status: "user-confirmed-closed"`
+ reason + next_action「下次再点名先与用户确认实况」；确认在营 → 写 entries + `status-reverse`。
**不回写就会下一轮重复问同一个问题。**

**执行命令**（表①/③/⑤ 直接执行；表② 用户逐条放行后执行）：

- **表① 开门反转**：`python3 scripts/qw_api.py status-reverse --items 'JSON数组' --base-url <BASE_URL>`
  items = `{"venueId","reportDate","sourceId","status":"OPEN","confidence"}`。
  自动注入 `"source":"AGENT_BATCH"`（V8 `change_source` 列，后台「更新记录」展示「批量更新」标签，
  与人工 ADMIN 区分），可 `--change-source` 覆盖。后端仅反转 CEASED/SUSPENDED → OPEN，其余静默跳过。
- **表② 反转**：同上，条目追加 `"forceReversal":true`。
- **表⑤ 关门暂停**：`python3 scripts/qw_api.py status-suspend --items 'JSON数组' --report-date YYYY-MM-DD --base-url <BASE_URL>`
  items 只需 `{"venueId"}`（脚本补 reportDate/sourceId/source）。
  后端 `POST /admin/venue-daily-openings/batch-suspend`：仅 **OPEN → SUSPENDED**，非 OPEN 与不存在
  静默跳过；审计 changedBy=null + changeSource=AGENT_BATCH；关注者收站内信/订阅消息（大批量时
  消息量较大，属既有语义不是 bug）；**刻意不产生数据更新公告**。返回 `{total, suspended, venueNotFound, details}`。
  ⚠️ **城市范围必须由 Skill 侧算好**再提交，混入未覆盖城市会误伤。
- **表③ 新店录入**：`python3 scripts/qw_api.py batch-create --items 'JSON数组' --base-url <BASE_URL>`
  items = `{"name","city","district?","address?","status?"}`。同城同名返回 EXISTED 属正常。
  ⚠️ 请求体是 `{"items":[...]}` 包装（裸数组会 5000 报错）；batch-create **不落营业时段/经纬度**，
  需补走 `POST /venues/{id}/update` 全量回填（详见 Step 4B / `build-playbook.md`）。

**别名回写（写库确认后的固定动作）**：本轮确认的 typo / alias / variant / renamed 映射，
先灌平台别名字段再回写字典：

```
POST /admin/venue-aliases/batch-import
{"items": [{"venueId": 434, "alias": "蜀尔顿"}, ...]}
```

- 逐条幂等（skipped / 软删行复活 / 单条失败不拖整批）；别名与主名同名自动跳过。
- **新别名 = 双写**：先平台 alias 域（运行时权威），再本 Skill 字典 entries（经验沉淀）。两层缺一不可。
- 🚫 **主名括号禁令**：新增/更名一律禁止把别名写进主名括号——主名保持干净，别名走 `qwt_venue_aliases`
  （`updateVenue` 全量回填时同样注意：name 只放主名）。

> **数据更新公告联动**：batch-create / status-reverse 会触发后端自动生成「数据更新公告」
> （SYSTEM，同日防重）；开关 `announcement.data_update.enabled` 默认 false，未开启不产生公告。

### Step 4B 城市首覆 · 完整表格建档（专用最优路径）

**适用**：用户**直接贴完整门店表格**（含名称/状态/公告/营业时间/地址多列），且该城不在平台词表
或该城零门店。**完整表格 = 用户建档授权**，可一次性全量建档；路径按序执行可一次成功，
**勿走五表绕圈**。要点：

1. `GET /venues/cities` 无该城 + 全量 export 确认该城零门店 → 判定「城市首覆」
   （⚠️ 关键字命中 ≠ 该城已覆盖，判别看命中行 `city` 是否 = 目标城）。
2. **状态口径先问用户（一次性确认，勿替拍板）**：「营业中」→ OPEN；「今日停业/暂停营业」→
   推荐 **SUSPENDED**；**「未到营业时间」→ 存储态必须 OPEN**（前端派生态，勿当停业）。
   混合状态逐店映射，不整批一刀切。
3. `batch-create` 全量建档（name 照录表格 / city 标准词表名 / district 从地址提取 / address 原文全量）。
4. **营业时间补全（必须）**：逐家 `GET /venues/{id}`（⚠️ 字段在 `data.venue` 子对象）→
   `POST /venues/{id}/update` 全量回填（现值防 null + `businessHours`）。
5. 核验 → 6. 字典回写（含历史舞讯短名家族 alias）→ 7. 公告按 Step 6 询问。

> 完整步骤、踩坑（首覆核查/状态映射/字段嵌套）见 `build-playbook.md`。

### Step 5 汇报

- 汇总：各源实际报告日期、**`coveredCities`**、舞讯条目数、EXACT/ALIAS/CONTAINED/UNMATCHED 分布、
  反转 N 家、新增 M 家、**暂停 K 家（含城市分布）**、跳过 X（EXISTED/FAILED 明细）。
- 遗留：表② 人工核实清单、FAILED 原因、数据存疑点——列给用户，不静默吞掉。
- **单源覆盖未写库的城市数必须显式说明**（否则用户以为漏跑）。
- 暂停方向单列一行：「本批暂停 K 家（覆盖城市 A/B/C…），未覆盖城市未做任何推断」，
  让用户一眼核对城市边界。

### Step 6 公告发布

**分级策略（09-07 分级 → 09-09 公告分开制 → 09-10 全源一致口径）**：
- **表①（开门）/表③（新店）全源一致数据写库成功后：无需询问，直接按固定模板 create+publish**；
  发布前先 `GET /admin/announcements` 核对今日是否已有 DATA_UPDATE（已发且信息不全才 offline 旧条）。
  ⚠️ 全源一致但平台已 OPEN（当日零写库）时**不发公告**——无正向内容，发了是噪音。
- **表⑤（关门/暂停）刻意不发公告**：公告口径是「新增/恢复」的正向信息，数百家转暂停对外发布
  是纯噪音且引发恐慌；暂停只写库 + 通知关注者。
- **公告已发后再补充门店 → 新发一条独立公告**（勿改写已发布公告）；原地 update 仅用于修正原公告
  自身错误/口径。「一天一条完整公告」口径已作废，同日可并存多条 DATA_UPDATE。
- **表② 放行 / 单源新店录入 / 混合批次**等不确定数据写库后：**仍须主动询问用户**是否发公告。

**📋 固定模板**（标题 `{M}月{D}日舞讯更新`，category=DATA_UPDATE，pinned=true）：

```
【新增门店 {N} 家】
- [门店名](venue://<venueId>)（城市·区县）

【恢复营业 {M} 家】
- [门店名](venue://<venueId>)（城市·区县，可附状态/备注）

以上门店点击可直接查看详情，营业状态以现场实际情况为准。
```

- 门店可点击链接 = `[店名](venue://<venueId>)`（前端已支持，towxml 渲染为 navigator 直达）。
  仅一类门店时**只列存在的分组标题**；今日停业的新增店也列出并注明。
- **大批量（≥10 家跨多城）可按城市分组**：`**城市名**（N 家）` 小标题 + 组内逐店列出。
- **零新增/零恢复时的可选形态（09-10 用户拍板）**：改列「**全源一致确认营业中**」的门店
  （按城市分组、可点击），标题不变；首句写「以下 N 家门店经两个来源共同确认今日营业中」，
  结尾「营业状态随时变动，以现场实际情况为准」。
  ⚠️ 口径必须写「两源共同确认」，**不能**写成「全部营业门店」（与「未上榜即暂停」自相矛盾）。

> 沿革、大批量渲染与发布前检查清单见 `announcement-playbook.md`。

### 接口速查表（全部 requireAdmin，Bearer 鉴权）

| 方法 | 路径 | 用途 |
|---|---|---|
| POST | /web-auth/password-login | 登录换 JWT（body: {username, password}） |
| GET | /venues/cities | 平台城市词表（公开） |
| GET | /admin/venue-sync/venues/export?city=&status=&page=&size= | 候选门店按量加载（size≤500，轻量字段 + aliases） |
| POST | /admin/venue-sync/venues/batch-create | 批量新增门店（同城同名幂等） |
| POST | /admin/venue-daily-openings/batch | 批量状态反转（停业/暂停 → 营业） |
| POST | /admin/venue-daily-openings/batch-suspend | 批量置暂停营业（白名单口径，仅 OPEN→SUSPENDED） |
| POST | /admin/venue-aliases/batch-import | 别名批量导入（幂等，单条失败不拖整批） |
| GET | /admin/venue-sync/reversals?limit= | 更新记录（本次反转可核验） |
| POST | /admin/announcements/create | 创建数据更新公告 |
| POST | /admin/announcements/{id}/publish | 发布公告（缺省立即发布） |
| POST | /admin/announcements/{id}/offline | 下线公告（同日替换旧条用） |

统一响应包 `{code, message, data}`，code=0 成功。401 = token 过期，重新登录。

## 常见问题（高频 5 条；其余见 `troubleshooting.md`）

- **城市名对不上 / 该城整城没写库**：舞讯常用简称，先映射到平台标准词表；**多源比较前必须去「市」归一**。
- **反转/暂停没生效**：后端只处理 `CEASED/SUSPENDED → OPEN` 与 `OPEN → SUSPENDED`，其余静默跳过
  （正确行为）。若「该暂停的没暂停」，先查它是否被误算进 mentionedSet（别名没归位）或该城不在 `coveredCities`。
- **怀疑「假新店」**：`GET /venues?keyword=<店名>` 交叉验证一次；⚠️ kwyword 参数必须 URL 编码
  （中文直接拼会 400），推荐 `urllib.parse.urlencode(...)`。
- **CLOSED 门店恢复营业**：CLOSED 不在批量反转范围，须 `POST /venues/{id}/update` 全量回填
  （字段来源 = 公开 `GET /venues/{id}`，`status` 换 OPEN 后原样回填，防字段被清 null）。
- **门店删除/清理重复**：后端**无门店删除接口**（项目禁 PUT/DELETE）——Agent 识别到同名同址重复
  条目时**不代删、不自动反转**，呈「疑似重复」交用户决策；字典 `removed_duplicates` 仅登记用户已删确认的店。
