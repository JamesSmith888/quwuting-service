#!/usr/bin/env python3
"""已跟踪文件的密钥扫描（2026-10-01 引入；提交前 / CI 必跑）。

── 根因 ──
`src/main/resources/application-dev.yaml.bak` 带着真实 JWT 签名密钥、微信 AppSecret、
地图 key 被提交进 Git（2026-09-01，提交信息「1111」），一个月无人察觉：
  · .gitignore 逐个枚举文件名，`.bak` 变体漏网（已改为白名单式忽略）；
  · 仓库没有任何「已跟踪文件里有没有密钥」的机器检查——忽略规则只管「新文件进不进来」，
    管不了「已经进来的」，也管不了被 `git add -f` 强加进来的。

── 规则 ──
扫描 `git ls-files` 的全部文本文件：
  1. 高置信形态（任何文件类型）：私钥块、阿里云 AccessKeyId（LTAI…）、长 JWT（eyJ…）。
  2. 配置文件（.yaml/.yml/.properties）中键名含 password/secret/token/access-key/private-key
     （以及 anon-key / api-key / *-key 结尾）的条目，值必须是空、`${占位符}` 或模板标记
     （如 `<...>`、`change-me`、`xxx`），否则判为明文密钥。
命中即退出码 1，并只打印「文件:行号:键名」，**不打印值本身**。

用法：python3 scripts/check-tracked-secrets.py    （仓库根目录执行）
"""

import re
import subprocess
import sys
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent

HIGH_CONFIDENCE = [
    ("私钥块", re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH |DSA )?PRIVATE KEY-----")),
    ("阿里云 AccessKeyId", re.compile(r"\bLTAI[0-9A-Za-z]{12,}\b")),
    ("JWT", re.compile(r"\beyJ[0-9A-Za-z_-]{20,}\.[0-9A-Za-z_-]{20,}\.[0-9A-Za-z_-]{10,}")),
]

CONFIG_SUFFIXES = {".yaml", ".yml", ".properties"}
SENSITIVE_KEY = re.compile(
    r"(password|passwd|secret|token|access[-_]?key|private[-_]?key|anon[-_]?key|api[-_]?key|(^|[-_.])key$)",
    re.IGNORECASE,
)
# 公开值 / 非密钥但名字像的键（显式登记，避免误报；新增须说明理由）
NON_SECRET_KEYS = {
    "status-template-id",   # 订阅消息模板 ID：公开信息
    "max-file-size",
}
PLACEHOLDER = re.compile(r"^(\$\{[^}]*\}|<[^>]*>|change[-_ ]?me.*|x{3,}.*|your[-_].*|placeholder.*|example.*)$",
                         re.IGNORECASE)
YAML_ENTRY = re.compile(r"^\s*([A-Za-z0-9_.-]+)\s*[:=]\s*(.*)$")


def tracked_files() -> list[Path]:
    out = subprocess.run(["git", "ls-files", "-z"], cwd=REPO_ROOT, check=True,
                         capture_output=True).stdout.decode("utf-8")
    return [REPO_ROOT / p for p in out.split("\0") if p]


def strip_value(raw: str) -> str:
    value = re.sub(r"\s+#.*$", "", raw).strip()
    if len(value) >= 2 and value[0] == value[-1] and value[0] in "'\"":
        value = value[1:-1]
    return value.strip()


def scan(path: Path) -> list[str]:
    try:
        text = path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        return []
    rel = path.relative_to(REPO_ROOT)
    findings = []
    lines = text.splitlines()
    for no, line in enumerate(lines, 1):
        for label, pattern in HIGH_CONFIDENCE:
            if pattern.search(line):
                findings.append(f"{rel}:{no}: {label}")
    # 用全部后缀判断（foo.yaml.bak / foo.yml.orig 这类备份同样是配置文件——本次事故就是它）
    if any(suffix in CONFIG_SUFFIXES for suffix in path.suffixes):
        for no, line in enumerate(lines, 1):
            if line.lstrip().startswith("#"):
                continue
            m = YAML_ENTRY.match(line)
            if not m:
                continue
            key, value = m.group(1), strip_value(m.group(2))
            if key.lower() in NON_SECRET_KEYS or not SENSITIVE_KEY.search(key):
                continue
            if value == "" or PLACEHOLDER.match(value):
                continue
            findings.append(f"{rel}:{no}: 配置键 `{key}` 是明文值（应为空或 ${{占位符}}，真实值放 gitignored 外部配置）")
    return findings


def main() -> int:
    findings = []
    for path in tracked_files():
        if path.is_file():
            findings.extend(scan(path))
    if findings:
        print("✗ 已跟踪文件中发现疑似密钥（只列位置，不打印值）：")
        for f in findings:
            print("  - " + f)
        print("处置：git rm --cached <文件> 停止跟踪 + 轮换泄露的凭据（历史提交中仍可读取）。")
        return 1
    print("✓ 已跟踪文件未发现明文密钥")
    return 0


if __name__ == "__main__":
    sys.exit(main())
