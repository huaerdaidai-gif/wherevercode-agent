#!/bin/sh
# WhereverCode V5 Hand 一键安装脚本（Mac / Linux）。
#
# V5 原生：只装 V5 Hand（hand_server.py，单文件 Python 服务），
# 不装旧 Execution Host、不连旧服务器、不从网上下载文件——
# 安装包（本脚本 + hand_server.py）由 App 经 SSH 一起传过来，
# 或由用户自己拷到目标机上。老项目只是思路参考（见下“安全机制”），
# 不是模板：本脚本按 V5 自己的设计写，不照搬 install_host.sh。
#
# 安全机制（学老项目思路，原生实现）：
#   state dir 0700、标记文件、版本化 releases、current 原子切换、
#   健康检查（端口接通 + 指纹可读）、保留一版回滚、幂等更新
#   （已装同版本只重启、不重新初始化身份）。
#
# 用法（在目标机上跑）：
#   sh v5-install-hand.sh --version <版本> [--state-dir DIR]
#       [--port 18743] [--device-id ID] [--display-name NAME]
#       [--bundle-dir DIR]
#   --bundle-dir：hand_server.py 所在目录，默认本脚本所在目录。
#   --version：必填，release 名，只许 [A-Za-z0-9._-]{1,80}。
#   成功时最后一行打印（供 App 解析）：
#     V5_HAND_INSTALLED device_id=... fingerprint=... port=... version=... state_dir=... python=...
#   失败时退出非 0，并在 stderr 说明原因，已装版本不受影响。
set -eu
umask 077

VERSION=""
STATE_DIR_OVERRIDE=""
PORT="18743"
DEVICE_ID=""
DISPLAY_NAME=""
BUNDLE_DIR_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --version) VERSION="$2"; shift 2;;
    --state-dir) STATE_DIR_OVERRIDE="$2"; shift 2;;
    --port) PORT="$2"; shift 2;;
    --device-id) DEVICE_ID="$2"; shift 2;;
    --display-name) DISPLAY_NAME="$2"; shift 2;;
    --bundle-dir) BUNDLE_DIR_OVERRIDE="$2"; shift 2;;
    *) echo "未知参数：$1" >&2; exit 2;;
  esac
done
case "$VERSION" in
  *[!A-Za-z0-9._-]*|"") echo "请提供 --version（只许字母/数字/./_/-，1~80 字符）。" >&2; exit 2;;
esac
[ "${#VERSION}" -le 80 ] || { echo "--version 太长（最多 80 字符）。" >&2; exit 2; }
case "$PORT" in ''|*[!0-9]*) echo "--port 必须是数字。" >&2; exit 2;; esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || { echo "--port 必须在 1~65535 之间。" >&2; exit 2; }

# --- 安装包位置：hand_server.py 须与本脚本在同一目录（或 --bundle-dir）。---
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd -P)
BUNDLE_DIR=${BUNDLE_DIR_OVERRIDE:-$SCRIPT_DIR}
[ -f "$BUNDLE_DIR/hand_server.py" ] || { echo "安装包里没有 hand_server.py（应在 $BUNDLE_DIR 里）。" >&2; exit 1; }

# --- 环境：python3 ≥3.10 + cryptography。---
for binary in python3; do command -v "$binary" >/dev/null 2>&1 || { echo "请先安装 ${binary}。" >&2; exit 1; }; done
PYTHON_BIN=$(command -v python3)
"$PYTHON_BIN" -c 'import sys; assert sys.version_info >= (3,10)' || { echo "需要 Python 3.10+。" >&2; exit 1; }
if "$PYTHON_BIN" -c 'import cryptography' 2>/dev/null; then
  HAND_PYTHON="$PYTHON_BIN"
else
  echo "本机 Python 没有 cryptography，尝试装一个隔离环境……" >&2
  HAND_PYTHON=""
fi

# --- 状态目录：Darwin 默认 ~/Library/Application Support/WhereverCode/V5Hand；
#     Linux 默认 ${XDG_DATA_HOME:-$HOME/.local/share}/wherevercode/v5-hand。
#     与旧 Execution Host 的目录不同，不混用、不覆盖旧客户端。---
case "$(uname -s)" in Darwin) DEFAULT_DIR="$HOME/Library/Application Support/WhereverCode/V5Hand";; *) DEFAULT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/wherevercode/v5-hand";; esac
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

# --- cryptography 缺失时：建隔离 venv 装一个；装不上就明说、退出，不静默继续。---
if [ -z "$HAND_PYTHON" ]; then
  VENV_DIR="$HOST_DIR/.venv"
  if [ -x "$VENV_DIR/bin/python" ] && "$VENV_DIR/bin/python" -c 'import cryptography' 2>/dev/null; then
    HAND_PYTHON="$VENV_DIR/bin/python"
  elif "$PYTHON_BIN" -m venv "$VENV_DIR" 2>/dev/null && "$VENV_DIR/bin/python" -m pip install -q cryptography 2>/dev/null && "$VENV_DIR/bin/python" -c 'import cryptography' 2>/dev/null; then
    HAND_PYTHON="$VENV_DIR/bin/python"
  elif "$PYTHON_BIN" -m pip install -q --user cryptography 2>/dev/null && "$PYTHON_BIN" -c 'import cryptography' 2>/dev/null; then
    HAND_PYTHON="$PYTHON_BIN"
  else
    echo "装不上 cryptography：请在目标机上手动跑 'python3 -m pip install cryptography'（或建 venv 再装），然后重跑本脚本。" >&2
    exit 1
  fi
fi

# --- 版本化 release：releases/<version>/hand_server.py，current 原子切换，
#     保留当前版 + 上一版（回滚），其余删掉。---
RELEASE_DIR="$HOST_DIR/releases/$VERSION"
mkdir -p "$RELEASE_DIR"; chmod 700 "$HOST_DIR/releases" "$RELEASE_DIR"
cp "$BUNDLE_DIR/hand_server.py" "$RELEASE_DIR/hand_server.py.tmp"
chmod 600 "$RELEASE_DIR/hand_server.py.tmp"
mv "$RELEASE_DIR/hand_server.py.tmp" "$RELEASE_DIR/hand_server.py"
# 回滚保留：先记下旧 current 指的版本（回滚版），再切新版。
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
# 清洗：device-id 不能含空格/引号/换行（它是配对标识）；显示名不能含
# 双引号/换行（它会被写进 systemd ExecStart 的双引号里，含引号会拆散参数）。
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

# --- 后台服务：Mac launchd / Linux systemd；都没有时 nohup 兜底（重启不自启，明说）。---
SERVICE="none"
case "$(uname -s)" in
Darwin)
  mkdir -p "$HOME/Library/LaunchAgents"
  LABEL=com.wherevercode.v5-hand
  PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
  CLI_PATH="$(dirname "$HAND_PYTHON"):/opt/homebrew/bin:/usr/local/bin:$HOME/.local/bin"
  cat > "$PLIST.new" <<EOF2
<?xml version="1.0"?><plist version="1.0"><dict><key>Label</key><string>$LABEL</string><key>ProgramArguments</key><array><string>$HAND_PYTHON</string><string>$HOST_DIR/current/hand_server.py</string><string>--state-dir</string><string>$HOST_DIR</string><string>--device-id</string><string>$DEVICE_ID</string><string>--display-name</string><string>$DISPLAY_NAME</string><string>--host</string><string>0.0.0.0</string><string>--port</string><string>$PORT</string><string>serve</string></array><key>EnvironmentVariables</key><dict><key>PATH</key><string>$CLI_PATH</string><key>WHEREVERCODE_V5_HAND_STATE_DIR</key><string>$HOST_DIR</string></dict><key>StandardOutPath</key><string>$HOST_DIR/v5-hand.log</string><key>StandardErrorPath</key><string>$HOST_DIR/v5-hand.log</string><key>RunAtLoad</key><true/><key>KeepAlive</key><true/></dict></plist>
EOF2
  chmod 600 "$PLIST.new"
  if [ -f "$PLIST" ] && cmp -s "$PLIST.new" "$PLIST" && launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then
    rm -f "$PLIST.new"; launchctl kickstart -k "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
  else
    [ ! -f "$PLIST" ] || cp -p "$PLIST" "$PLIST.install-backup"
    mv -f "$PLIST.new" "$PLIST"; launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true; launchctl bootstrap "gui/$(id -u)" "$PLIST"
  fi
  launchctl kickstart "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
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
PAIR_CODE=$("$HAND_PYTHON" "$HOST_DIR/current/hand_server.py" --state-dir "$HOST_DIR" pair-code 2>/dev/null | tr -d ' \n\r')
echo "V5_HAND_INSTALLED device_id=$DEVICE_ID fingerprint=$FINGERPRINT pair_code=$PAIR_CODE port=$PORT version=$VERSION state_dir=$HOST_DIR python=$HAND_PYTHON service=$SERVICE"
echo "V5 Hand 已安装并启动（$SERVICE）。App 里核对指纹一致、填入配对码 $PAIR_CODE 后确认配对。"
