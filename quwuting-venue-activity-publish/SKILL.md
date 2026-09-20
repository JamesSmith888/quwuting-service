---
name: quwuting-venue-activity-publish
description: 去舞厅（quwuting）门店营业活动发布工作流。当需要把老板海报、群消息、口述情报或舞讯里的门店优惠（门票优惠/买一送一/免门票/赠品/满减/套餐）整理成一条结构化活动、发布到小程序「门店详情页 · 营业活动卡」时使用。覆盖：活动 vs 公告/快讯/门店动态/门店状态的域分诊、三个结构化轴（外层调度 / 权益类别 / 核销方式）取值判据、跨夜时段契约、同城同名门店甄别、群渠道优惠 → 平台口令的合规转译、POST /admin/venue-activities/create 一步发布、无幂等键的重复发布防护、发布后双向核验与 update 全量覆盖语义、Skill 双副本同步纪律。
agent_created: true
---

# 去舞厅 · 门店营业活动发布（quwuting-venue-activity-publish）

领域权威文档（**先读，勿凭本 SKILL 猜口径**）：

- 后端（表结构 / 状态机 / 调度策略 / 接口契约）：`quwuting-service/docs/agents/49-venue-activities.md`
- 小程序端（展示层 / 合规边界 / 到店链路）：`quwuting/docs/agents/49-venue-activities.md`

本 Skill = 把**非结构化的门店优惠情报**（老板发的海报、群里转的消息、口头描述）
转成一条**结构化活动**，发布到小程序「门店详情页 · 营业活动卡」。

## ⓪ 运行前必做 · Skill 双副本同步（用户要求：每次跑 Skill 前先同步）

```bash
bash scripts/sync-skills.sh          # 在 quwuting-service 仓内执行
bash scripts/sync-skills.sh --check  # 只读检查差异（退出码 1 = 有差异）
```

权威方向 = **项目路径**（`quwuting-service/quwuting-*`，Git 可回溯）→ 运行时镜像
`~/.workbuddy/skills/`。脚本**单向镜像**（源 → 镜像）+ 覆盖前自动备份。

> **改名/新增 Skill 时同步改两处**：① 本仓 `quwuting-<name>/` 目录（源）；
> ② `scripts/sync-skills.sh` 的 `MANAGED_SKILLS` 数组（否则镜像不生成、也不会被检查）。
> **只改项目侧 = 本轮跑的还是旧契约**——本项目已有此漂移前科，故升格为红线。

⛔ **备份目录必须在 `skills/` 之外**（2026-09-17 实测事故）：备份若落在
`skills/.sync-backup/`，其 `<时间戳>/quwuting-xxx/SKILL.md` 与 Skill **目录同形**，
会被 Skill 扫描器一并收录 ⇒ **实际加载到的是备份里的旧稿**（实测调用
`quwuting-bulletin-publish` 命中 `.sync-backup/20260917-003155/` 的 139 行旧版，
而正式副本已是 257 行）。同步跑得越勤 ⇒ 备份越多 ⇒ 中招概率越高。
**勿再把备份改回 `skills/` 内**，现备份在 `~/.workbuddy/.skill-sync-backup/`。

## 🚫 红线（最高优先，违反即事故）

1. **渠道限定语必须转译，不能原样上屏**。源情报里出现「本群贵宾 / 群友专享 /
   粉丝价 / 前 50 名到店」这类**平台无法验证、平台用户无法兑现**的限定条件时：
   - 要么转成**平台口令**（`CODE_WORD`，用户报「去舞厅」即可享）——**须门店认可**；
   - 要么去掉限定、按通用口径发布（`OPEN_TO_ALL`，谁到店都能享，代价：**归因失效**）。
   ⛔ 原样发布（写"本群贵宾可享"）= 平台用户到店被拒 → 投诉落小程序主体。
   拿不准就**问用户**，不要替门店承诺。
2. **不编造 `platformAddon`**。平台加项是**门店与平台谈定的独家条款**（门店门口海报
   不会有这一条），它同时是"报口令产生实际差异"的证据。门店没给就不填 ——
   49 号明文「无加项时不渲染该块、也不补假加项」（内容真实性 > 视觉整齐）。
3. **合规话术**：过 20 号文档禁忌词表；不写「妹妹 / 精妹」等擦边称呼（源情报原话必须
   转译为「舞伴 / 人气 / 场内人多」）；不写脏话网络粗口；不写夸张不实断言。
4. **平台不背书**：文案里**不要**写"平台补贴/平台赠送/官方保障"之类。卡片底部的
   免责行（「活动由门店提供，以门店实际为准」）由**前端固定渲染**，文案不要重复它。
5. **发布即生产**：默认 `BASE_URL = https://api.starseek.online`。用户说「发布」=
   明确授权生产写库；但**部署类动作仍禁动**——接口 404 = 后端未发版，停下问用户，
   禁自行 deploy / 重启 / ssh。
6. **本域不改门店状态**。活动与门店营业状态是**两个域**（门店状态权威见
   `48-venue-status-authority`）。门店 `CEASED`（已停业）时发活动，详情页会出现
   「已停业」徽标 + 活动卡**互相打脸**——先与用户确认门店状态是否已/需同步恢复
   （走门店状态通道，不是本 Skill 的职责）。

## 分诊：这条情报该发到哪里（先分诊再动手）

| 内容性质 | 去处 | 判据 |
|---|---|---|
| **门店经营优惠**（门票/赠品/满减/套餐，有有效期） | **本 Skill** | 含优惠承诺、属经营信息 → 无商家通道，只能平台直发 |
| 平台权威信息（数据更新 / 新功能 / 规则） | 公告域 | `quwuting-announcement-publish` |
| 行业情报（哪家开/关、人气、时段） | 快讯域 | `quwuting-bulletin-publish` |
| 这家店**此刻**出了什么事（舞友上报） | 门店报告公告条 | `venue-announcements`（27 号），不背书 |
| 门店**开/停业状态**变化 | 门店状态通道 | 48 号；**不要用活动代替状态**，也不要用状态代替活动 |

**最易混的一对**：源消息常把「恢复营业 + 当天优惠」写在一条里 ——
**拆成两个动作**：营业状态走状态通道；当天的优惠（有时间语义、要被程序判定）走本 Skill。

## 工作流（五步）

### Step 1 情报提取 → 三轴结构化

从源情报里提取，**只有三样东西必须结构化**，其余全是自由文本
（49 号 §1：⛔ 严禁让自由文本参与判定，那是活动模板 DSL 的开端）：

| 轴 | 字段 | 取值 | 怎么选 |
|---|---|---|---|
| **外层调度** | `outerType` | `DATE_RANGE` / `ALWAYS` | 有明确档期 → `DATE_RANGE`；长期常态优惠 → `ALWAYS`。**「单日」不是独立值**，是 `start=end` 的退化（9/18 单日 → 两个日期都填 `2026-09-18`） |
| **权益类别** | `benefitKind` | `TICKET_B1G1` 门票买一送一<br>`TICKET_FREE` 门票免费<br>`TICKET_OFF` 门票立减<br>`GIFT` 到店赠品<br>`SPEND_OFF` 消费满减<br>`PACKAGE` 套餐优惠<br>`OTHER` 其他优惠 | 决定列表页那个 ≤4 字短标签。**新增类别的判据 = 列表页需要一个新短标签**，不要因为文案不同就加 |
| **核销方式** | `redemptionMode` | `CODE_WORD` 到店报口令<br>`SHOW_PAGE` 出示本页<br>`RESERVE` 需预约<br>`OPEN_TO_ALL` 到店即可 | 除 `OPEN_TO_ALL` 外都能归因（打卡可作平台贡献证据）；**能口令就口令** |

内层生效窗口 = 纯数据（不设枚举）：`weekdays`（可空/空 = 每天）× `windows`（可空/空 = 全时段）。

**两处容易漏的连带后果（2026-09-17 实战补记）**：

- ⚠️ **`OPEN_TO_ALL` 会让「我到店了」打卡按钮不渲染**：服务端把「不可归因」算进
  `checkinAvailable=false`（既有设计，49 号 §5.1：摆一个点不出结果的按钮比没有按钮更糟）。
  ⇒ 选「到店即可」就是**同时放弃这条活动的平台归因**。与门店谈口径时要知道这个代价：
  想要"打卡可作贡献证据"，就得是 `CODE_WORD` 且门店认可口令。
- ⚠️ **`windows` 填了就会上屏**（事实行的「时段」）。若它与门店档案 `businessHours`
  不一致（门店改了营业时间但档案没同步），详情页会**同屏出现两个时间**——基础信息卡一个、
  活动卡一个。**当日全天有效的活动优先留空**；确要限时段，先确认门店档案已同步
  （门店资料修改是门店域的事，不在本 Skill 范围内）。

### Step 2 门店甄别（发布前置，**同城同名必查**）

```bash
# 中文参数必须 URL 编码，否则 400
curl -sS -G "https://api.starseek.online/venues" \
  --data-urlencode "keyword=寻梦缘" --data-urlencode "size=50" --data-urlencode "sort=newest"
```

**判据（按证据强度从高到低）**：

1. **票价是否吻合**——源情报写"门票 30 元/位"，门店档案里就该有 30 元那张票；
2. **地址是否吻合**（含广场/楼宇名等**稳定地标**，街道名常被高德写成不同叫法）；
3. **活跃度**（`viewCount` / 是否有电话 / 是否有近期舞友上报状态），空壳重复数据通常
   无票价、无电话、`viewCount` 个位数。

⚠️ **实测案例（2026-09-17，南通「寻梦缘歌舞厅」）**：同名 4 家，其中南通两家 ——
`id=13`（地址含**京扬广场**2楼、有 30 元固定票价、电话、`viewCount=289`）与
`id=113`（地址写"东场坊2楼"、**无票价无电话**、`viewCount=5`）。
→ 判 **id=13**；`id=113` 是同批导入的空壳重复数据。
**只看店名会选错**；只看城市也会选错（同城就有两家同名）。选定后把甄别依据写进汇报。

### Step 3 文案撰写（受展示契约约束，不是自由发挥）

活动卡每件事**只有一个表达处**（49 号 §5.1.2「同一事实只呈现一次」）：

| 字段 | 回答什么 | 硬约束 |
|---|---|---|
| `title` | **是什么**（唯一的主张句，卡内最大字号） | ≤60 字；不要把限制条件塞进来 |
| `badgeLabel` | 列表页 ≤4 字短标签 | ≤8 字（超 4 字会顶掉列表页节奏）；留空 = 取类别默认（如"门票优惠"） |
| `benefitSummary` | **限制**：票价 / 期限 | ≤500 字；⛔ 不要复述 `title` 里的权益 |
| `redemptionHint` | 怎么核销（口令/出示/预约） | ≤200 字，如「到前台报「去舞厅」」 |
| `platformAddon` | 报口令我**多**拿什么 | ≤200 字；见红线 2，**没有就留空** |

- `windows` 填了 → 事实行显示时段；留空 → 显示为全时段有效（不占一行）。
- **跨夜时段契约**（舞厅普遍开到凌晨）：`13:00-02:00` 这种**直接填 `close < open`**，
  ⛔ **不要拆成两条**（拆开后跨夜那半段永不命中）。所有命中判定走
  `ActivityWindow.contains()`，任何地方不得自写时间比较。
- `weekdays` 用 ISO：1=周一 … 7=周日；留空 = 每天。

### Step 4 一步发布（ADMIN 通道）

鉴权：token 缓存 `/tmp/qw_token.json`，**变量名必须是 `ADMIN_TOKEN`**：

```bash
ADMIN_TOKEN=$(python3 -c "import json;print(json.load(open('/tmp/qw_token.json'))['token'])")
# 401 时重新登录取新 token 并回写缓存：
#   POST /web-auth/password-login  body: {"username":"admin","password":"$WEB_ADMIN_PASSWORD"}
```

**中文 + 多字段必须落临时 JSON 文件再 `--data @file`**（内联 `-d` 的 shell 引号极易翻车）：

```bash
cat > /tmp/qw_activity_payload.json << 'EOF'
{
  "venueId": 13,
  "title": "恢复营业首日 · 门票 1 元/位",
  "benefitKind": "TICKET_OFF",
  "badgeLabel": "门票1元",
  "benefitSummary": "原价 30 元/位（连场）；优惠仅限 9 月 18 日当天",
  "redemptionMode": "CODE_WORD",
  "redemptionHint": "到前台报「去舞厅」",
  "outerType": "DATE_RANGE",
  "startDate": "2026-09-18",
  "endDate": "2026-09-18",
  "windows": [{"open": "13:00", "close": "02:00"}],
  "sortWeight": 0,
  "publish": true
}
EOF
curl -sS -X POST "https://api.starseek.online/admin/venue-activities/create" \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H "Content-Type: application/json" \
  --data @/tmp/qw_activity_payload.json
```

- `publish: true` = **保存并直接发布**；`false` = 存 `DRAFT`（草稿态用户端看不到）。
- **⛔ 本接口没有 `dedupKey`**（与快讯域的关键差别）——**没有幂等保护**，
  连点两次就是两条活动。防重靠**发布前先查**：

```bash
curl -sS -H "Authorization: Bearer $ADMIN_TOKEN" \
  "https://api.starseek.online/admin/venue-activities?venueId=13&size=20"
```

### Step 5 核验与汇报

```bash
# 用户端口径（未登录可读，只认 PUBLISHED）——这才是用户真正看到的东西
curl -sS "https://api.starseek.online/venues/13/activities"
```

逐项核对：`status=PUBLISHED`；`state` 是否与当前时刻相符（派生权威在服务端——
**核对 `nextChangeAt` 与实际时钟，别用自己的时间比较去判对错**）；`windowsText` /
`validityText` / `badgeLabel` 是否就是你要的文案；`redemptionHint` 是否上屏。

- ⚠️ **管理端响应（`AdminVenueActivityResponse`）不含 `redemptionHint` / `platformAddon`**
  ⇒ 核销提示与加项文案**只能回用户端接口复看**，管理端列表里看不出来。
- 详情页与列表页**两个口径都要看**：详情页 = `GET /venues/{id}/activities`；
  列表页标记 = `GET /venues/activity-badges?venueIds=`（返回 `{venueId: {...}}`，
  无活动的门店**不返回键**）。

汇报给用户必须带：**活动 id · venueId 与甄别依据 · 全部字段 · 生效/失效时间 ·
核验结果 · 待确认项**（尤其门店状态是否需同步）。

## 改动 / 下线（既有活动）

| 动作 | 接口 | 语义 |
|---|---|---|
| 改 | `POST /admin/venue-activities/{id}/update` | **全量覆盖**：`applyFields` 逐字段 `set`，**没传的字段会被置空**（`badgeLabel` 例外：空则回落类别默认）⇒ 改一个字段也必须**重传全部字段** |
| 下线 | `POST /admin/venue-activities/{id}/offline` | 运营主动撤下（手动）；有效期结束的自动下线由 30s 调度负责 |
| 复活 | `POST /{id}/update` 带 `publish=true` | **OFFLINE 的唯一复活通道**（不做静默状态漂移） |

- `outerType=ALWAYS` 会**清掉遗留日期**（避免"改了类型但旧日期还在库里"造成两套真值）。
- 有效期判据 `endDate < today` ⇒ **结束当天仍然有效**，次日凌晨第一次调度才转 `OFFLINE`。
- 下线后用户端**整区消失**（无可见活动时活动卡不渲染），不需要额外的"隐藏"动作。

### 「更新某店活动」三条判据（2026-09-20 实战：南通帝豪音乐酒吧 id=122）

1. **先查现有活动再动手**（`GET /admin/venue-activities?venueId=`）。同一门店的「更新」=
   改既有条目，⛔ **不是再 create 一条**——本域没有幂等键，多 create 一次卡片上就多一条同义活动。
2. **一次情报含多种权益类别 ⇒ 拆成多条活动**。门票政策（`TICKET_*`）与次卡/套票
   （`PACKAGE`）的 `benefitKind` 不同 ⇒ 列表页短标签不同 ⇒ 各自一条。判据回到 Step 1
   「新增类别的判据 = 列表页需要一个新短标签」。**同一 `benefitKind` 内的细节**
   （时段边界 / 性别 / 赠票期限）写进 `benefitSummary`，不拆。
3. **改文案时顺手清掉上一轮遗留的"自由文本日期"**。本次实测既有活动 `benefitSummary` 写着
   「仅限 9 月 19 日当天」，而 `endDate=2026-10-08`——**自由文本与结构化有效期自相矛盾**。
   ① 有效期**只由** `startDate/endDate` 表达，正文里不写日期；② 老板每天发同一套福利
   （只有通知日期在变）时，那是门店常态票务口径，别按"单日促销"写文案。

**源情报里的非渠道限定语要如实转译**：`🔥男士福利` 这类**性别**限定不属于红线 1 的
「群友专享」——平台用户到店能兑现，不构成渠道限定；但必须如实标注（`benefitSummary`
写「限男士」），漏写会让到店预期错位。拿不准就问用户。

## 常见坑

- **接口 404**：生产后端未发版（活动域 2026-09-15 起新增，迁移 V27/V28）。停下告知用户，**禁自行部署**。
- **1034 参数非法**：`DATE_RANGE` 却没填任何日期 / `endDate` 早于 `startDate` /
  `windows` 某项缺 `open` 或 `close`。
- **1033 活动不存在**：id 写错或已被删。
- **1001 场所不存在**：`venueId` 不存在（多半是 Step 2 甄别出错）。
- **时段筛选漏了跨夜**：把 `13:00-02:00` 拆成 `13:00-23:59` + `00:00-02:00` ⇒ 凌晨那半段永不命中。
- **重复活动**：没有幂等键，重复提交产生多条 → 用列表接口核对，多出来的手动 `offline`。
- **中文 400**：GET 参数未编码；用 `--data-urlencode`。
- **`state` 与直觉不符**：状态是**服务端派生**的（前端零推导），别按自己的时间比较去判断对错；
  先看 `nextChangeAt` 与实际时钟。

## 相关

- 门店状态恢复 / 停业：`quwuting-venue-daily-sync`（本 Skill **不碰**门店状态）
- 平台公告：`quwuting-announcement-publish` ｜ 行业快讯：`quwuting-bulletin-publish`
