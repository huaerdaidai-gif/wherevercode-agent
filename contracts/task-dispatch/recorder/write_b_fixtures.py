#!/usr/bin/env python3
"""B-group (and handwritten C) fixture writer for the task-dispatch contract.

These fixtures pin behaviour that does not exist in the reference server
(update_task / withdraw_task / reopen_task, channel-scoped idempotency,
soft delete, role-based tool exposure, new-tool guards). They are written
by hand from spec.md. Comparison rules (spec.md section 8): `result`
is a subset match, `error` and `state_after` are exact matches.

Run: python3 write_b_fixtures.py <out-dir>
"""

import json
import os
import sys

GOAL = "实现登录页的记住我功能"
GOAL2 = "实现登录页的记住我功能（含退出登录后清除）"
SEED = {"agents": [
    {"id": "boss", "scopes": ["owner"], "roles": []},
    {"id": "dev1", "scopes": ["agent"], "roles": ["dev"]},
    {"id": "dev2", "scopes": ["agent"], "roles": ["dev"]},
    {"id": "rev1", "scopes": ["agent"], "roles": ["review"]},
], "lease_seconds": 7200}


def T(ref, kind, state, version, events, round=1, assignee="dev1", claimed=None,
      reserved=None, reviewer=None, parent=None, decision=None, verdict=None,
      dp=0, dtp=0, channel=None, epoch=1, deleted=False):
    return {"ref": ref, "kind": kind, "parent": parent, "state": state,
            "round": round, "version": version, "assignee_id": assignee,
            "claimed_by_id": claimed, "reserved_for_id": reserved,
            "reviewer_id": reviewer, "decision_reason": decision,
            "verdict": verdict, "delivery_pages": dp,
            "delivery_total_pages": dtp, "events": events,
            "channel_id": channel, "epoch": epoch, "deleted": deleted}


def step(actor, tool, args, expect, state_after):
    return {"call": {"actor": actor, "tool": tool, "args": args},
            "expect": expect, "state_after": state_after}


def ok(result):
    return {"result": result}


def err(status, code, message):
    return {"error": {"status": status, "code": code, "message": message}}


def fixture(name, group, description, steps):
    return {"name": name, "group": group, "description": description,
            "source": "handwritten per spec.md", "seed": SEED, "steps": steps}


def b01():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"], reviewer="rev1", channel="CH1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "reviewer": "rev1", "project_id": "proj",
                       "goal": GOAL, "channel_id": "CH1"},
                      ok({"task": {"task_id": "TASK_1", "state": "queued", "version": 1},
                          "state": "queued"}), [t1]))
    t1 = T("TASK_1", "work", "queued", 2, ["created", "updated"],
           reviewer="rev1", channel="CH1")
    steps.append(step("boss", "update_task",
                      {"task_id": "TASK_1", "expected_version": 1, "goal": GOAL2},
                      ok({"task": {"task_id": "TASK_1", "version": 2, "goal": GOAL2}}), [t1]))
    t1 = T("TASK_1", "work", "claimed", 3, ["created", "updated", "claimed"],
           claimed="dev1", reviewer="rev1", channel="CH1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed", "version": 3}}), [t1]))
    steps.append(step("boss", "update_task",
                      {"task_id": "TASK_1", "expected_version": 2, "title": "改标题"},
                      err(409, "stale_version", "版本已变（当前 v3，你给的是 v2）：请重新读取任务后再试"), [t1]))
    t1 = T("TASK_1", "work", "queued", 4,
           ["created", "updated", "claimed", "reassigned"],
           assignee="dev2", reserved="dev2", reviewer="rev1", channel="CH1")
    steps.append(step("boss", "update_task",
                      {"task_id": "TASK_1", "expected_version": 3, "agent": "dev2"},
                      ok({"task": {"task_id": "TASK_1", "version": 4, "state": "queued",
                                   "assignee_id": "dev2"}}), [t1]))
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_1", "summary": "我做完了", "branch": "feat/login"},
                      err(404, "task_not_found", "任务不存在"), [t1]))
    t1 = T("TASK_1", "work", "claimed", 5,
           ["created", "updated", "claimed", "reassigned", "claimed"],
           assignee="dev2", claimed="dev2", reserved="dev2",
           reviewer="rev1", channel="CH1")
    steps.append(step("dev2", "claim_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed", "version": 5}}), [t1]))
    t1 = T("TASK_1", "work", "reviewing", 6,
           ["created", "updated", "claimed", "reassigned", "claimed",
            "submitted", "review_opened"],
           assignee="dev2", claimed="dev2", reserved="dev2",
           reviewer="rev1", channel="CH1")
    t2 = T("TASK_2", "review", "queued", 1, [], assignee="rev1", parent="TASK_1")
    steps.append(step("dev2", "submit_result",
                      {"task_id": "TASK_1", "summary": "已实现", "branch": "feat/login"},
                      ok({"ok": True, "state": "reviewing"}), [t1, t2]))
    steps.append(step("boss", "update_task",
                      {"task_id": "TASK_1", "expected_version": 6, "goal": GOAL},
                      err(409, "task_state_reviewing", "任务当前状态是待审核，做不了这一步"), [t1, t2]))
    t2 = T("TASK_2", "review", "claimed", 2, ["claimed"], assignee="rev1",
           claimed="rev1", parent="TASK_1")
    steps.append(step("rev1", "claim_task", {"task_id": "TASK_2"},
                      ok({"task": {"task_id": "TASK_2", "state": "claimed"}}), [t1, t2]))
    t1 = T("TASK_1", "work", "approved", 7,
           ["created", "updated", "claimed", "reassigned", "claimed",
            "submitted", "review_opened", "approved"],
           assignee="dev2", reviewer="rev1", channel="CH1", verdict="approve")
    t2 = T("TASK_2", "review", "done", 3, ["claimed", "verdict"],
           assignee="rev1", parent="TASK_1", verdict="approve")
    steps.append(step("rev1", "submit_result",
                      {"task_id": "TASK_2", "verdict": "approve", "summary": "通过"},
                      ok({"ok": True, "state": "done"}), [t1, t2]))
    steps.append(step("boss", "update_task",
                      {"task_id": "TASK_1", "expected_version": 7, "goal": GOAL},
                      err(409, "task_state_approved", "任务当前状态是审查通过，做不了这一步"), [t1, t2]))
    return fixture("b01-update-task", "B",
                   "update_task: content edit with fencing, reassignment releases the "
                   "claimer and reserves for the new assignee, state guards.", steps)


def b02_queued():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"], reviewer="rev1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "reviewer": "rev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t1 = T("TASK_1", "work", "cancelled", 2, ["created", "withdrawn"], reviewer="rev1")
    steps.append(step("boss", "withdraw_task", {"task_id": "TASK_1", "reason": "计划取消"},
                      ok({"ok": True, "state": "cancelled"}), [t1]))
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1"},
                      err(409, "task_state_cancelled", "任务当前状态是已取消，领不了"), [t1]))
    steps.append(step("boss", "withdraw_task", {"task_id": "TASK_1"},
                      ok({"ok": True, "state": "cancelled"}), [t1]))
    return fixture("b02-withdraw-queued", "B",
                   "withdraw on a queued task; repeated withdraw is an idempotent no-op.",
                   steps)


def b02_claimed():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"], reviewer="rev1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "reviewer": "rev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t1 = T("TASK_1", "work", "claimed", 2, ["created", "claimed"],
           claimed="dev1", reviewer="rev1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed"}}), [t1]))
    t1 = T("TASK_1", "work", "cancelled", 3, ["created", "claimed", "withdrawn"],
           reviewer="rev1")
    steps.append(step("boss", "withdraw_task", {"task_id": "TASK_1"},
                      ok({"ok": True, "state": "cancelled"}), [t1]))
    steps.append(step("dev1", "heartbeat", {"task_id": "TASK_1"},
                      err(409, "task_state_cancelled", "任务当前状态是已取消，做不了这一步"), [t1]))
    return fixture("b02-withdraw-claimed", "B",
                   "withdraw on a claimed task clears the claimer; later actions refused.",
                   steps)


def b02_reviewing():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"], reviewer="rev1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "reviewer": "rev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t1 = T("TASK_1", "work", "claimed", 2, ["created", "claimed"],
           claimed="dev1", reviewer="rev1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed"}}), [t1]))
    t1 = T("TASK_1", "work", "reviewing", 3,
           ["created", "claimed", "submitted", "review_opened"],
           claimed="dev1", reviewer="rev1")
    t2 = T("TASK_2", "review", "queued", 1, [], assignee="rev1", parent="TASK_1")
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_1", "summary": "已实现", "branch": "feat/login"},
                      ok({"ok": True, "state": "reviewing"}), [t1, t2]))
    t1 = T("TASK_1", "work", "cancelled", 4,
           ["created", "claimed", "submitted", "review_opened", "withdrawn"],
           reviewer="rev1")
    t2 = T("TASK_2", "review", "cancelled", 2, ["withdrawn"], assignee="rev1",
           parent="TASK_1")
    steps.append(step("boss", "withdraw_task", {"task_id": "TASK_1"},
                      ok({"ok": True, "state": "cancelled"}), [t1, t2]))
    steps.append(step("rev1", "claim_task", {"task_id": "TASK_2"},
                      err(409, "task_state_cancelled", "任务当前状态是已取消，领不了"), [t1, t2]))
    steps.append(step("boss", "decide_task",
                      {"task_id": "TASK_1", "action": "done", "reason": "收了"},
                      err(409, "task_state_cancelled", "任务当前状态是已取消，不需要决定"), [t1, t2]))
    return fixture("b02-withdraw-reviewing", "B",
                   "withdraw while reviewing cancels the open review subtask too.", steps)


def b03():
    steps = []
    base_events = ["created", "claimed", "submitted", "review_opened", "approved"]
    t1 = T("TASK_1", "work", "queued", 1, ["created"], reviewer="rev1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "reviewer": "rev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t1 = T("TASK_1", "work", "claimed", 2, ["created", "claimed"],
           claimed="dev1", reviewer="rev1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1", "attempt_id": "a-1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed"}}), [t1]))
    t1 = T("TASK_1", "work", "reviewing", 3,
           ["created", "claimed", "submitted", "review_opened"],
           claimed="dev1", reviewer="rev1")
    t2 = T("TASK_2", "review", "queued", 1, [], assignee="rev1", parent="TASK_1")
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_1", "summary": "第一版", "branch": "feat/login",
                       "attempt_id": "s-1"},
                      ok({"ok": True, "state": "reviewing"}), [t1, t2]))
    t2 = T("TASK_2", "review", "claimed", 2, ["claimed"], assignee="rev1",
           claimed="rev1", parent="TASK_1")
    steps.append(step("rev1", "claim_task", {"task_id": "TASK_2"},
                      ok({"task": {"task_id": "TASK_2", "state": "claimed"}}), [t1, t2]))
    t1 = T("TASK_1", "work", "approved", 4, base_events, reviewer="rev1",
           verdict="approve")
    t2 = T("TASK_2", "review", "done", 3, ["claimed", "verdict"], assignee="rev1",
           parent="TASK_1", verdict="approve")
    steps.append(step("rev1", "submit_result",
                      {"task_id": "TASK_2", "verdict": "approve", "summary": "通过",
                       "attempt_id": "v-1"},
                      ok({"ok": True, "state": "done"}), [t1, t2]))
    t1 = T("TASK_1", "work", "queued", 5, base_events + ["reopened"],
           reviewer="rev1", epoch=2)
    steps.append(step("boss", "reopen_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "queued", "round": 1,
                                   "version": 5, "epoch": 2, "verdict": None,
                                   "summary": None}}), [t1, t2]))
    # Same attempt_id as before the reopen: the epoch in the idempotency key
    # means this executes fresh instead of replaying the pre-reopen result.
    t1 = T("TASK_1", "work", "claimed", 6, base_events + ["reopened", "claimed"],
           claimed="dev1", reviewer="rev1", epoch=2)
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1", "attempt_id": "a-1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed", "version": 6}}),
                      [t1, t2]))
    t1 = T("TASK_1", "work", "reviewing", 7,
           base_events + ["reopened", "claimed", "submitted", "review_opened"],
           claimed="dev1", reviewer="rev1", epoch=2)
    t3 = T("TASK_3", "review", "queued", 1, [], assignee="rev1", parent="TASK_1")
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_1", "summary": "重开后第二版",
                       "branch": "feat/login", "attempt_id": "s-1"},
                      ok({"ok": True, "state": "reviewing"}), [t1, t2, t3]))
    t3 = T("TASK_3", "review", "claimed", 2, ["claimed"], assignee="rev1",
           claimed="rev1", parent="TASK_1")
    steps.append(step("rev1", "claim_task", {"task_id": "TASK_3"},
                      ok({"task": {"task_id": "TASK_3", "state": "claimed"}}), [t1, t2, t3]))
    t1 = T("TASK_1", "work", "approved", 8,
           base_events + ["reopened", "claimed", "submitted", "review_opened", "approved"],
           reviewer="rev1", epoch=2, verdict="approve")
    t3 = T("TASK_3", "review", "done", 3, ["claimed", "verdict"], assignee="rev1",
           parent="TASK_1", verdict="approve")
    steps.append(step("rev1", "submit_result",
                      {"task_id": "TASK_3", "verdict": "approve", "summary": "通过"},
                      ok({"ok": True, "state": "done"}), [t1, t2, t3]))
    t1 = T("TASK_1", "work", "queued", 9,
           base_events + ["reopened", "claimed", "submitted", "review_opened",
                          "approved", "reopened"],
           reviewer="rev1", epoch=3)
    steps.append(step("boss", "reopen_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "queued", "epoch": 3}}),
                      [t1, t2, t3]))
    return fixture("b03-reopen-task", "B",
                   "reopen resets round, bumps epoch, clears delivery fields; old "
                   "attempt_ids execute fresh under the new epoch; reopen is repeatable.",
                   steps)


def b04():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"], channel="CH1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL,
                       "channel_id": "CH1", "idempotency_key": "K"},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t2 = T("TASK_2", "work", "queued", 1, ["created"], channel="CH2")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL,
                       "channel_id": "CH2", "idempotency_key": "K"},
                      ok({"task": {"task_id": "TASK_2", "state": "queued"}}), [t1, t2]))
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL2,
                       "channel_id": "CH1", "idempotency_key": "K"},
                      err(409, "idempotency_conflict", "idempotency_key 已用于不同内容的任务"),
                      [t1, t2]))
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL,
                       "channel_id": "CH1", "idempotency_key": "K"},
                      ok({"task": {"task_id": "TASK_1", "state": "queued", "version": 1}}),
                      [t1, t2]))
    return fixture("b04-channel-idempotency", "B",
                   "start_task idempotency is scoped by (channel_id, key): another "
                   "channel is a different task; same scope + different content conflicts.",
                   steps)


def b05():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"])
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t1 = T("TASK_1", "work", "claimed", 2, ["created", "claimed"], claimed="dev1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed"}}), [t1]))
    # 基线实测（a08 录制）：submit 转 needs_decision 不清 claimed_by，
    # decide 才清——此处期望须与录制组一致。
    t1 = T("TASK_1", "work", "needs_decision", 3, ["created", "claimed", "submitted"],
           claimed="dev1", decision="no_reviewer")
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_1", "summary": "做完了", "commit": "abc1234"},
                      ok({"ok": True, "state": "needs_decision"}), [t1]))
    t1 = T("TASK_1", "work", "done", 4,
           ["created", "claimed", "submitted", "decided"])
    steps.append(step("boss", "decide_task",
                      {"task_id": "TASK_1", "action": "done", "reason": "确认完成"},
                      ok({"ok": True, "state": "done"}), [t1]))
    t1 = T("TASK_1", "work", "done", 5,
           ["created", "claimed", "submitted", "decided", "deleted"], deleted=True)
    steps.append(step("boss", "ui_delete_task", {"task_id": "TASK_1"},
                      ok({"ok": True, "task": {"task_id": "TASK_1", "deleted_at": "<ts>"}}), [t1]))
    steps.append(step("boss", "list_tasks", {"project_id": "proj"},
                      ok({"tasks": []}), [t1]))
    steps.append(step("boss", "get_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "done",
                                   "deleted_at": "<ts>"}}), [t1]))
    t2 = T("TASK_2", "work", "queued", 1, ["created"])
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL2},
                      ok({"task": {"task_id": "TASK_2", "state": "queued"}}), [t1, t2]))
    steps.append(step("boss", "ui_delete_task", {"task_id": "TASK_2"},
                      err(409, "task_not_terminal", "只有终态任务可以删除"), [t1, t2]))
    steps.append(step("dev1", "ui_delete_task", {"task_id": "TASK_1"},
                      err(403, "task_forbidden", "只有建单人或频道 owner 可以删除"), [t1, t2]))
    return fixture("b05-soft-delete", "B",
                   "UI soft delete: terminal only, creator/channel-owner only, lists "
                   "exclude it, get_task keeps the tombstone.", steps)


def b06():
    worker = ["list_my_tasks", "claim_task", "release_task", "report_progress",
              "report_status", "submit_result", "attach_delivery_diff",
              "get_delivery_diff", "heartbeat"]
    owner = worker + ["start_task", "get_task", "list_tasks", "decide_task",
                      "update_task", "withdraw_task", "reopen_task"]
    steps = [
        step("dev1", "tools/list", {}, ok({"tools": worker}), []),
        step("boss", "tools/list", {}, ok({"tools": owner}), []),
        step("dev1", "get_task", {"task_id": "TASK_1"},
             err(409, "v5_error", "未知工具"), []),
        step("dev1", "start_task", {"agent": "dev1", "project_id": "proj", "goal": GOAL},
             err(409, "v5_error", "未知工具"), []),
        step("dev1", "update_task", {"task_id": "TASK_1", "expected_version": 1},
             err(409, "v5_error", "未知工具"), []),
        step("dev1", "withdraw_task", {"task_id": "TASK_1"},
             err(409, "v5_error", "未知工具"), []),
    ]
    return fixture("b06-role-exposure", "B",
                   "Workers see exactly the 9 worker tools; owner tools are unknown "
                   "tools to them, not forbidden ones.", steps)


def c07():
    steps = []
    t1 = T("TASK_1", "work", "queued", 1, ["created"])
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_1", "state": "queued"}}), [t1]))
    t1 = T("TASK_1", "work", "claimed", 2, ["created", "claimed"], claimed="dev1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "claimed"}}), [t1]))
    steps.append(step("boss", "reopen_task", {"task_id": "TASK_1"},
                      err(409, "task_state_claimed", "任务当前状态是进行中，做不了这一步"), [t1]))
    steps.append(step("boss", "update_task", {"task_id": "TASK_1", "goal": GOAL2},
                      err(409, "v5_error", "expected_version 必填"), [t1]))
    # 基线实测（a08 录制）：submit 转 needs_decision 不清 claimed_by，
    # decide 才清——此处期望须与录制组一致。
    t1 = T("TASK_1", "work", "needs_decision", 3, ["created", "claimed", "submitted"],
           claimed="dev1", decision="no_reviewer")
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_1", "summary": "做完了", "commit": "abc1234"},
                      ok({"ok": True, "state": "needs_decision"}), [t1]))
    steps.append(step("boss", "update_task",
                      {"task_id": "TASK_1", "expected_version": 3, "goal": GOAL2},
                      err(409, "task_state_needs_decision", "任务当前状态是等你决定，做不了这一步"), [t1]))
    t1 = T("TASK_1", "work", "done", 4,
           ["created", "claimed", "submitted", "decided"])
    steps.append(step("boss", "decide_task",
                      {"task_id": "TASK_1", "action": "done", "reason": "确认"},
                      ok({"ok": True, "state": "done"}), [t1]))
    steps.append(step("boss", "withdraw_task", {"task_id": "TASK_1"},
                      err(409, "task_state_done", "任务当前状态是完成，做不了这一步"), [t1]))
    t1 = T("TASK_1", "work", "queued", 5,
           ["created", "claimed", "submitted", "decided", "reopened"], epoch=2)
    steps.append(step("boss", "reopen_task", {"task_id": "TASK_1"},
                      ok({"task": {"task_id": "TASK_1", "state": "queued", "epoch": 2}}), [t1]))
    t2 = T("TASK_2", "work", "queued", 1, ["created"], reviewer="rev1")
    steps.append(step("boss", "start_task",
                      {"agent": "dev1", "reviewer": "rev1", "project_id": "proj", "goal": GOAL},
                      ok({"task": {"task_id": "TASK_2", "state": "queued"}}), [t1, t2]))
    t2 = T("TASK_2", "work", "claimed", 2, ["created", "claimed"],
           claimed="dev1", reviewer="rev1")
    steps.append(step("dev1", "claim_task", {"task_id": "TASK_2"},
                      ok({"task": {"task_id": "TASK_2", "state": "claimed"}}), [t1, t2]))
    t2 = T("TASK_2", "work", "reviewing", 3,
           ["created", "claimed", "submitted", "review_opened"],
           claimed="dev1", reviewer="rev1")
    t3 = T("TASK_3", "review", "queued", 1, [], assignee="rev1", parent="TASK_2")
    steps.append(step("dev1", "submit_result",
                      {"task_id": "TASK_2", "summary": "已实现", "branch": "feat/login"},
                      ok({"ok": True, "state": "reviewing"}), [t1, t2, t3]))
    steps.append(step("boss", "reopen_task", {"task_id": "TASK_3"},
                      err(409, "v5_error", "只有工作任务可以重开"), [t1, t2, t3]))
    return fixture("c07-new-tool-guards", "C",
                   "Guards for the new tools: reopen needs a terminal work task, "
                   "update needs expected_version and Q/C state, withdraw refuses "
                   "terminal states.", steps)


def main():
    out_dir = sys.argv[1]
    os.makedirs(out_dir, exist_ok=True)
    for fx in (b01(), b02_queued(), b02_claimed(), b02_reviewing(), b03(), b04(),
               b05(), b06(), c07()):
        path = os.path.join(out_dir, fx["name"] + ".json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(fx, fh, ensure_ascii=False, indent=2)
        print(f"wrote {path} ({len(fx['steps'])} steps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
