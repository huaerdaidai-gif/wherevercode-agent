#!/usr/bin/env bash
# MCP 派工模块端到端脚本：用 curl 对一个正在运行的派工 MCP 服务端走完整流程。
#
# 必需环境变量（令牌只从环境读，绝不打印、不出现在命令行参数里）：
#   MCP_URL             例如 http://127.0.0.1:8765/mcp
#   DISPATCHER_TOKEN    具备 dispatch 能力的令牌（wcd_…）
#   EXECUTOR_TOKEN      具备 execute 能力的令牌（wcd_…）
#   EXECUTOR_ID         执行方 principal id（如 grok-bot）
# 依赖：bash、curl、python3（解析 JSON）。
# 成功时最后一行输出 E2E_OK，任何一步不符合预期即非零退出。
set -euo pipefail

: "${MCP_URL:?MCP_URL is required}"
: "${DISPATCHER_TOKEN:?DISPATCHER_TOKEN is required}"
: "${EXECUTOR_TOKEN:?EXECUTOR_TOKEN is required}"
: "${EXECUTOR_ID:?EXECUTOR_ID is required}"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
umask 077
printf 'Authorization: Bearer %s\n' "$DISPATCHER_TOKEN" > "$work/dispatcher.h"
printf 'Authorization: Bearer %s\n' "$EXECUTOR_TOKEN" > "$work/executor.h"
run_id="$(date +%s)-$$"
echo 0 > "$work/id"

# rpc <who> <method> <params-json>  → 打印响应体（不含任何请求头）
rpc() {
  local who="$1" method="$2" params="$3"
  local id=$(( $(cat "$work/id") + 1 ))
  echo "$id" > "$work/id"
  local body="{\"jsonrpc\":\"2.0\",\"id\":$id,\"method\":\"$method\",\"params\":$params}"
  echo ">> [$who] $method $params" >&2
  local resp
  resp="$(curl -sS --max-time 15 -X POST "$MCP_URL" \
    -H @"$work/$who.h" -H 'Content-Type: application/json' \
    -H 'Accept: application/json, text/event-stream' -H 'MCP-Protocol-Version: 2025-06-18' \
    --data-binary "$body")"
  echo "<< [$who] $resp" >&2
  printf '%s' "$resp"
}

# tool <who> <name> <args-json>
tool() { rpc "$1" tools/call "{\"name\":\"$2\",\"arguments\":$3}"; }

# jget <json> <python expr on r>
jget() { python3 -c 'import json,sys; r=json.loads(sys.argv[1]); print(eval(sys.argv[2]))' "$1" "$2"; }

expect() { # expect <actual> <expected> <label>
  if [ "$1" != "$2" ]; then echo "FAIL: $3: expected '$2' got '$1'" >&2; exit 1; fi
  echo "PASS: $3 = $1"
}

# 0. 未带令牌 → 401
code="$(curl -sS -o /dev/null -w '%{http_code}' --max-time 10 -X POST "$MCP_URL" -H 'Content-Type: application/json' \
  --data-binary '{"jsonrpc":"2.0","id":0,"method":"ping"}')"
expect "$code" 401 "no token is rejected"

# 1. initialize + tools/list
r="$(rpc dispatcher initialize '{"protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"curl-e2e","version":"1"}}')"
expect "$(jget "$r" 'r["result"]["serverInfo"]["name"]')" wherevercode-dispatch "initialize serverInfo"
r="$(rpc dispatcher tools/list '{}')"
expect "$(jget "$r" '",".join(sorted(t["name"] for t in r["result"]["tools"]))')" \
  "assign_task,change_goal,create_task,get_task_status,pause_task,resume_task,stop_task" "dispatcher tools"
r="$(rpc executor tools/list '{}')"
expect "$(jget "$r" '",".join(sorted(t["name"] for t in r["result"]["tools"]))')" \
  "claim_task,get_task_status,list_my_tasks,report_progress,submit_result" "executor tools"

# 2. create（重复两次，同一幂等键）→ 同一任务
args="{\"title\":\"curl e2e\",\"goal\":\"curl 驱动的端到端\",\"idempotency_key\":\"curl-create-$run_id\"}"
r="$(tool dispatcher create_task "$args")"
task_id="$(jget "$r" 'r["result"]["structuredContent"]["task"]["task_id"]')"
r2="$(tool dispatcher create_task "$args")"
expect "$(jget "$r2" 'r["result"]["structuredContent"]["task"]["task_id"]')" "$task_id" "idempotent create"
expect "$(jget "$r2" 'r["result"]["structuredContent"]["replayed"]')" True "replayed flag"

# 3. assign → list_my_tasks → claim
tool dispatcher assign_task "{\"task_id\":\"$task_id\",\"agent_id\":\"$EXECUTOR_ID\"}" >/dev/null
r="$(tool executor list_my_tasks '{}')"
expect "$(jget "$r" 'any(t["task_id"]=="'"$task_id"'" for t in r["result"]["structuredContent"]["tasks"])')" True "task listed for executor"
r="$(tool executor claim_task "{\"task_id\":\"$task_id\"}")"
attempt="$(jget "$r" 'r["result"]["structuredContent"]["attempt_id"]')"
expect "$(jget "$r" 'r["result"]["structuredContent"]["task"]["state"]')" working "claimed and working"

# 4. 越权：执行方不能建任务
r="$(tool executor create_task '{"title":"x","goal":"y","idempotency_key":"curl-evil-0001"}')"
expect "$(jget "$r" 'r["result"]["structuredContent"]["error_code"]')" forbidden "executor cannot create"

# 5. progress → change_goal → stale goal rejected → submit
tool executor report_progress "{\"task_id\":\"$task_id\",\"attempt_id\":\"$attempt\",\"message\":\"half way\",\"percent\":50}" >/dev/null
r="$(tool dispatcher change_goal "{\"task_id\":\"$task_id\",\"goal\":\"新目标\"}")"
expect "$(jget "$r" 'r["result"]["structuredContent"]["task"]["goal_version"]')" 2 "goal_version bumped"
r="$(tool executor submit_result "{\"task_id\":\"$task_id\",\"attempt_id\":\"$attempt\",\"goal_version\":1,\"result\":\"old\"}")"
expect "$(jget "$r" 'r["result"]["structuredContent"]["error_code"]')" goal_changed "stale goal rejected"
r="$(tool executor submit_result "{\"task_id\":\"$task_id\",\"attempt_id\":\"$attempt\",\"goal_version\":2,\"result\":\"done via curl\"}")"
expect "$(jget "$r" 'r["result"]["structuredContent"]["task"]["state"]')" completed "submitted"

# 6. stop fence on a second task
r="$(tool dispatcher create_task "{\"title\":\"to stop\",\"goal\":\"g\",\"assignee\":\"$EXECUTOR_ID\",\"idempotency_key\":\"curl-create2-$run_id\"}")"
t2="$(jget "$r" 'r["result"]["structuredContent"]["task"]["task_id"]')"
r="$(tool executor claim_task "{\"task_id\":\"$t2\"}")"
a2="$(jget "$r" 'r["result"]["structuredContent"]["attempt_id"]')"
tool dispatcher stop_task "{\"task_id\":\"$t2\",\"reason\":\"curl stop\"}" >/dev/null
r="$(tool executor submit_result "{\"task_id\":\"$t2\",\"attempt_id\":\"$a2\",\"goal_version\":1,\"result\":\"sneaky\"}")"
expect "$(jget "$r" 'r["result"]["structuredContent"]["error_code"]')" task_stopped "write after stop rejected"
r="$(tool executor report_progress "{\"task_id\":\"$t2\",\"attempt_id\":\"$a2\",\"ack\":\"stopped\"}")"
expect "$(jget "$r" 'r["result"]["structuredContent"]["task"]["state"]')" cancelled "stop acknowledged"

r="$(tool dispatcher get_task_status "{\"task_id\":\"$task_id\"}")"
expect "$(jget "$r" 'r["result"]["structuredContent"]["task"]["state"]')" completed "final status"
echo E2E_OK
