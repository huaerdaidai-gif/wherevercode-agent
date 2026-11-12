#!/bin/sh
# WhereverCode V5 Hand 卸载脚本（Mac / Linux）。
#
# V5 原生：只卸 V5 Hand，不碰旧 Execution Host。老项目只是思路参考
# （停服务门控、标记校验、默认保留数据、授权映射备份），不是模板。
# 不通知任何服务器——配对撤销由 App 在本地做（Pairing REVOKED）。
# 默认保留授权映射备份；任务数据（Hand 本身不存任务，只存审计日志）
# 与授权映射备份到带时间戳的文件后，状态目录才删。
#
# 用法：sh v5-uninstall-hand.sh [--state-dir DIR]
set -eu
umask 077

STATE_DIR_OVERRIDE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --state-dir) STATE_DIR_OVERRIDE="$2"; shift 2;;
    *) echo "未知参数：$1" >&2; exit 2;;
  esac
done
case "$(uname -s)" in Darwin) DEFAULT_DIR="$HOME/Library/Application Support/WhereverCode/V5Hand";; *) DEFAULT_DIR="${XDG_DATA_HOME:-$HOME/.local/share}/wherevercode/v5-hand";; esac
HOST_DIR=${STATE_DIR_OVERRIDE:-$DEFAULT_DIR}
PLIST="$HOME/Library/LaunchAgents/com.wherevercode.v5-hand.plist"
if [ -z "$STATE_DIR_OVERRIDE" ] && [ -f "$PLIST" ] && command -v plutil >/dev/null 2>&1; then
  ACTUAL=$(plutil -extract ProgramArguments.1 raw -o - "$PLIST" 2>/dev/null || true)
  case "$ACTUAL" in */hand_server.py) RELEASE_DIR=$(dirname "$ACTUAL"); case "$RELEASE_DIR" in */releases/*) HOST_DIR=$(dirname "$(dirname "$RELEASE_DIR")");; *) HOST_DIR="$RELEASE_DIR";; esac;; esac
fi
[ -d "$HOST_DIR" ] || { echo "找不到 V5 Hand 配置。"; exit 0; }
[ ! -L "$HOST_DIR" ] || { echo "设备状态目录是符号链接；为避免删除其他目录，已停止卸载。" >&2; exit 2; }
HOST_DIR=$(CDPATH= cd -- "$HOST_DIR" && pwd -P)
case "$HOST_DIR" in /|"$HOME") echo "设备状态目录路径不安全；已停止卸载。" >&2; exit 2;; esac
case "$HOME" in "$HOST_DIR"/*) echo "设备状态目录是主目录的父级；已停止卸载。" >&2; exit 2;; esac
MARKER="$HOST_DIR/.wherevercode-v5-hand-state"
if [ -f "$MARKER" ]; then
  [ "$(cat "$MARKER")" = 'WhereverCode V5 Hand state v1' ] || { echo "设备状态目录标记无效；已停止卸载。" >&2; exit 2; }
else
  case "$HOST_DIR" in "$HOME/Library/Application Support/WhereverCode/V5Hand"|"${XDG_DATA_HOME:-$HOME/.local/share}/wherevercode/v5-hand") :;; *) echo "无法确认这是专用 V5 Hand 目录；为避免删除其他文件，已停止卸载。" >&2; exit 2;; esac
fi

# 先停服务；停不下来就退出 1（不强删），保护运行中的任务。
case "$(uname -s)" in
Darwin)
  if [ -f "$PLIST" ]; then
    LABEL=$(plutil -extract Label raw -o - "$PLIST" 2>/dev/null || true)
    [ -z "$LABEL" ] || launchctl bootout "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || true
    if [ -n "$LABEL" ] && launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1; then
      echo "Hand 服务仍在运行；为保护数据，已停止卸载。" >&2; exit 1
    fi
    rm -f "$PLIST"
  fi;;
Linux)
  UNIT_FILE="${XDG_CONFIG_HOME:-$HOME/.config}/systemd/user/wherevercode-v5-hand.service"
  if [ -f "$UNIT_FILE" ] && command -v systemctl >/dev/null 2>&1; then
    systemctl --user disable --now wherevercode-v5-hand.service >/dev/null 2>&1 || true
    if systemctl --user is-active --quiet wherevercode-v5-hand.service; then
      echo "Hand 服务仍在运行；为保护数据，已停止卸载。" >&2; exit 1
    fi
    rm -f "$UNIT_FILE"
  fi;;
esac
if [ -f "$HOST_DIR/hand.pid" ]; then
  PID=$(cat "$HOST_DIR/hand.pid" 2>/dev/null || true)
  case "$PID" in ''|*[!0-9]*) :;; *) kill -TERM "$PID" 2>/dev/null || true;; esac
fi

# 备份授权映射（agents.json / auth.json / paired_clients.json），0600。
# 注意：server_key.json（设备身份）故意不备份——重装即新身份，须重新配对。
BACKUP="$HOME/WhereverCode-v5hand-backup-$(date +%Y%m%d%H%M%S)-$$.json"
python3 - "$HOST_DIR" "$BACKUP" <<'PY'
import json, os, sys
state_dir, target = sys.argv[1:]
out = {}
for name in ("agents.json", "auth.json", "paired_clients.json"):
    path = os.path.join(state_dir, name)
    if os.path.isfile(path):
        try: out[name] = json.load(open(path, encoding="utf-8"))
        except Exception: out[name] = None
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
with os.fdopen(fd, "w", encoding="utf-8") as stream:
    json.dump(out, stream, ensure_ascii=False)
    stream.flush(); os.fsync(stream.fileno())
PY
echo "已备份 Agent 与授权映射：${BACKUP}（重装后可对照恢复；设备身份未备份，重装须重新配对）"

for link in "$HOME/.local/bin/wherevercode-hand"; do
  if [ -L "$link" ] && [ "$(readlink "$link")" = "$HOST_DIR/wherevercode-hand" ]; then rm -f "$link"; fi
done
# server_key.json（设备身份）与 audit.jsonl（日志）也删掉：卸载即干净断开，
# 重装是新身份、须重新配对（与上方备份说明一致）。
for path in "$HOST_DIR/wherevercode-hand" "$HOST_DIR/.wherevercode-v5-hand-state" \
  "$HOST_DIR/v5-hand.log" "$HOST_DIR/current" "$HOST_DIR/current.new" \
  "$HOST_DIR/host.json" "$HOST_DIR/hand.pid" \
  "$HOST_DIR/server_key.json" "$HOST_DIR/audit.jsonl"; do
  [ -f "$path" ] || [ -L "$path" ] || continue; rm -f "$path"
done
if [ -d "$HOST_DIR/releases" ] && [ ! -L "$HOST_DIR/releases" ]; then
  for release_dir in "$HOST_DIR/releases"/*; do
    [ -d "$release_dir" ] && [ ! -L "$release_dir" ] || continue
    rm -f "$release_dir"/*.py "$release_dir"/*.tmp
    rmdir "$release_dir" 2>/dev/null || echo "保留版本目录中的未知文件：$release_dir"
  done
  rmdir "$HOST_DIR/releases" 2>/dev/null || echo "保留目录中的未知文件：$HOST_DIR/releases"
fi
if [ -d "$HOST_DIR/.venv" ] && [ ! -L "$HOST_DIR/.venv" ]; then
  rm -rf "$HOST_DIR/.venv"
fi
if ! rmdir "$HOST_DIR" 2>/dev/null; then
  echo "保留状态目录中的未知文件：$HOST_DIR"
fi
echo "V5 Hand 已卸载。本机已卸；请在 App 里撤销该设备的配对以立即作废凭证。"
echo "项目与 Agent CLI 配置未删除。"
