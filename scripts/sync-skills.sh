#!/usr/bin/env bash
#
# 去舞厅 · 项目 Skill 双副本同步（2026-09-14 建立）
# ------------------------------------------------------------------
# 背景：项目 Skill 存在两份副本，长期存在「项目侧已改、运行时侧过期」的静默漂移
# （2026-09-14 实测：quwuting-bulletin-publish 项目侧 5 稿已删 title 字段，
#   运行时侧仍是 2 稿口径 → 加载到旧契约会写出被忽略的字段）。
#
# 权威方向：**项目路径 = 源（Git 可提交、可回溯）**，运行时路径 = 镜像。
# 因此本脚本是单向「源 → 镜像」，不做双向自动合并（双向合并会静默吞掉一侧改动）。
#
# 用法：
#   bash scripts/sync-skills.sh            # 同步（默认）：覆盖前自动备份，幂等可重跑
#   bash scripts/sync-skills.sh --check    # 只报告差异，不写任何文件（退出码 1 = 有差异）
#
# 何时跑：**每次运行任一 quwuting-* Skill 之前**（见各 SKILL.md「⓪ 运行前必做」）。
# 环境变量：QW_PROJECT_ROOT / QW_SKILLS_HOME 可覆盖默认路径。
#
set -uo pipefail

PROJECT_ROOT="${QW_PROJECT_ROOT:-/Users/xin.y/WeChatProjects/quwuting-service}"
SKILLS_HOME="${QW_SKILLS_HOME:-$HOME/.workbuddy/skills}"
BACKUP_ROOT="$SKILLS_HOME/.sync-backup"

MODE="sync"
for arg in "$@"; do
  case "$arg" in
    --check|--dry-run) MODE="check" ;;
    -h|--help)
      sed -n '2,22p' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "未知参数：${arg}（可用：--check / --dry-run / --help）" >&2; exit 2 ;;
  esac
done

# 有项目源副本的 Skill（源目录名 = 运行时目录名）
MANAGED_SKILLS=(
  quwuting-venue-daily-sync
  quwuting-announcement-publish
  quwuting-bulletin-publish
)

RSYNC_EXCLUDES=(--exclude=.DS_Store --exclude=__pycache__ --exclude=*.pyc)

if [[ ! -d "$PROJECT_ROOT" ]]; then
  echo "❌ 项目根目录不存在：$PROJECT_ROOT" >&2; exit 2
fi
if [[ ! -d "$SKILLS_HOME" ]]; then
  echo "❌ 运行时 skills 目录不存在：$SKILLS_HOME" >&2; exit 2
fi

TS="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="$BACKUP_ROOT/$TS"

echo "── Skill 双副本同步（$([[ $MODE == check ]] && echo 只读检查 || echo 同步)）"
echo "   源   ：$PROJECT_ROOT"
echo "   镜像 ：$SKILLS_HOME"
echo

changed=0; same=0; missing_src=0

for skill in "${MANAGED_SKILLS[@]}"; do
  src="$PROJECT_ROOT/$skill"
  dst="$SKILLS_HOME/$skill"

  if [[ ! -d "$src" ]]; then
    echo "⚠️  $skill — 项目侧无源目录，跳过"
    missing_src=$((missing_src + 1)); continue
  fi

  if [[ -d "$dst" ]]; then
    diff_out="$(diff -rq -x '.DS_Store' -x '__pycache__' "$src" "$dst" 2>&1)"
  else
    diff_out="运行时侧缺失（将全量创建）"
  fi

  if [[ -z "$diff_out" ]]; then
    echo "✅ $skill — 已一致"
    same=$((same + 1)); continue
  fi

  changed=$((changed + 1))
  echo "🔁 $skill — 存在差异："
  echo "$diff_out" | sed 's/^/     /'

  if [[ $MODE == sync ]]; then
    mkdir -p "$BACKUP_DIR"
    if [[ -d "$dst" ]]; then
      cp -R "$dst" "$BACKUP_DIR/$skill"
    fi
    mkdir -p "$dst"
    rsync -a --delete "${RSYNC_EXCLUDES[@]}" "$src/" "$dst/" \
      || { echo "     ❌ 同步失败：$skill" >&2; exit 1; }
    echo "     → 已同步（原镜像备份于 $BACKUP_DIR/${skill}）"
  fi
done

# 孤儿检测：运行时侧有、项目侧无源的 Skill（不经本脚本管理，无法版本化）
# 注意：macOS 自带 bash 3.2，`set -u` 下空数组展开会报 unbound variable —— 故用字符串累加
orphans=""
for d in "$SKILLS_HOME"/quwuting-*; do
  [[ -d "$d" ]] || continue
  name="$(basename "$d")"
  for m in "${MANAGED_SKILLS[@]}"; do [[ "$name" == "$m" ]] && continue 2; done
  orphans="$orphans $name"
done

echo
echo "── 汇总：一致 $same · 有差异 $changed · 项目侧缺源 $missing_src"
if [[ -n "$orphans" ]]; then
  echo "ℹ️  无项目源（仅存在于运行时，未纳入版本管理）：$orphans"
fi
if [[ $MODE == check && $changed -gt 0 ]]; then
  exit 1
fi
exit 0
