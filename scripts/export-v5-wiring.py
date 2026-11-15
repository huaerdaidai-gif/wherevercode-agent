#!/usr/bin/env python3
"""Reproduce route-to-adapter evidence without inferring any HTTP endpoints."""
import csv
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
source = root / 'android/app/src/main/java/wherevercode/agent/ui/flagship/AppFlagshipCapabilities.kt'
bindings = {route: (entry, description) for route, entry, description in re.findall(
    r'"([a-z]+)" to V5Binding\("([a-z_]+)", "([^"]+)"\)', source.read_text())}
with (root / 'docs/v5/audit-70.csv').open() as f:
    audit = {row['route_id']: row for row in csv.DictReader(f)}
contract = json.loads((root / 'docs/v5/baseline/contracts/routes_wiring.json').read_text())
assert len(contract) == len(audit) == 70
nav_file = root / 'docs/v5/evidence/device/traversal.csv'
nav_passed = set()
if nav_file.exists():
    with nav_file.open() as f:
        nav_passed = {r['route_id'] for r in csv.DictReader(f) if r['result'] == 'PASS'}
summary_file = root / 'docs/v5/evidence/native-summary.json'
local_verified = summary_file.exists() and json.loads(summary_file.read_text()).get('local_task_verified', False)
rows = []
for route in contract:
    rid = route['id']
    row = audit[rid].copy()
    row['component'] = 'MainActivity → FlagshipHub → existing Controller' if rid in bindings else 'MainActivity → FlagshipHub (gap detail only)'
    row['entry_id'] = bindings.get(rid, ('', ''))[0]
    row['actual_behavior'] = bindings.get(rid, ('', '待开发；显示缺口，无操作成功或运行数据'))[1]
    row['capability_status'] = 'IMPLEMENTED_UNVERIFIED' if rid in bindings else 'DESIGN_ONLY'
    row['capability_scope'] = 'partial existing capability; full route operations not verified' if rid in bindings else 'gap detail only; no backend action'
    row['request_response'] = 'Navigation request: route_id String; callbacks reuse original typed controller APIs. See actions.csv and source.' if rid in bindings else 'No backend request: service/UI not implemented'
    row['real_http_api'] = 'No new API. Existing local stores / authenticated clients only; see api-evidence.txt.' if rid in bindings else 'MISSING; no endpoint invented'
    row['verification'] = '原生导航已验收；完整业务能力未验收' if rid in nav_passed else row['verification']
    row['navigation_test'] = ('PASS: native navigation only; evidence/device/traversal.csv' if rid in nav_passed else 'FlagshipNavTest (device result recorded separately)')
    row['backend_verification'] = 'NOT_VERIFIED: requires configured test model/device/backend; navigation is not backend evidence'
    if local_verified and rid in {'compose', 'tasks', 'taskdetail'}:
        row['backend_verification'] = 'PARTIAL_LOCAL_VERIFIED: native create/claim/persisted failure/detail-state sync; external model, device controls and review chain NOT_VERIFIED'
    rows.append(row)
for suffix in ('json', 'csv'):
    path = root / f'docs/v5/wiring-70.{suffix}'
    if suffix == 'json':
        path.write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
    else:
        with path.open('w') as f:
            writer = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator="\n")
            writer.writeheader()
            writer.writerows(rows)
print(f'{len(rows)} routes; {len(bindings)} partial capability adapters; {70-len(bindings)} gap details; 0 full backend features verified')
