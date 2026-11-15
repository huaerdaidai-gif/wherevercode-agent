#!/bin/sh
# WhereverCode V5 Hand 一键安装脚本（Mac / Linux）。
#
# V5 原生：只装 V5 Hand（hand_server.py，单文件 Python 服务），
# 不装旧 Execution Host、不连旧服务器。
# 支持两种安装模式：
#   (1) 离线/内嵌包模式：hand_server.py 与本脚本在同一目录或由 --bundle-dir 指定；
#   (2) 一键在线/远程模式：从任意目录或通过 curl 执行时，自动从官方可信发布源
#       安全下载 hand_server.py，并进行 SHA-256 完整性及语法校验。
#
# 安全机制：
#   state dir 0700、标记文件、版本化 releases、current 原子切换、
#   依赖环境隔离（.venv + cryptography）、端口冲突自动接管、
#   健康检查（端口接通 + 指纹可读）、保留一版回滚、幂等更新。
#
# 用法：
#   sh v5-install-hand.sh --version <版本> [--state-dir DIR]
#       [--port 18743] [--device-id ID] [--display-name NAME]
#       [--bundle-dir DIR] [--base-url URL] [--checksum SHA256]
#
# 成功时最后一行打印（供 App 解析）：
#   V5_HAND_INSTALLED device_id=... fingerprint=... pair_code=... port=... version=... state_dir=... python=... service=...
# 失败时退出非 0，并在 stderr 说明原因，已装版本不受影响。
set -eu
umask 077

VERSION=""
STATE_DIR_OVERRIDE=""
PORT="18743"
DEVICE_ID=""
DISPLAY_NAME=""
BUNDLE_DIR_OVERRIDE=""
BASE_URL=""
CHECKSUM=""

while [ $# -gt 0 ]; do
  case "$1" in
    --version|-version|–version|—version) VERSION="$2"; shift 2;;
    --state-dir|-state-dir|–state-dir|—state-dir) STATE_DIR_OVERRIDE="$2"; shift 2;;
    --port|-port|–port|—port) PORT="$2"; shift 2;;
    --device-id|-device-id|–device-id|—device-id) DEVICE_ID="$2"; shift 2;;
    --display-name|-display-name|–display-name|—display-name) DISPLAY_NAME="$2"; shift 2;;
    --bundle-dir|-bundle-dir|–bundle-dir|—bundle-dir) BUNDLE_DIR_OVERRIDE="$2"; shift 2;;
    --base-url|-base-url|–base-url|—base-url) BASE_URL="$2"; shift 2;;
    --checksum|-checksum|–checksum|—checksum) CHECKSUM="$2"; shift 2;;
    *) echo "未知参数：$1" >&2; exit 2;;
  esac
done

case "$VERSION" in
  *[!A-Za-z0-9._-]*|"") echo "请提供 --version（只许字母/数字/./_/-，1~80 字符）。" >&2; exit 2;;
esac
[ "${#VERSION}" -le 80 ] || { echo "--version 太长（最多 80 字符）。" >&2; exit 2; }
case "$PORT" in ''|*[!0-9]*) echo "--port 必须是数字。" >&2; exit 2;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || { echo "--port 必须在 1~65535 之间。" >&2; exit 2; }

# --- 环境：python3 ≥3.10。---
for binary in python3; do
  command -v "$binary" >/dev/null 2>&1 || { echo "请先安装 ${binary}。" >&2; exit 1; }
done
PYTHON_BIN=$(command -v python3)
"$PYTHON_BIN" -c 'import sys; assert sys.version_info >= (3,10)' || { echo "需要 Python 3.10+。" >&2; exit 1; }

# --- 状态目录初始化 ---
case "$(uname -s)" in
  Darwin) DEFAULT_DIR="$HOME/Library/Application Support/WhereverCode/V5Hand";;
  *) DEFAULT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/wherevercode/v5-hand";;
esac
HOST_DIR=${STATE_DIR_OVERRIDE:-$DEFAULT_DIR}
[ ! -L "$HOST_DIR" ] || { echo "设备状态目录不能是符号链接。" >&2; exit 2; }
mkdir -p "$HOST_DIR"; chmod 700 "$HOST_DIR"
HOST_DIR=$(CDPATH= cd "$HOST_DIR" && pwd -P)
MARKER="$HOST_DIR/.wherevercode-v5-hand-state"
if [ -f "$MARKER" ]; then
  [ "$(cat "$MARKER")" = 'WhereverCode V5 Hand state v1' ] || { echo "设备状态目录标记无效。" >&2; exit 2; }
elif [ -f "$HOST_DIR/server_key.json" ] || [ -z "$(find "$HOST_DIR" -mindepth 1 -maxdepth 1 -print -quit)" ]; then
  printf '%s\n' 'WhereverCode V5 Hand state v1' > "$MARKER"; chmod 600 "$MARKER"
else
  echo "指定目录不是空目录或已安装的 V5 Hand 目录；为避免覆盖其他文件，安装已停止。" >&2; exit 2
fi

# --- cryptography 依赖管理：优先系统/现有 venv，缺失时自动创建隔离环境并安装 ---
HAND_PYTHON=""
if "$PYTHON_BIN" -c 'import cryptography' 2>/dev/null; then
  HAND_PYTHON="$PYTHON_BIN"
fi

if [ -z "$HAND_PYTHON" ]; then
  VENV_DIR="$HOST_DIR/.venv"
  if [ -x "$VENV_DIR/bin/python" ] && "$VENV_DIR/bin/python" -c 'import cryptography' 2>/dev/null; then
    HAND_PYTHON="$VENV_DIR/bin/python"
  else
    echo "本机 Python 未检测到 cryptography 依赖，正在配置隔离运行环境 (.venv)……" >&2
    rm -rf "$VENV_DIR"
    if "$PYTHON_BIN" -m venv "$VENV_DIR" 2>/dev/null && "$VENV_DIR/bin/pip" install --prefer-binary -q cryptography 2>/dev/null; then
      if "$VENV_DIR/bin/python" -c 'import cryptography' 2>/dev/null; then
        HAND_PYTHON="$VENV_DIR/bin/python"
        echo "隔离运行环境与 cryptography 依赖就绪。" >&2
      fi
    fi
  fi
fi

if [ -z "$HAND_PYTHON" ]; then
  if "$PYTHON_BIN" -m pip install -q --user cryptography 2>/dev/null && "$PYTHON_BIN" -c 'import cryptography' 2>/dev/null; then
    HAND_PYTHON="$PYTHON_BIN"
  else
    echo "装不上 cryptography：请在目标机上手动跑 'python3 -m pip install cryptography'（或建 venv 再装），然后重跑本脚本。" >&2
    exit 1
  fi
fi

# --- 定位或自动下载 hand_server.py（支持任意工作目录一键安装）---
HAND_SERVER_SRC=""

# 1. 优先检查用户指定的 --bundle-dir
if [ -n "$BUNDLE_DIR_OVERRIDE" ] && [ -f "$BUNDLE_DIR_OVERRIDE/hand_server.py" ]; then
  HAND_SERVER_SRC="$BUNDLE_DIR_OVERRIDE/hand_server.py"
fi

# 2. 检查脚本自身所在目录
if [ -z "$HAND_SERVER_SRC" ]; then
  SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" 2>/dev/null && pwd -P || true)
  if [ -n "$SCRIPT_DIR" ] && [ -f "$SCRIPT_DIR/hand_server.py" ]; then
    HAND_SERVER_SRC="$SCRIPT_DIR/hand_server.py"
  fi
fi

# 3. 检查当前工作目录
if [ -z "$HAND_SERVER_SRC" ] && [ -f "$PWD/hand_server.py" ]; then
  HAND_SERVER_SRC="$PWD/hand_server.py"
fi

# 4. 检查常见项目/仓库路径
if [ -z "$HAND_SERVER_SRC" ]; then
  for candidate in \
    "${SCRIPT_DIR:-.}/../../../../android/app/src/main/assets/install/hand_server.py" \
    "${SCRIPT_DIR:-.}/../../../../server/hand_server.py" \
    "${SCRIPT_DIR:-.}/../assets/install/hand_server.py" \
    "$HOME/wherevercode-agent/android/app/src/main/assets/install/hand_server.py" \
    "$HOME/wherevercode-agent/server/hand_server.py" \
    "$HOME/Projects/wherevercode-agent/android/app/src/main/assets/install/hand_server.py" \
    "$HOME/Projects/wherevercode-agent/server/hand_server.py"; do
    if [ -f "$candidate" ]; then
      HAND_SERVER_SRC="$candidate"
      break
    fi
  done
fi

# 5. 若本地均未找到，自动从官方可信发布源下载
if [ -z "$HAND_SERVER_SRC" ]; then
  echo "本地未找到 hand_server.py，正在从官方发布源自动下载……" >&2
  DL_DIR="$HOST_DIR/downloads"
  mkdir -p "$DL_DIR"; chmod 700 "$DL_DIR"
  TARGET_DL="$DL_DIR/hand_server.py"

  DEFAULT_BASE_URL="https://raw.githubusercontent.com/huaerdaidai-gif/wherevercode-agent/main/android/app/src/main/assets/install"
  FETCH_BASE="${BASE_URL:-$DEFAULT_BASE_URL}"
  FETCH_BASE="${FETCH_BASE%/}"

  URL_CANDIDATES="\
$FETCH_BASE/hand_server.py
https://cdn.jsdelivr.net/gh/huaerdaidai-gif/wherevercode-agent@main/android/app/src/main/assets/install/hand_server.py
https://raw.githubusercontent.com/huaerdaidai-gif/wherevercode-agent/main/server/hand_server.py"

  DOWNLOADED=0
  for dl_url in $URL_CANDIDATES; do
    echo "尝试获取: $dl_url" >&2
    if command -v curl >/dev/null 2>&1; then
      if curl -fsSL --connect-timeout 10 --max-time 60 "$dl_url" -o "$TARGET_DL.tmp" 2>/dev/null; then
        mv -f "$TARGET_DL.tmp" "$TARGET_DL"
        DOWNLOADED=1
        break
      fi
    elif command -v wget >/dev/null 2>&1; then
      if wget -q --timeout=10 -O "$TARGET_DL.tmp" "$dl_url" 2>/dev/null; then
        mv -f "$TARGET_DL.tmp" "$TARGET_DL"
        DOWNLOADED=1
        break
      fi
    fi
    rm -f "$TARGET_DL.tmp"
  done

  if [ "$DOWNLOADED" -eq 1 ] && [ -f "$TARGET_DL" ]; then
    HAND_SERVER_SRC="$TARGET_DL"
    echo "成功下载 hand_server.py。" >&2
  else
    echo "从官方发布源下载 hand_server.py 失败；请检查网络或使用 --bundle-dir 指定包含 hand_server.py 的本地目录。" >&2
    exit 1
  fi
fi

# --- 完整性与安全语法验证 ---
[ -f "$HAND_SERVER_SRC" ] || { echo "无法读取 hand_server.py：$HAND_SERVER_SRC" >&2; exit 1; }
FILE_SIZE=$(wc -c < "$HAND_SERVER_SRC" | tr -d ' ')
[ "$FILE_SIZE" -gt 50000 ] || { echo "hand_server.py 文件不完整（大小仅 $FILE_SIZE 字节）。" >&2; exit 1; }

"$HAND_PYTHON" -m py_compile "$HAND_SERVER_SRC" || { echo "hand_server.py 语法校验失败。" >&2; exit 1; }

CALC_SHA=$("$HAND_PYTHON" -c "import hashlib; print(hashlib.sha256(open('$HAND_SERVER_SRC', 'rb').read()).hexdigest())")
if [ -n "$CHECKSUM" ]; then
  if [ "$CALC_SHA" != "$CHECKSUM" ]; then
    echo "SHA-256 校验不匹配：期望 $CHECKSUM，实际 $CALC_SHA" >&2
    exit 1
  fi
  echo "文件完整性校验通过 (SHA-256: $CALC_SHA)。" >&2
else
  echo "已验证文件完整性 (SHA-256: $CALC_SHA)。" >&2
fi

# --- 版本化 release：releases/<version>/hand_server.py，current 原子切换 ---
RELEASE_DIR="$HOST_DIR/releases/$VERSION"
mkdir -p "$RELEASE_DIR"; chmod 700 "$HOST_DIR/releases" "$RELEASE_DIR"
cp "$HAND_SERVER_SRC" "$RELEASE_DIR/hand_server.py.tmp"
chmod 600 "$RELEASE_DIR/hand_server.py.tmp"
mv -f "$RELEASE_DIR/hand_server.py.tmp" "$RELEASE_DIR/hand_server.py"

OLD_RELEASE=""
if [ -L "$HOST_DIR/current" ]; then
  OLD_RELEASE=$(basename "$(readlink "$HOST_DIR/current")" 2>/dev/null || true)
fi

"$HAND_PYTHON" - "$RELEASE_DIR" "$HOST_DIR/current" <<'PY'
import os, sys
release, destination = sys.argv[1:]
temporary = destination + '.new'
try: os.unlink(temporary)
except FileNotFoundError: pass
os.symlink(release, temporary)
os.replace(temporary, destination)
PY

# 只留新版 + 旧版（回滚），其余删掉。
"$HAND_PYTHON" - "$HOST_DIR/releases" "$VERSION" "$OLD_RELEASE" <<'PY'
import os, shutil, sys
root, keep, old = sys.argv[1], sys.argv[2], sys.argv[3]
keep_set = {keep} | ({old} if old else set())
for name in os.listdir(root):
    path = os.path.join(root, name)
    if name not in keep_set and os.path.isdir(path) and not os.path.islink(path):
        shutil.rmtree(path, ignore_errors=True)
PY

DEVICE_ID=${DEVICE_ID:-$(hostname)}
DISPLAY_NAME=${DISPLAY_NAME:-$(hostname)}
DEVICE_ID="$(printf '%s' "$DEVICE_ID" | tr -d " '\"" | tr -d '\n\r')"
DISPLAY_NAME="$(printf '%s' "$DISPLAY_NAME" | tr -d "'\"" | tr -d '\n\r')"
[ -n "$DEVICE_ID" ] || DEVICE_ID="$(hostname)"
[ -n "$DISPLAY_NAME" ] || DISPLAY_NAME="$(hostname)"

# --- 启动器：wherevercode-hand（固定 python + current + 参数）。---
cat > "$HOST_DIR/wherevercode-hand" <<EOF2
#!/bin/sh
exec '$HAND_PYTHON' '$HOST_DIR/current/hand_server.py' --state-dir '$HOST_DIR' --device-id '$DEVICE_ID' --display-name '$DISPLAY_NAME' --host 0.0.0.0 --port $PORT "\$@"
EOF2
chmod 700 "$HOST_DIR/wherevercode-hand"
mkdir -p "$HOME/.local/bin"
ln -sf "$HOST_DIR/wherevercode-hand" "$HOME/.local/bin/wherevercode-hand"

# --- 端口冲突预防与清理 ---
case "$(uname -s)" in
Darwin)
  for old_label in com.wherevercode.hand com.wherevercode.v5-hand; do
    launchctl bootout "gui/$(id -u)/$old_label" >/dev/null 2>&1 || true
  done
  ;;
Linux)
  if command -v systemctl >/dev/null 2>&1; then
    systemctl --user stop wherevercode-v5-hand.service >/dev/null 2>&1 || true
    systemctl --user stop wherevercode-hand.service >/dev/null 2>&1 || true
  fi
  ;;
esac

if command -v lsof >/dev/null 2>&1; then
  PORT_PID=$(lsof -ti :"$PORT" 2>/dev/null || true)
  if [ -n "$PORT_PID" ]; then
    kill -TERM $PORT_PID 2>/dev/null || true
    sleep 1
  fi
fi

# --- 后台服务注册与启动 ---
SERVICE="none"
case "$(uname -s)" in
Darwin)
  mkdir -p "$HOME/Library/LaunchAgents"
  LABEL=com.wherevercode.v5-hand
  PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
  CLI_PATH="$(dirname "$HAND_PYTHON"):/opt/homebrew/bin:/usr/local/bin:$HOME/.local/bin:$PATH"
  cat > "$PLIST.new" <<EOF2
<?xml version="1.0"?><plist version="1.0"><dict><key>Label</key><string>$LABEL</string><key>ProgramArguments</key><array><string>$HAND_PYTHON</string><string>$HOST_DIR/current/hand_server.py</string><string>--state-dir</string><string>$HOST_DIR</string><string>--device-id</string><string>$DEVICE_ID</string><string>--display-name</string><string>$DISPLAY_NAME</string><string>--host</string><string>0.0.0.0</string><string>--port</string><string>$PORT</string><string>serve</string></array><key>EnvironmentVariables</key><dict><key>PATH</key><string>$CLI_PATH</string><key>WHEREVERCODE_V5_HAND_STATE_DIR</key><string>$HOST_DIR</string></dict><key>StandardOutPath</key><string>$HOST_DIR/v5-hand.log</string><key>StandardErrorPath</key><string>$HOST_DIR/v5-hand.log</string><key>RunAtLoad</key><true/><key>KeepAlive</key><true/></dict></plist>
EOF2
  chmod 600 "$PLIST.new"
  mv -f "$PLIST.new" "$PLIST"
  launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
  launchctl bootstrap "gui/$(id -u)" "$PLIST" >/dev/null 2>&1 || launchctl load "$PLIST" >/dev/null 2>&1 || true
  launchctl kickstart -k "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
  SERVICE="launchd";;
Linux)
  if command -v systemctl >/dev/null 2>&1 && systemctl --user show-environment >/dev/null 2>&1; then
    UNIT_DIR="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user"; mkdir -p "$UNIT_DIR"
    cat > "$UNIT_DIR/wherevercode-v5-hand.service" <<EOF2
[Unit]
Description=WhereverCode V5 Hand
[Service]
ExecStart=$HAND_PYTHON $HOST_DIR/current/hand_server.py --state-dir "$HOST_DIR" --device-id "$DEVICE_ID" --display-name "$DISPLAY_NAME" --host 0.0.0.0 --port $PORT serve
Environment=WHEREVERCODE_V5_HAND_STATE_DIR=$HOST_DIR
Environment=PATH=$HOME/.local/bin:$PATH
Restart=always
UMask=0077
[Install]
WantedBy=default.target
EOF2
    systemctl --user daemon-reload; systemctl --user enable --now wherevercode-v5-hand.service
    if command -v loginctl >/dev/null 2>&1; then loginctl enable-linger "$(id -un)" || echo "无法启用 linger；退出 SSH 后服务可能停止。" >&2; else echo "缺少 loginctl；退出 SSH 后服务可能停止。" >&2; fi
    SERVICE="systemd"
  else
    echo "未检测到可用 systemd 用户服务；改用 nohup 后台启动，重启后请重跑 wherevercode-hand serve。" >&2
    nohup "$HOST_DIR/wherevercode-hand" serve >>"$HOST_DIR/v5-hand.log" 2>&1 &
    echo $! > "$HOST_DIR/hand.pid"; chmod 600 "$HOST_DIR/hand.pid"
    SERVICE="nohup"
  fi;;
*)
  echo "未知系统 $(uname -s)；改用 nohup 后台启动，重启后请重跑 wherevercode-hand serve。" >&2
  nohup "$HOST_DIR/wherevercode-hand" serve >>"$HOST_DIR/v5-hand.log" 2>&1 &
  echo $! > "$HOST_DIR/hand.pid"; chmod 600 "$HOST_DIR/hand.pid"
  SERVICE="nohup";;
esac

# --- 健康检查：端口接通（最多等 20 秒）+ 指纹可读。---
OK=0
i=0
while [ "$i" -lt 20 ]; do
  if "$HAND_PYTHON" -c "import socket,sys; s=socket.socket(); s.settimeout(2); s.connect(('127.0.0.1', $PORT)); s.close()" 2>/dev/null; then OK=1; break; fi
  sleep 1; i=$((i+1))
done
[ "$OK" = 1 ] || { echo "Hand 服务未在 20 秒内监听端口 $PORT；请看 $HOST_DIR/v5-hand.log。" >&2; exit 1; }
FINGERPRINT=$("$HAND_PYTHON" "$HOST_DIR/current/hand_server.py" --state-dir "$HOST_DIR" fingerprint 2>/dev/null | tr -d ' \n\r' | tail -c 64)
[ -n "$FINGERPRINT" ] || { echo "读不到 Hand 指纹；请看 $HOST_DIR/v5-hand.log。" >&2; exit 1; }
PAIR_CODE=$("$HAND_PYTHON" "$HOST_DIR/current/hand_server.py" --state-dir "$HOST_DIR" pair-code --raw 2>/dev/null | tr -d ' \n\r')
echo "V5_HAND_INSTALLED device_id=${DEVICE_ID} fingerprint=${FINGERPRINT} pair_code=${PAIR_CODE} port=${PORT} version=${VERSION} state_dir=${HOST_DIR} python=${HAND_PYTHON} service=${SERVICE}"
echo "V5 Hand 已安装并启动（${SERVICE}）。App 里核对指纹一致、填入配对码 ${PAIR_CODE}（5 分钟有效，一次性）后确认配对。"
echo "管理命令：运行 wherevercode-hand pair-code 查看状态，或 --rotate 刷新新码。"
