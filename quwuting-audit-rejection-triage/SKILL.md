---
name: quwuting-audit-rejection-triage
description: 去舞厅（quwuting）微信小程序审核驳回的分诊与修复工作流。当收到小程序审核失败（驳回文案「未浏览体验功能服务即要求授权登录」/「涉及 UGC」/「类目不符」等）、需要判断"审核到底看到了什么"、或要排查「落地即要求登录」类缺陷时使用。覆盖：模板话术辨别 → 审核截图放大定位页面 → 门禁扫描定位根因 → 三类修复形态 → 真机复现与提审策略。
agent_created: true
---

# 小程序审核驳回分诊：把「审核文案」变成可验证根因

> 一句话：**驳回文案是结论，不是证据。** 先把它翻译成"审核员在他那台设备上看到了什么页面"，
> 再去代码里找那个页面为什么会长成那样。

## 〇、红线

- **不要照着驳回文案的字面去改**。文案是**规则条文的模板**，措辞可能与真实原因无关
  （2026-09-30 实证：文案写「要求授权手机号码、头像、昵称」，而全仓**从未调用**
  `wx.getUserProfile` / `getPhoneNumber` / `wx.getUserInfo` / `chooseAvatar`）。
- **不要为了让审核通过而砍功能**（除合规红线外）。优先修根因——审核员看到的往往是
  **一个真实缺陷**，只是他比你更早、在更干净的环境里撞上。
- 提审往返成本高（天级），**一次改到位**：把同类缺陷全清（用门禁兜底），别只修被点到的那一处。

## 一、第一步：辨别「模板话术」（最高价值的一步）

驳回文案里凡是**成套出现的授权名词**（手机号 + 头像 + 昵称三件套、位置 + 通讯录等），
先假设它是模板。**用 grep 证伪或证实**：

```bash
cd quwuting/miniprogram
grep -rn "getUserProfile\|getPhoneNumber\|getUserInfo\|chooseAvatar" \
  --include="*.ts" --include="*.js" --include="*.wxml" . | grep -v "^./typings"
# 零命中 ⇒ 我们从未索取过这些授权 ⇒ 文案是模板，真实原因在别处
```

**判据：能机器证伪的结论，不要用阅读代码去猜。**

同时注意反向可能：文案说的"授权"可能指**微信原生弹窗**（位置 `scope.userLocation` 的 desc、
订阅消息授权等），那些要在 `app.json` / `services/subscribe.ts` 里找。

## 二、第二步：放大审核截图，定位「审核员落在哪一页」

审核截图通常很小（手机截图被缩进一段文字里）。**裁切 + 放大**能读出关键信息：

```bash
IMG=~/path/to/audit-screenshot.png
sips -g pixelWidth -g pixelHeight "$IMG"          # 先看尺寸，估小图位置
# -c <高> <宽> --cropOffset <上> <左>（区域靠肉眼估，多试几次）
sips -c 230 320 --cropOffset 786 25 "$IMG" --out /tmp/crop.png
sips -Z 1400 /tmp/crop.png --out /tmp/crop-big.png
```

要读出的三件事：

| 特征 | 意义 |
|---|---|
| **顶部胶囊文案**（如"xx-小程序审核专…"） | 是否审核专用版本/体验版 |
| **底部 tabBar 哪一格高亮** | 定位到 **tab 页**——这一步最省事，直接排除 30 个二级页 |
| 页中一行文案 + 一个按钮的形态 | 极可能是**空态/错误态**（登录墙就是"一行提示 + 去登录"） |

## 三、第三步：定位根因（按驳回类型分诊）

### 类型 A：「未浏览体验功能服务，即要求授权登录」= 登录墙

**先跑门禁**（它是这类缺陷的专用扫描器）：

```bash
cd quwuting && npm run check:first-paint-auth
```

门禁扫 `pages/**/*.ts`，A 组抓 `onLoad` 内用同步快照做首屏门禁、B 组抓 `onShow` 因未登录早退。
**若门禁零违规而审核仍报登录墙**，改人工扫这四条：

```bash
cd quwuting/miniprogram
grep -rn "请先登录" pages --include="*.ts"            # 登录墙文案
grep -rn "onLoad" -A 12 pages --include="*.ts" | grep -n "ensureLogin"  # onLoad 弹框
grep -rn "loggedIn" pages --include="*.wxml"          # "去登录"分支
```

**根因只有一种**：把「**还没有结论**」当成了「**没有**」。

- 凭证由 `app.onLaunch` 的 `silentLogin()` **异步**换取（wx.login → 后端建号 → 落盘）；
- 页面 `onLoad` 与它**同一帧**启动 ⇒ 同步读 `isLoggedIn()` 必然是空快照；
- 页面于是渲染登录墙，而 `onLoad` 每进程只跑一次、登录完成的广播打在它**之后** ⇒ **永不自愈**；
- 审核员是**全新设备**（storage 全空，还要走完整建号）⇒ **必中且最快**。

### 类型 B：「涉及用户生成内容（UGC）/ 社交类目」

个人主体无「社交服务」类目。查是否新增了"用户可输入并公开展示"的功能，
解法固定 = **砍前端用户入口 → 平台代发 / 仅管理员直发**（见 AGENTS.md UGC 红线）。

### 类型 C：页面/文案命中敏感词

先查 `docs/agents/` 里该域的合规沿革（本仓已有多次同构改名先例），**接口路径与字段名一律不动**
（技术标识不命中词库），只改用户可见文案。

## 四、修复形态（类型 A 的三条，缺一不可）

**① 首屏要「登录结论」→ 用 `whenAuthSettled()`，禁用同步快照**

```ts
const state = await whenAuthSettled()   // services/auth.ts；等本次启动的登录落定
if (!state.loggedIn) { this.safeSetData({ loading: false, loadError: '请先登录后…' }) ; return }
```

它不弹框、不索取授权、**恒 resolve**（失败降级匿名）、经 `doLogin` 单飞**不产生额外请求**。
⚠️ `onAuthStateChanged` 是**纯增量**语义（不重放当前值）⇒ **"只订阅不判起点"不成立**。

**② 内容公开的页面 → 干脆不判登录**（首选，改动最小、审核最稳）

- 前端：直接渲染；登录态只影响**用户级态**（自己是否表态过 / 未读点 / 未读徽标），
  由 `onAuthStateChanged` 收敛（冷启动登录完成 / 点位操作触发登录，两条路径都不触发 onShow）。
- 后端：读接口 `UserContext.requireAuth()` → `getCurrentUserId()`（可空），
  个人态字段（`reactedByMe` / `read`）恒 false，**写接口保持 `requireAuth`**。
  先查 service 是否已有 null 守卫（本仓 `batchBadges` / `recordViews` 都有 ⇒ 零额外分支）。

**③ `onShow` 不得因未登录整体早退**：未登录只该跳过用户级态的刷新，不该让整页不刷新
（否则首屏失败/未加载完的页面切回来也不重试，永久停在空态）。

**配套：`ensureLogin()` 只允许出现在用户动作触发的回调里**。写在 `onLoad` 里 = 打开即弹框，
是同一红线的另一种形态（本仓 `announcement-detail` 曾如此，而该页正是分享卡片落点）。

## 五、验证与提审

```bash
cd quwuting
# 1. 类型 + 全部门禁（用隔离工作区的 tsc，见「环境坑」）
npm run verify
# 2. 改过 .ts 后必须镜像 .js（禁全量重生成，见 scripts/mirror-js.sh 头注）
npm run mirror:js                 # 扫描模式 = git status 里的 .ts
npm run mirror:js -- <路径>        # 精确模式（推荐）
```

**必须真机复现**（这是唯一能证明修复的验证）：

1. 微信开发者工具 **清缓存**（清存储）→ 冷启动 → **直接进被驳回的那一页** → 应直接出内容；
2. 匿名态回归：点表态/收藏仍按需登录；未读数、个人态在登录后自动补正；
3. 若有后端改动：`mvnw test-compile` + `-Drun.db.tests=true`。

**提审策略**：功能页面填**不需要登录的入口页**（如首页），并在审核备注里写明
「xx 为公开内容，无需登录即可浏览，登录仅用于表态等交互」。

## 六、环境坑（本仓实测，会误导判断）

⚠️ **`quwuting/node_modules` 里没有 typescript**（package.json / lock 也未声明）⇒
`npx tsc` 会解析到 registry 上的空壳包，**静默空转 exit 0**——"tsc 全绿"是假象。
`check-empty-state-behavior.js` 传的 `--ignoreConfig`（TS 6.x 选项，5.4/5.9 均不支持）会让
`npm run check` 链中断。临时跑法：

```bash
export PATH="$HOME/.workbuddy/binaries/node/workspace/node_modules/.bin:/path/to/managed-node/bin:$PATH"
tsc --version    # 必须是真版本号；若报 Unknown option 说明解析错了包
```

**判据：门禁/检查工具的"全绿"要先证明它真的执行过**（看它扫了多少文件、`--version` 是否正常）。

## 七、沉淀检查清单（做完这轮，问自己）

- [ ] 同源缺陷是否**全部**清掉（不是只修被点到的那一处）？
- [ ] 有没有**机器门禁**兜住这一类？（没有就写一个——本仓每类缺陷都配一道）
- [ ] `AGENTS.md`「最小事实」+ 域文档（`docs/agents/<n>-*.md`）是否同步了**新的判据**？
- [ ] 后端接口契约文档（service 仓同名文档）是否同步？
- [ ] 日报是否记下"根因 + 判据"（不是"改了什么"）？
