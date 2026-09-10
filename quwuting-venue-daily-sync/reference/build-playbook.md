# 新店建档细则（build-playbook）

> 由 SKILL.md Step 4B 迁出的完整步骤与踩坑。**保守建档红线（默认只建 name+city）以 SKILL.md 红线 4 为准**。

## 适用情形

用户**直接贴完整门店表格**（含 门店名称/状态/公告/营业时间/地址 多列），且该城不在平台词表
（`GET /venues/cities` 无）或该城零门店。这是「零星点名只建 name+city」的**例外**：完整表格 =
用户建档授权，可一次性全量建档。**判别主键 = 输入形态：零星店名 vs 完整多列表格。**

首覆实证：2026-09-06 滁州 15 家（用户验证通过）；同日六城 52 家连贴。

## 步骤

### 1. 城市词表核对 → 判定「城市首覆」

- `GET /venues/cities`（公开）无该城 → 不带 city 全量 `export` 翻页（size=500）确认该城零门店。
- ⚠️ **勿把新城市店并入邻近城市**（滁州 ≠ 马鞍山，2026-09-04 险些错归）；建库城市用标准词表格式
  （「滁州市」）。
- ⚠️ **关键字命中 ≠ 该城已覆盖（2026-09-06 六城实战）**：首覆核查用店名/街道关键字搜全量，命中
  几乎全是**外城同名/近似店**（台州「蓝堡」系命中 6 城、鞍山检索命中「马鞍山」整城、大连帝豪/琳琳
  命中南通/青岛同名）——判别看命中行的 `city` 是否 = 目标城，**同城零命中才算首覆**。
- 连续多城批量建档（用户连贴多张表格）时，**口径确认一次后同会话后续批次直接沿用**
  （状态映射/环境/名称照录），无需每批重问；但表格出现**新状态值或新环境意图**时仍须停下确认。

### 2. 状态口径先问用户（一次性确认，勿替拍板）

2026-09-06 六城 52 家实战，三种状态全遇过：

| 表格状态列 | 存储态 | 依据 |
|---|---|---|
| 营业中 | **OPEN** | — |
| 今日停业 / 暂停营业 | **SUSPENDED**（推荐，09-06 用户拍板） | ①贴舞讯「今日停业/暂停」语义；②未来舞讯报恢复走 status-reverse 可自动 OPEN；③CLOSED 不在批量反转范围（恢复须全量 update，维护负担重）。备选 CLOSED / 按公告细分 |
| **未到营业时间** | **必须 OPEN** | 平台**前端派生态**（NOT_OPEN_YET，仅 OPEN 且当前时刻不在营业时段内出现，见 `miniprogram/utils/venueStatus.ts`）——存储态填 OPEN + businessHours，时段外小程序自动显示「明天 X 开门」；填 SUSPENDED/CLOSED 会导致徽标语义错误（门店在营业列表消失）。**勿把「未到营业时间」当停业** |

同一批表格状态混合时**逐店映射，不整批一刀切**。

### 3. batch-create 全量建档

items = `{name（照录表格）, city（标准词表名）, district（从地址提取区县）, address（原文全量）, status}`。
同城同名幂等返回 EXISTED 属正常。

### 4. 营业时间补全（必须，batch-create 不落时段/经纬度）

- 逐家 `GET /venues/{id}` → ⚠️ **字段嵌套在 `data.venue` 子对象，不是 data 顶层**——2026-09-06 首轮
  15 家全 FAIL（「name/city 不能为空」）即因取值取空。正确写法：`cur = data["venue"]`。
- `POST /venues/{id}/update`，body = `CreateVenueRequest` **全量**：`name / status / city / district /
  address` 用 GET 现值回填（防覆盖成 null）+ `businessHours: [{name:"", open, close}]`
  （单时段 name 空串）。
- **businessHours 跨天契约**：`close < open` = 次日凌晨（如 `19:00-04:00` 原样存取）。

### 5. 核验

`GET /venues?city=<城>&size=30&sort=newest` 逐家核对 status/district/businessHours；
`GET /venues/cities` 应出现该城。

### 6. 字典回写（防下次舞讯重复误判，必做）

- entries 增补建档映射：每店 `{city, news_name=店名, platform_name=店名, venue_id, kind=exact}`；
- **历史舞讯短名家族 alias**（滁州实证：澜/澜夜→澜夜酒吧、丽莎→丽莎舞厅酒馆、幻新→幻新酒吧、
  老炮→老炮酒吧——09-04 舞讯已报道过短名，直接解析不重判）；
- 旧「未覆盖城市不建库」聚合条目 → `kind=resolved`，note 注明建档 venueId 区间。
  下次舞讯命中即定位，不再进新店候选。

### 7. 公告

按 SKILL.md Step 6 询问；若今日已发 DATA_UPDATE 且不含这批，给三选项（offline 旧条后发完整版 /
另发专项 / 不发——新增多为停业态时可建议等恢复再报）。

## 与「零星点名建档」的边界

| 输入形态 | 建档口径 | 路径 |
|---|---|---|
| 舞讯零星点名（无地址/时段） | 只建 `name + city`（不编造其余字段） | Step 4 表③ |
| 用户贴**完整多列表格** | 全量建档（name/city/district/address/status + 营业时间） | 本文件（Step 4B） |
