---
name: quwuting-git-sync
description: 去舞厅（quwuting）三仓（quwuting / quwuting-service / quwuting-admin-web）拉取与本地脏工作区处置工作流。当需要 git pull、同步远程代码、处理「本地未提交改动挡住 pull」或 run-history.md 追加型日志冲突、拉取后核查迁移编号与 skill 镜像漂移时使用。
agent_created: true
---

# 三仓拉取与脏工作区处置

## 〇、三仓与分支（唯一事实）

| 目录（三仓父目录，各机自定；本机 = `/Users/xin.y/WeChatProjects/`） | 分支 | 说明 |
|---|---|---|
| `quwuting` | `feat/remove-partner-and-recruit` | 小程序端（TS/WXML） |
| `quwuting-service` | `feature/mysql-migration` | 后端 + 各 `quwuting-*` skill 权威源 |
| `quwuting-admin-web` | `master` | 管理后台（Vue） |

**运行前必做**：`cd quwuting-service && bash scripts/sync-skills.sh --check`
（确认 skill 镜像未漂移；有差异时先判断方向再同步，见 §四）。

## 一、先探状态，再拉取

禁止直接 `git pull` 三仓——`quwuting-service` 长期带**未提交的 skill 参考资料改动**（build-playbook / run-history / 字典 / 各 SKILL.md），
本地脏文件恰好被远程改动时会 **abort**。步骤：

1. 三仓各跑 `git status -sb`，记录脏文件清单与落后情况。
2. **并发写手检测（必做，2026-09-30 实证）**：`git status` 只证明「有改动」，不证明「没人在改」。
   对脏文件**连续采样两次 mtime**，两次之间穿插你本来就要做的诊断（间隔 ≥30s）：

   ```bash
   stat -f "%Sm %N" -t "%H:%M:%S" $(git diff --name-only)   # 采样 1
   # … 做 fetch / 比对等诊断，天然等待数十秒 …
   stat -f "%Sm %N" -t "%H:%M:%S" $(git diff --name-only)   # 采样 2
   ```

   判据：**任一时间戳晚于你本次会话开始时刻 ⇒ 判定该仓存在并发写手，此仓一律不动**
   （不 stash、不 pull、不 checkout），只输出冲突面清单并等对方收工。
   为什么是红线：`stash push` 会把文件从工作区**摘走**，对端编辑器随后保存/重建 ⇒
   不是「丢掉一份改动」而是**两边都写坏**，后果重于普通冲突。
   附带用途：同一仓连续两次采样静止 ≠ 全仓静止，`find . -newermt "<T>" -not -path "./.git/*"`
   可快速圈出「最近被写过」的文件，避免只看 `git diff` 名单而漏掉未跟踪的新文件。
3. 三仓 `git pull` 并行执行（互不依赖）；失败的那个单独处理。

## 二、脏工作区挡住 pull：备份 → stash → pull → 定向还原

⛔ **禁用 `git checkout -- .` 清工作区**（skill 改动是未提交的真实工作，丢了无法从 Git 找回）。
⛔ **禁用直接 `git stash pop`**——追加型日志（见 §三）会留冲突标记，需人工定向合并。

```bash
BK=/tmp/qwt-pull-backup-$(date +%H%M%S) && mkdir -p $BK
# 逐文件备份（保留目录结构）
git diff --name-only | while read f; do mkdir -p "$BK/$(dirname $f)"; cp "$f" "$BK/$f"; done
git stash push -m "wip-before-pull-$(date +%H%M%S)" -- <脏文件清单>
git pull
# 拉取后先分类，再分头处理：
#   远程**未改动**的脏文件 → 直接从 $BK 覆盖回去（等价于干净的 stash pop）
#   远程**改动过**的脏文件 → 定向合并：run-history.md 走 §三；其余一般文件走 §2.1
```

⚠️ `git diff --name-only` **不含未跟踪新文件**（本轮 28 个脏条目里 6 个是 `??`）。
新增文件同样是真实工作（新门禁脚本 / 新 utils），必须一并备份与还原：
用 `git stash push -u`，或 `git ls-files --others --exclude-standard` 单独登记。
`git stash push -- <文件...>` 可只暂存指定文件，避免误吞其它在途改动。
全部还原并校验通过后再 `git stash drop`；`/tmp` 备份留到会话结束再删。

⛔ **`??` 清单里必须剔除 `.cache-check/`（2026-10-03）**：它是**外部检查工具的全量分析沙箱**，
不是工作产物——整仓副本 `wt/`（含 `node_modules` 软链）、tsc 产物 `out/`、`geo/`、`bin/npx` 垫片，
实测 **107MB / 2447 文件**。已在 `quwuting/.gitignore` 忽略。
三处别踩：

- **禁入备份/还原清单**：`git stash push -u` 会把它**整包吞入**，还原时等于把 107MB 垃圾搬回来；
- **禁参与并发写手 mtime 采样**（§一 步骤 2）：它会污染「最近被写过」的 `find -newermt` 圈选；
- **上手先自检**：`git check-ignore -v .cache-check/`。**若它仍以 `??` 出现 ⇒ `.gitignore` 规则被回退，
  先补规则再往下走**（缺规则时 `git status` 会把它折叠成一行 `?? .cache-check/`，极易被当成一个普通新目录放行）。

### 2.1 真冲突文件：先跑 `git merge-file`，再判「取非 base 侧」

⛔ **别一上来就人工读 `<<<<<<<`** —— 先让 Git 做一次三向合并，**伪冲突会自动消失**：

```bash
OLD=$(cat "$BK/OLD_HEAD")                      # §一 记录的拉取前 HEAD
safe=$(echo "$f" | tr '/' '_')
git show "$OLD:$f" > "/tmp/qwt-merge/base_$safe"      # base
cp "$BK/files/$f"  "/tmp/qwt-merge/local_$safe"       # 本地
cp "$f"            "/tmp/qwt-merge/remote_$safe"      # 远端（= pull 后的工作区）
set +e
git merge-file -p -L LOCAL -L BASE -L REMOTE \
  "/tmp/qwt-merge/local_$safe" "/tmp/qwt-merge/base_$safe" "/tmp/qwt-merge/remote_$safe" \
  > "/tmp/qwt-merge/merged_$safe"              # 退出码 = 冲突块数；0 = 干净
```

2026-09-30 实证：5 个真冲突文件里 **4 个零冲突**（两侧改的是文件不同区域），只剩 1 个文件
1 块冲突。**先跑这一步再估工作量**，别按「冲突文件数」估难度。

**「取非 base 侧」判据（超长行 / 表格行必用）**：本项目大量文件是「一行一个语义单位」
（`AGENTS.md` 索引表、`docs/agents/*.md` 的表格行），单行上千字。
⚠️ 行粒度 diff 在此太粗 —— 两侧各改**相邻两行**时必报整块冲突，但内容其实不重叠。

判据 = 对冲突块内**每一行**，比对 base / local / remote 三版：

| 情形 | 取谁 |
|---|---|
| `local == base`（本地没动这行） | **取 remote** |
| `remote == base`（远端没动这行） | **取 local** |
| 两侧都 ≠ base | 真重叠 ⇒ 才需人工读内容合并 |

2026-09-30 实证：`AGENTS.md` 唯一冲突块 2 行 —— `34-announcements` 行本地未动（取远端 v5.8）、
`35-venue-search` 行远端未动（取本地）⇒ 一条规则机械求解，**不必读那 3000 字长行**。

## 三、run-history.md 追加型日志的冲突合并（本项目高频）

`quwuting-venue-daily-sync/reference/run-history.md` 是**追加型**日志，并行会话各自在文件末尾追加
⇒ 双方改动落在**同一锚点**（永远是文件末尾），Git 报冲突。

**判据 = 时间顺序**（不是分支顺序、不是 diff 大小）：

1. 取两侧新增块的时间线与内容指纹：
   - 远程提交时间：`git log -2 --format="%h %ci %s" origin/<branch>`
   - 本地块时间：文件 mtime + 块内自述（公告编号、venueId 区间、门店总数快照）
2. **交叉印证块序**：后发生的一侧必然**引用**先发生一侧的产物
   （例：远程块出现「再 `offline` #78」⇒ 本地块必在前；远程块出现本地刚建的 `#1427` ⇒ 本地块必在前）。
3. 合并 = `基准 + 本地块 + 空行 + 远程块`（**两侧内容一行不删**，只有空行分隔）。

按行号硬插的脚本模板（`dst` = pull 后文件，`src` = 备份的本地文件）：

```python
start = next(i for i, l in enumerate(src) if l.startswith('<本地块首个标题>'))
while start > 0 and src[start-1].strip() == '': start -= 1      # 含前导空行
local_block = src[start:]
while local_block and local_block[-1].strip() == '': local_block.pop()
idx = next(i for i, l in enumerate(dst) if l.startswith('<远程块首个标题>'))   # 必须前缀匹配
open(dst_path, 'w').write('\n'.join(dst[:idx] + local_block + [''] + dst[idx:]))
```

⚠️ 踩坑：`list.index(字符串)` 是**完全相等**匹配，标题行带括号后缀会永远 `ValueError`
⇒ 一律 `next(i for i,l in enumerate(lines) if l.startswith(marker))`。

## 四、拉取后必查（三条，均有过实际事故）

1. **迁移编号**：`ls src/main/resources/db/migration-mysql/ | sort -V | tail -5`
   —— 远程可能刚占用你在本地方案里规划的号（09-29：本地草案「到访 V33」被远程实际落地的
   `V33__venue_presence_pings` 占用）。**新迁移一律取实际最大号 +1**（当时 = V35）。
2. **语义取代**：拉取可能带入**已实现**的能力，使本地未提交的方案草案/待办过时
   ⇒ 报告时显式指出「哪条本地草案已被落地取代」，别让用户按旧方案继续推。
3. **skill 镜像同步**：源（仓内 `quwuting-<name>/`）→ 镜像（`~/.workbuddy/skills/`）单向。
   远程带入 skill 文件改动后镜像即漂移。
   - 先判方向：`diff -u <镜像> <源> | grep '^-'`（去掉 `---` 表头）
     —— 若镜像独有行**全是被源取代的旧措辞**，则镜像只是旧版，同步安全；
     **若镜像含源里没有的新内容 ⇒ 停止同步并报告**（可能是有人直接改了镜像，违反单向约定）。
   - `bash scripts/sync-skills.sh`（覆盖前自动备份到 `~/.workbuddy/.skill-sync-backup/`）。
   - ⛔ 备份目录必须在 `skills/` **之外**（同形目录会被 Skill 扫描器收录 ⇒ 加载到旧稿）。
   - 复核：`bash scripts/sync-skills.sh --check` 应「一致 5 · 有差异 0」。

## 五、验证清单（收尾前逐条跑）

```bash
git diff --stat                        # 改动集应与 pull 前一致（澄清新增分隔空行）
grep -c -E '^(<<<<<<<|=======|>>>>>>>)' <合并文件>   # 必须为 0
```
- 两侧块**完整保留**：用「块字符串 `in merged`」断言，并断言本地块位置 < 远程块位置。
- 远程未改动的脏文件与备份逐字节比对：`cmp -s "$BK/$f" "$f"`。
- 接缝处应为**恰好一行空行**（与文件内 `### ` 段落间距一致）。
- **两侧改动全保留断言（比 `git diff --stat` 有力，缺它则"零冲突"可能静默吞内容）**：
  对每个冲突文件取 `base→local` 与 `base→remote` 的 `+` 行集合，断言二者都 ⊆ 合并结果。
  ⚠️ 踩坑：difflib / `unified_diff` 的行**自带 `+` 前缀**，比对前必须 `l[1:]`；
  否则会把「全部行缺失」当成结果（2026-09-30 实测：忘了 strip ⇒ 143/143 误报缺失）。
- **`AGENTS.md` / `docs/agents/*.md` 抽检**：表格行去重（`awk -F'|' '{print $2}' | sort | uniq -d`）
  与标题去重 —— 两侧各自新增同一段落时会留下重复块，行数对得上但内容重复。
- **`.ts → .js` 产物自洽（小程序仓专属，性价比最高的收尾判据）**：
  `npm run mirror:js -- --dry-run <本次改过的 .ts>`
  报「内容一致，零改动」⇒ 一条命令同时证明 **tsc 编译通过 + .js 与 .ts 一致**；
  报「需要回拷」⇒ 该 `.js` 必须由 tsc 重生成，**禁手改 .js**（镜像脚本以 TS 为权威）。
- **合并收尾必跑 `npm run verify`**（`tsc --noEmit` + 19 道门禁）= 本项目对「合并正确」的定义。
  跑完核退出码：`npm run verify > /tmp/v.log 2>&1; echo EXIT=$?`（只看 tail 会漏掉中段失败）。

## 六、红线

- **有并发写手就不拉**：见 §一.2——宁可停在「只报告冲突面」，也不赌一个正在被编辑的工作区。
- **只拉不推**：`git pull` 之外不动 remote——`git push` / `deploy.sh` / `ssh aliyun` / 重启服务
  均须用户明确要求。
- 合并**只增不删**：追加型日志两侧都是历史事实，不得以「去重/精简」为由删任一侧。
- 备份留在 `/tmp`（会话级），不要污染仓库目录。
