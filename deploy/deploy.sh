#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# quwuting-service 一键部署脚本（Alibaba Cloud Linux 3 / RHEL 系兼容）
#
# 适用场景：阿里云 ECS + Cloudflare Tunnel（HTTPS 终止，转发至 localhost:8080）。
# 满足 AGENTS.md 核心约束：java -jar + JVM 内存限制 + systemd
# Restart=always + cgroup MemoryMax 三件套。
#
# 配置策略：所有业务配置（DB/JWT/Supabase）直接写在 application-dev.yaml 中，
# 不依赖外部环境变量文件。JVM 参数硬编码在 ExecStart 中。
#
# 用法（首次部署，root 直接执行）：
#   1. 把仓库 clone 到 /root/quwuting-service（或通过环境变量覆盖 APP_DIR）
#   2. 确保 src/main/resources/application-dev.yaml 中有真实的业务配置
#   3. sudo bash deploy/deploy.sh
#
# 后续升级：
#   cd /root/quwuting-service && git pull
#   sudo bash deploy/deploy.sh --no-user --no-unit   # 仅重新打包+重启
#
# 退出码：非 0 即失败；脚本采用 set -euo pipefail，任何错误立刻中止。
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

# ── 可调参数（脚本顶部集中维护） ───────────────────────────────────────────
APP_NAME="quwuting-service"
APP_DIR="${APP_DIR:-/root/quwuting-service}"
APP_USER="${APP_USER:-appuser}"
LOG_DIR="${LOG_DIR:-/var/log/${APP_NAME}}"
UNIT_FILE="/etc/systemd/system/${APP_NAME}.service"
JAR_GLOB="${APP_DIR}/target/${APP_NAME}-*.jar"
SPRING_PROFILE="${SPRING_PROFILE:-dev}"

# JVM 参数（阿里云 ECS 2C/2G 实测合理值）
# Xmx512m: 堆上限，RSS 实际 ≈ Xmx + 250MB ≈ 762MB
# MaxMetaspaceSize=192m: 防止 Spring/Hibernate 类元数据膨胀
# ExitOnOutOfMemoryError: JVM 内 OOM 立刻退出，让 systemd 10s 内拉起
# HeapDumpOnOutOfMemoryError: 留 hprof，事后用 Eclipse MAT 分析
JVM_FLAGS="-Xmx512m -Xms256m -XX:MaxMetaspaceSize=192m -XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=${LOG_DIR}/ -Djava.security.egd=file:/dev/./urandom"

# cgroup 内存限制
CGROUP_MEMORY_HIGH="800M"      # 软告警，到达后内核优先回收本进程
CGROUP_MEMORY_MAX="950M"        # 硬上限；仍 < 物理 2GB 一半，OS 不会被拖死

# 命令行选项
SKIP_USER=false
SKIP_UNIT=false
SKIP_PACKAGE=false
for arg in "$@"; do
  case "$arg" in
    --no-user)    SKIP_USER=true ;;
    --no-unit)    SKIP_UNIT=true ;;
    --no-package) SKIP_PACKAGE=true ;;
    -h|--help)
      grep -E '^# ' "$0" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    *) echo "[deploy] unknown arg: $arg"; exit 2 ;;
  esac
done

log() { echo -e "\033[1;32m[deploy]\033[0m $*"; }
warn() { echo -e "\033[1;33m[deploy]\033[0m $*" >&2; }
die() { echo -e "\033[1;31m[deploy]\033[0m $*" >&2; exit 1; }

# ── 0. 前置检查 ───────────────────────────────────────────────────────────
[[ $EUID -eq 0 ]] || die "请用 root 或 sudo 运行（需创建用户、写 systemd unit）"
[[ -d "$APP_DIR" ]] || die "APP_DIR=$APP_DIR 不存在；先 git clone 到该路径"
[[ -x "$APP_DIR/mvnw" ]] || die "$APP_DIR/mvnw 不可执行"
command -v java >/dev/null 2>&1 || die "未安装 java；请先 'dnf install -y java-latest-openjdk-headless'"
command -v systemctl >/dev/null 2>&1 || die "无 systemd；本脚本依赖 systemd"

# 检查 application-dev.yaml 存在
DEV_CONFIG="$APP_DIR/src/main/resources/application-${SPRING_PROFILE}.yaml"
[[ -f "$DEV_CONFIG" ]] || die "缺少配置文件 $DEV_CONFIG（业务配置直接写在 yaml 中，不依赖环境变量）"
log "配置文件: $DEV_CONFIG"

# ── 1. 创建系统用户（无登录 shell，无 home，专跑应用） ─────────────────────
if ! $SKIP_USER && ! id -u "$APP_USER" >/dev/null 2>&1; then
  log "创建系统用户 $APP_USER"
  useradd --system --no-create-home --shell /sbin/nologin "$APP_USER"
else
  log "用户 $APP_USER 已存在，跳过"
fi

# ── 1.5 确保 APP_USER 拥有可写的 HOME ─────────────────────────────────────
APP_USER_HOME=$(getent passwd "$APP_USER" | awk -F: '{print $6}')
if [[ -n "$APP_USER_HOME" && ! -d "$APP_USER_HOME" ]]; then
  log "创建 $APP_USER 的 HOME 目录 $APP_USER_HOME"
  mkdir -p "$APP_USER_HOME"
  chown "$APP_USER:$APP_USER" "$APP_USER_HOME"
  chmod 750 "$APP_USER_HOME"
fi

# ── 2. 日志目录 ────────────────────────────────────────────────────────────
log "准备日志目录 $LOG_DIR"
mkdir -p "$LOG_DIR"
chown "$APP_USER:$APP_USER" "$LOG_DIR"
chmod 750 "$LOG_DIR"

# ── 2.5 APP_DIR 可达性（appuser 需进入并读取打包源码） ─────────────────────
APP_DIR_PARENT="$(dirname "$APP_DIR")"
if [[ "$APP_DIR_PARENT" == "/root" ]]; then
  current_mode=$(stat -c '%a' /root)
  if [[ "${current_mode: -1}" -lt 1 ]]; then
    log "为 /root 追加 o+x（仅可穿越，不可 ls），让 $APP_USER 能进入 $APP_DIR"
    chmod o+x /root
  fi
fi
log "调整 $APP_DIR 属主为 $APP_USER（递归）"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"

# ── 3. 探测 JAVA_HOME（打包阶段需透传给 sudo；systemd unit ExecStart 也要用） ─
if [[ -z "${JAVA_HOME:-}" ]]; then
  JAVA_BIN=$(command -v java || true)
  if [[ -z "$JAVA_BIN" ]]; then
    die "未找到 java，且 JAVA_HOME 未设置；请先安装 JDK 或 export JAVA_HOME"
  fi
  JAVA_REAL=$(readlink -f "$JAVA_BIN")
  JAVA_HOME=$(dirname "$(dirname "$JAVA_REAL")")
fi
[[ -x "$JAVA_HOME/bin/java" ]] || die "推断到的 JAVA_HOME=$JAVA_HOME 无效（缺 bin/java）"
log "使用 JAVA_HOME=$JAVA_HOME"

# ── 4. 打包（除非 --no-package） ───────────────────────────────────────────
if ! $SKIP_PACKAGE; then
  log "打包 jar（./mvnw -q -DskipTests package）"
  pushd "$APP_DIR" >/dev/null
  sudo -u "$APP_USER" env \
      HOME="$APP_DIR" \
      JAVA_HOME="$JAVA_HOME" \
      PATH="$JAVA_HOME/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin" \
      ./mvnw -q -DskipTests package \
    || die "Maven 打包失败"
  popd >/dev/null
fi

# 打包产物所有权 → APP_USER（systemd 以该用户运行 java -jar）
chown -R "$APP_USER:$APP_USER" "$APP_DIR/target"

JAR_PATH=$(ls -1t $JAR_GLOB 2>/dev/null | head -1 || true)
[[ -n "$JAR_PATH" && -f "$JAR_PATH" ]] || die "找不到 jar：$JAR_GLOB"
log "目标 jar: $JAR_PATH"

# ── 5. systemd unit ───────────────────────────────────────────────────────
if [[ "$APP_DIR" == /root/* || "$APP_DIR" == /home/* ]]; then
  PROTECT_HOME_LINE="# ProtectHome 已禁用：APP_DIR ($APP_DIR) 位于 /root 或 /home 下"
else
  PROTECT_HOME_LINE="ProtectHome=true"
fi

if ! $SKIP_UNIT; then
  log "写入 systemd unit: $UNIT_FILE"
  cat > "$UNIT_FILE" <<EOF
[Unit]
Description=${APP_NAME} (Spring Boot)
Documentation=file://${APP_DIR}/AGENTS.md
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=120
StartLimitBurst=5

[Service]
Type=simple
User=${APP_USER}
Group=${APP_USER}
WorkingDirectory=${APP_DIR}

# JVM 参数 + jar 路径 + Spring profile（业务配置在 application-${SPRING_PROFILE}.yaml）
ExecStart=${JAVA_HOME}/bin/java ${JVM_FLAGS} -jar ${JAR_PATH} --spring.profiles.active=${SPRING_PROFILE}

# 守护：被杀立即拉起
Restart=always
RestartSec=10s

# cgroup 内存 — 必须 < 物理内存，让 OOM-Killer 命中本进程而非 sshd/postgres
MemoryHigh=${CGROUP_MEMORY_HIGH}
MemoryMax=${CGROUP_MEMORY_MAX}
TasksMax=512
LimitNOFILE=65536

# 安全沙盒
NoNewPrivileges=true
ProtectSystem=full
${PROTECT_HOME_LINE}
PrivateTmp=true
ReadWritePaths=${LOG_DIR}

# 日志走 journald（journalctl -u ${APP_NAME} -f 实时查看）
StandardOutput=journal
StandardError=journal
SyslogIdentifier=${APP_NAME}

[Install]
WantedBy=multi-user.target
EOF

  systemctl daemon-reload
fi

# ── 6. 启用并启动 ─────────────────────────────────────────────────────────
log "enable + restart ${APP_NAME}"
systemctl enable "${APP_NAME}.service" >/dev/null
systemctl restart "${APP_NAME}.service"

sleep 3
if systemctl is-active --quiet "${APP_NAME}.service"; then
  log "✅ ${APP_NAME} 已启动"
  systemctl --no-pager status "${APP_NAME}.service" | head -15
  echo
  log "实时日志：journalctl -u ${APP_NAME} -f"
  log "RSS 观察：pmap -x \$(systemctl show -p MainPID --value ${APP_NAME}) | tail -1"
else
  warn "❌ 启动失败，最近 50 行日志："
  journalctl -u "${APP_NAME}.service" -n 50 --no-pager
  exit 1
fi
