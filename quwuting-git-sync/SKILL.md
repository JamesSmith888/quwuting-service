---
name: quwuting-git-sync
description: 去舞厅（quwuting）三仓（quwuting / quwuting-service / quwuting-admin-web）拉取与本地脏工作区处置工作流。当需要 git pull、同步远程代码、处理「本地未提交改动挡住 pull」或 run-history.md 追加型日志冲突、拉取后核查迁移编号与 skill 镜像漂移时使用。
agent_created: true
---

# 三仓拉取与脏工作区处置

## 〇、三仓与分支（唯一事实）

| 目录（`/Users/yangxin/Downloads/xcxWork/`） | 分支 | 说明 |
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
2. 三仓 `git pull` 并行执行（互不依赖）；失败的那个单独处理。

## 二、脏工作区挡住 pull：备份 → stash → pull → 定向还原

⛔ **禁用 `git checkout -- .` 清工作区**（skill 改动是未提交的真实工作，丢了无法从 Git 找回）。
⛔ **禁用直接 `git stash pop`**——追加型日志（见 §三）会留冲突标记，需人工定向合并。

```bash
BK=/tmp/qwt-pull-backup-$(date +%H%M%S) && mkdir -p $BK
# 逐文件备份（保留目录结构）
git diff --name-only | while read f; do mkdir -p "$BK/$(dirname $f)"; cp "$f" "$BK/$f"; done
git stash push -m "wip-before-pull-$(date +%H%M%S)" -- <脏文件清单>
git pull
# 拉取后：远程**未改动**的文件直接从 $BK 覆盖回去（等价于干净的 stash pop）
#           远程**改动过**的文件（尤其 run-history.md）走 §三 定向合并
```

`git stash push -- <文件...>` 只暂存指定文件，避免误吞其它在途改动。
全部还原并校验通过后再 `git stash drop`。

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

## 六、红线

- **只拉不推**：`git pull` 之外不动 remote——`git push` / `deploy.sh` / `ssh aliyun` / 重启服务
  均须用户明确要求。
- 合并**只增不删**：追加型日志两侧都是历史事实，不得以「去重/精简」为由删任一侧。
- 备份留在 `/tmp`（会话级），不要污染仓库目录。
