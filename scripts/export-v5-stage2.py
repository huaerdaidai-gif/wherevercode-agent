#!/usr/bin/env python3
"""Second-stage evidence is separate from the preserved first-stage acceptance."""
import csv
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[1]
out = root / 'docs/v5/stage2'
source = (root / 'android/app/src/main/java/wherevercode/agent/ui/flagship/AppFlagshipCapabilities.kt').read_text()
bindings = {rid: (entry, note) for rid, entry, note in re.findall(r'"([a-z]+)" to V5Binding\("([a-z_]+)", "([^"]+)"\)', source)}
with (root / 'docs/v5/wiring-70.csv').open() as f:
    rows = list(csv.DictReader(f))
with (out / 'gap-70.csv').open() as f:
    gaps = {r['route_id']: r for r in csv.DictReader(f)}
summary_path = out / 'evidence/native-summary.json'
summary = json.loads(summary_path.read_text()) if summary_path.exists() else {}
new = {
    'search': ('LocalSearchScreen / LocalSearch', 'ConversationStore.listConversations/listMessages; TaskStore.listTasks; ProjectStore.listProjects; FlagshipRoutes',
               'String query → List<LocalSearchResult(kind,id,title,snippet)>', '本地正文与元数据检索、精确 ID 导航；无云端/文件正文索引'),
    'memory': ('MemoryScreen / PersistentMemoryStore', 'BrainComponents.memoryStore = BrainEngine memory; SharedPreferences brain_memory/entries_v1',
               'MemoryEntry + confirmed Boolean → Boolean; list(MemoryKind) → List<MemoryEntry>; remove → Boolean', '确认事实、三类记忆列表、持久化及删除；无跨端同步；设备备注不授予或撤销权限'),
    'skills': ('SkillsScreen / RoomSkillStore', 'BrainComponents.skillStore; existing TaskDatabase.skills table',
               'Skill → upsert; listForProject(String) → List<Skill>; remove(id)', '项目/全局筛选、真实详情、人工零证据记录及删除；自动化/技能市场仍缺失'),
}
for row in rows:
    rid = row['route_id']
    row['stage2_dependency'] = gaps[rid]['stage2_category']
    row['stage2_status'] = '已实现但未验证' if rid in bindings else '仅设计'
    row['stage2_scope'] = '沿用首批局部能力，不能据此判定整屏完成' if rid in bindings else '缺口详情可导航；业务操作待开发'
    row['stage2_native'] = 'PASS: navigation only' if summary.get('navigation_70') else '未验证本批 APK；首批导航证据单独保留'
    row['stage2_verified_operations'] = ''
    if rid in new:
        component, data, types, scope = new[rid]
        row.update(component=component, entry_id=bindings[rid][0], actual_behavior=bindings[rid][1],
                   audit_status='可复用但需接线', verification='本地子功能已实现；本批设备结果见 stage2_verified_operations，整屏能力未全量验收',
                   capability_status='IMPLEMENTED_UNVERIFIED', capability_scope='partial local implementation; full flagship route NOT_VERIFIED',
                   data_types_and_calls=data, request_response=types, real_http_api='NONE: local stores; no shared API contract changed',
                   auth='应用沙盒内本机用户数据；不新增远程权限',
                   states='Loading/Empty/Error/Retry; Offline local reads; writes fail explicitly; no fake success',
                   confirmation='事实确认后保存；记忆与技能删除有二次确认；人工技能明确保存，成功次数与证据为空',
                   tests='PersistentMemoryStoreTest; LocalSearchTest; LocalFeaturesTest; FlagshipNavTest',
                   stage2_scope=scope, stage2_dependency='本批 Android 本地实现，无需新增服务端接口',
                   backend_verification='LOCAL_ONLY; no cloud/model/remote execution claim',
                   gap=scope)
        if summary.get('local_features'):
            row['stage2_verified_operations'] = {'search':'设备：路由检索并精确跳转；JVM：会话正文脱敏/去重、任务与项目 ID',
                'memory':'设备：确认保存/独立持久库重读/删除；JVM：重开/敏感拒绝/损坏与写盘失败保护',
                'skills':'设备：UI 创建/Room 重读/零执行证据/删除；项目筛选与自动化未做设备验收'}[rid]
    if summary.get('task_detail') and rid in {'compose','tasks','taskdetail'}:
        row['stage2_verified_operations'] = '本批设备：UI 创建/真实领取/未配置模型失败/详情状态自动同步；非模型执行成功'
assert len(rows) == len({r['route_id'] for r in rows}) == 70
with (out / 'acceptance-70.csv').open('w') as f:
    writer = csv.DictWriter(f, fieldnames=list(rows[0]), lineterminator='\n')
    writer.writeheader(); writer.writerows(rows)
(out / 'acceptance-70.json').write_text(json.dumps(rows, ensure_ascii=False, indent=2) + '\n')
print(f'70 retained routes; {len(bindings)} partial bindings; {70-len(bindings)} gaps; no full-route acceptance inferred')
