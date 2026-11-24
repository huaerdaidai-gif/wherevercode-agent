#!/usr/bin/env python3
"""W0 fixture recorder for the task-dispatch contract.

Drives the reference implementation (v5_platform.py, the live V5 server's
Store + view layer) through scripted scenarios under a fake clock and
records each tool call's result / error plus the full task-state trace
after every step. The recordings are the A-group conformance fixtures in
../fixtures/: the Android and iOS embedded implementations must reproduce
them exactly (after the same normalization) in their fixture runners.

Usage:
    python3 record_fixtures.py <dir-containing-v5_platform.py> <out-dir>

Normalization rules (fixture runners must apply the same):
  - task ids -> TASK_1, TASK_2, ... in creation order (incl. subtasks)
  - epoch timestamps (floats >= 1e9) -> "<ts>"
  - everything else is recorded verbatim (versions, rounds, states,
    error codes and messages are deterministic under the fake clock)
"""

import importlib.util
import json
import os
import sys
import tempfile

BASE_TS = 1_700_000_000.0
LIST_NOTE = "排队的先领；领取后租约 2 小时，超过 2 小时没有动静会回到队伍。"


def load_platform(baseline_dir):
    sys.path.insert(0, baseline_dir)
    spec = importlib.util.spec_from_file_location(
        "v5_platform", os.path.join(baseline_dir, "v5_platform.py"))
    module = importlib.util.module_from_spec(spec)
    sys.modules["v5_platform"] = module
    spec.loader.exec_module(module)
    return module


class Recorder:
    def __init__(self, platform, lease_seconds=7200):
        self.p = platform
        self.clock = [BASE_TS]
        platform._now = lambda: self.clock[0]
        fd, self.db_path = tempfile.mkstemp(suffix=".db")
        os.close(fd)
        os.unlink(self.db_path)
        self.store = platform.Store(self.db_path, lease_seconds=lease_seconds)
        self.idmap = {}
        self.steps = []

    # -- normalization ------------------------------------------------------
    def _register_ids(self):
        rows = self.store._conn.execute(
            "SELECT task_id FROM tasks ORDER BY rowid").fetchall()
        for row in rows:
            tid = row[0]
            if tid not in self.idmap:
                self.idmap[tid] = f"TASK_{len(self.idmap) + 1}"

    def norm(self, obj):
        if isinstance(obj, dict):
            return {k: self.norm(v) for k, v in obj.items()}
        if isinstance(obj, (list, tuple)):
            return [self.norm(v) for v in obj]
        if isinstance(obj, float) and obj >= 1e9:
            return "<ts>"
        if isinstance(obj, str):
            out = obj
            for real, placeholder in self.idmap.items():
                if real in out:
                    out = out.replace(real, placeholder)
            return out
        return obj

    # -- state trace ----------------------------------------------------------
    def snapshot(self):
        self._register_ids()
        tasks = []
        rows = self.store._conn.execute(
            "SELECT * FROM tasks ORDER BY rowid").fetchall()
        for row in rows:
            row = dict(row)
            events = [e["kind"] for e in self.store.task_events(row["task_id"], limit=200)]
            tasks.append({
                "ref": self.idmap[row["task_id"]],
                "kind": row["kind"],
                "parent": self.idmap.get(row["parent_id"]) if row["parent_id"] else None,
                "state": row["state"],
                "round": row["round"],
                "version": row["version"],
                "assignee_id": row["assignee_id"],
                "claimed_by_id": row["claimed_by_id"],
                "reserved_for_id": row["reserved_for_id"],
                "reviewer_id": row["reviewer_id"],
                "decision_reason": row["decision_reason"],
                "verdict": row["verdict"],
                "delivery_pages": row["delivery_pages"],
                "delivery_total_pages": row["delivery_total_pages"],
                "events": events,
            })
        return tasks

    # -- tool translation (mirrors v5_platform.MCPService._dispatch) ---------
    def _execute(self, actor, tool, a):
        s = self.store
        p = self.p
        if tool == "start_task":
            target = s.get_agent(a["agent"])
            if target is None:
                raise p.V5Error(f"agent {a['agent']} 不存在")
            reviewer_id = a.get("reviewer")
            reviewer_name = None
            if reviewer_id is not None:
                reviewer = s.get_agent(reviewer_id)
                if reviewer is None:
                    raise p.V5Error(f"reviewer {reviewer_id} 不存在")
                reviewer_name = reviewer["name"]
            row = s.create_task(a.get("project_id"), a.get("goal"), a["agent"],
                                target["name"], reviewer_id=reviewer_id,
                                reviewer_name=reviewer_name, title=a.get("title"),
                                created_by=actor, idempotency_key=a.get("idempotency_key"))
            return {"task": p.owner_view(row, s), "state": "queued"}
        if tool == "get_task":
            row = s.get_task(a["task_id"])
            if row is None:
                raise p.V5Error("任务不存在", 404, "task_not_found")
            return {"task": p.owner_view(row, s, with_events=bool(a.get("include_events")))}
        if tool == "list_tasks":
            return {"tasks": [p.owner_view(t, s) for t in s.for_owner(
                a.get("project_id"), limit=a.get("limit") or 50, offset=a.get("offset") or 0)]}
        if tool == "decide_task":
            return s.decide_task(a["task_id"], a.get("action"), a.get("reason"))
        if tool == "list_my_tasks":
            return {"tasks": [p.agent_view(t, s) for t in s.for_assignee(actor)],
                    "note": LIST_NOTE}
        if tool == "claim_task":
            return s.claim_task(a["task_id"], actor, self._name(actor),
                                expected_version=a.get("expected_version"),
                                attempt_id=a.get("attempt_id"))
        if tool == "report_progress":
            return s.report_progress(a["task_id"], actor, self._name(actor), a.get("text"),
                                     expected_version=a.get("expected_version"),
                                     attempt_id=a.get("attempt_id"))
        if tool == "heartbeat":
            return s.heartbeat(a["task_id"], actor,
                               expected_version=a.get("expected_version"),
                               attempt_id=a.get("attempt_id"))
        if tool == "report_status":
            return s.report_status(actor, a.get("quota_text"), a.get("quota_reset_at"))
        if tool == "release_task":
            return s.release_task(a["task_id"], actor, self._name(actor), a.get("reason"),
                                  expected_version=a.get("expected_version"),
                                  attempt_id=a.get("attempt_id"))
        if tool == "submit_result":
            if a.get("verdict") is not None:
                return s.submit_verdict(a["task_id"], actor, self._name(actor), a["verdict"],
                                        a.get("summary"),
                                        expected_version=a.get("expected_version"),
                                        attempt_id=a.get("attempt_id"))
            return s.submit_work(a["task_id"], actor, self._name(actor), a.get("summary"),
                                 branch=a.get("branch"), commit=a.get("commit"),
                                 pr_url=a.get("pr_url"),
                                 expected_version=a.get("expected_version"),
                                 attempt_id=a.get("attempt_id"))
        if tool == "attach_delivery_diff":
            return s.attach_delivery(a["task_id"], actor, self._name(actor), a.get("page"),
                                     a.get("total_pages"), a.get("text"),
                                     truncated=bool(a.get("truncated", False)),
                                     expected_version=a.get("expected_version"),
                                     attempt_id=a.get("attempt_id"))
        if tool == "get_delivery_diff":
            return s.get_delivery_page(a["task_id"], a.get("page"), actor)
        raise AssertionError(f"unknown tool {tool}")

    def _name(self, agent_id):
        row = self.store.get_agent(agent_id)
        return row["name"] if row else agent_id

    # -- recording ------------------------------------------------------------
    def agent(self, agent_id, name, scopes, roles):
        self.store.create_agent(agent_id, name, "tok-" + agent_id, scopes, roles)

    def call(self, actor, tool, **args):
        self._register_ids()
        recorded_args = self.norm(args)
        try:
            result = self._execute(actor, tool, args)
            expect = {"result": self.norm(result)}
        except self.p.V5Error as exc:
            expect = {"error": {"status": exc.http_status, "code": exc.code,
                                "message": self.norm(exc.message)}}
        self._register_ids()
        self.steps.append({
            "call": {"actor": actor, "tool": tool, "args": recorded_args},
            "expect": expect,
            "state_after": self.snapshot(),
        })

    def advance(self, seconds):
        self.clock[0] += seconds
        self.steps.append({"advance_clock_seconds": seconds,
                           "state_after": self.snapshot()})

    def task(self, n):
        """Real id of TASK_n (1-based, creation order)."""
        self._register_ids()
        for real, placeholder in self.idmap.items():
            if placeholder == f"TASK_{n}":
                return real
        raise AssertionError(f"TASK_{n} not created yet")


def seed_basic(rec):
    rec.agent("boss", "Boss", ["owner"], [])
    rec.agent("dev1", "DevA", ["agent"], ["dev"])
    rec.agent("dev2", "DevB", ["agent"], ["dev"])
    rec.agent("rev1", "RevA", ["agent"], ["review"])


def seed_with_pack(rec):
    seed_basic(rec)
    rec.agent("pack1", "PackA", ["agent"], ["pack"])


# ---------------------------------------------------------------------------
# A-group scenarios: recorded against the reference implementation.
# ---------------------------------------------------------------------------

GOAL = "实现登录页的记住我功能"
DIFF1 = "diff --git a/Login.kt b/Login.kt\n+rememberMe()\n"
DIFF2 = "diff --git a/LoginTest.kt b/LoginTest.kt\n+testRememberMe()\n"


def a01(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL, idempotency_key="key-1")
    t1 = rec.task(1)
    # Same idempotency key: replay returns the same task, no duplicate.
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL, idempotency_key="key-1")
    rec.call("dev1", "list_my_tasks")
    rec.call("dev2", "list_my_tasks")
    rec.call("boss", "list_tasks", project_id="proj")
    rec.call("boss", "get_task", task_id=t1, include_events=True)


def a02(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    # Not the assignee: the task is invisible (404), not forbidden.
    rec.call("dev2", "claim_task", task_id=t1)
    rec.call("dev1", "claim_task", task_id=t1)
    # Re-claim by the holder renews the lease.
    rec.call("dev1", "claim_task", task_id=t1)
    # Claimed by someone else: 409.
    rec.call("dev2", "claim_task", task_id=t1)


def a03(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1, expected_version=99,
             attempt_id="c-1")
    rec.call("dev1", "claim_task", task_id=t1, expected_version=1,
             attempt_id="c-1")
    # Same attempt_id but the version has moved on: the version check fires
    # FIRST (409 stale_version). Attempt replay only short-circuits when the
    # expected_version matches or is omitted (see the heartbeat pair below).
    rec.call("dev1", "claim_task", task_id=t1, expected_version=1,
             attempt_id="c-1")
    rec.call("dev1", "heartbeat", task_id=t1, attempt_id="h-1")
    rec.call("dev1", "heartbeat", task_id=t1, attempt_id="h-1")
    rec.call("dev1", "report_progress", task_id=t1, text="做了一半",
             expected_version=1, attempt_id="p-1")


def a04(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "report_progress", task_id=t1, text="开始处理")
    rec.call("dev1", "heartbeat", task_id=t1)
    # Releasing a work task sends it to needs_decision (reason=released).
    rec.call("dev1", "release_task", task_id=t1, reason="手头有更急的")
    rec.call("boss", "get_task", task_id=t1)


def a05(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="做完了")
    rec.call("dev1", "submit_result", task_id=t1, summary="做完了",
             branch="feat/login", commit="not-a-sha")


def a06(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现并自测",
             branch="feat/login", commit="abc1234")
    review = rec.task(2)
    rec.call("rev1", "list_my_tasks")
    rec.call("rev1", "claim_task", task_id=review)
    rec.call("rev1", "submit_result", task_id=review, verdict="approve",
             summary="改动符合目标，测试齐全")
    rec.call("boss", "get_task", task_id=t1, include_events=True)


def a07(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    review = None
    for round_no in (1, 2, 3):
        rec.call("dev1", "claim_task", task_id=t1)
        rec.call("dev1", "submit_result", task_id=t1,
                 summary=f"第 {round_no} 版实现", branch="feat/login")
        review = rec.task(1 + round_no)
        rec.call("rev1", "claim_task", task_id=review)
        rec.call("rev1", "submit_result", task_id=review,
                 verdict="changes_requested", summary=f"第 {round_no} 处问题")
    # Round 3 rejection exhausts max_rounds -> needs_decision.
    rec.call("boss", "get_task", task_id=t1, include_events=True)
    rec.call("boss", "decide_task", task_id=t1, action="done",
             reason="就按现在的版本收")
    rec.call("boss", "get_task", task_id=t1)


def a08(rec):
    # No reviewer: submit goes straight to needs_decision (no_reviewer).
    rec.call("boss", "start_task", agent="dev1", project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="做完了",
             commit="abc1234")
    rec.call("boss", "decide_task", task_id=t1, action="cancel",
             reason="需求变了，不做了")
    rec.call("boss", "get_task", task_id=t1)


def a09(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.advance(7201)
    # The sweep runs on list: expired claim returns to the queue.
    rec.call("dev1", "list_my_tasks")
    rec.call("boss", "get_task", task_id=t1, include_events=True)
    rec.call("dev1", "claim_task", task_id=t1)
    # Acting on an expired lease without re-claiming is refused.
    rec.advance(7201)
    rec.call("dev1", "heartbeat", task_id=t1)


def a10(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    # A pack agent exists and a branch was given: pack subtask opens first.
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现",
             branch="feat/login", commit="abc1234")
    pack = rec.task(2)
    rec.call("pack1", "list_my_tasks")
    rec.call("pack1", "claim_task", task_id=pack)
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=1,
             total_pages=2, text=DIFF1)
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=2,
             total_pages=2, text=DIFF2)
    review = rec.task(3)
    rec.call("rev1", "claim_task", task_id=review)
    rec.call("rev1", "get_delivery_diff", task_id=review, page=1)
    rec.call("dev1", "get_delivery_diff", task_id=t1, page=1)
    rec.call("rev1", "submit_result", task_id=review, verdict="approve",
             summary="diff 与描述一致")
    rec.call("boss", "get_task", task_id=t1)


def a11(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现",
             branch="feat/login")
    # Pack task sits unclaimed for over 30 minutes: sweep skips the pack
    # step and opens the review directly.
    rec.advance(1801)
    rec.call("dev1", "list_my_tasks")
    rec.call("boss", "get_task", task_id=t1, include_events=True)
    rec.call("rev1", "list_my_tasks")


def a12(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    # Submit before claiming: state guard refuses.
    rec.call("dev1", "submit_result", task_id=t1, summary="抢跑",
             branch="feat/login")
    rec.call("dev1", "claim_task", task_id=t1)
    # Verdict on a work task: refused, use summary submit instead.
    rec.call("dev1", "submit_result", task_id=t1, verdict="approve",
             summary="自审通过")
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现",
             branch="feat/login")
    review = rec.task(2)
    rec.call("rev1", "claim_task", task_id=review)
    # Summary-style submit on a review task: refused, use verdict.
    rec.call("rev1", "submit_result", task_id=review, summary="看完了",
             branch="feat/login")


def a13(rec):
    rec.call("dev1", "report_status", quota_text="本周还剩 40%",
             quota_reset_at="周二 17:53")
    rec.call("dev1", "report_status")
    rec.call("dev1", "report_status", quota_text="x" * 501)


def a14(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现",
             branch="feat/login")
    review = rec.task(2)
    rec.call("rev1", "claim_task", task_id=review)
    # Reviewer releasing cancels the review and escalates the work task.
    rec.call("rev1", "release_task", task_id=review, reason="没空审")
    rec.call("boss", "get_task", task_id=t1)


def a15(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="第一版",
             branch="feat/login")
    review = rec.task(2)
    rec.call("rev1", "claim_task", task_id=review)
    rec.call("rev1", "submit_result", task_id=review,
             verdict="changes_requested", summary="补测试")
    # Rework is reserved for the original implementer: dev2 is refused.
    rec.call("dev2", "claim_task", task_id=t1)
    # Even after the reservation expires, the baseline stays assignee-only:
    # dev2 still gets 404. Cross-agent pickup needs an owner reassignment
    # (update_task, B group) - the reservation alone never opens the task.
    rec.advance(7201)
    rec.call("dev2", "list_my_tasks")
    rec.call("dev2", "claim_task", task_id=t1)
    rec.call("dev1", "claim_task", task_id=t1)


def c01(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    # bool is not an int: expected_version type is enforced.
    rec.call("dev1", "claim_task", task_id=t1, expected_version=True)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现",
             branch="feat/login")
    pack = rec.task(2)
    rec.call("pack1", "claim_task", task_id=pack)
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=0,
             total_pages=2, text=DIFF1)
    # Only pack-role agents may attach.
    rec.call("dev2", "attach_delivery_diff", task_id=pack, page=1,
             total_pages=2, text=DIFF1)
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=1,
             total_pages=2, text=DIFF1)
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=2,
             total_pages=2, text=DIFF2)
    review = rec.task(3)
    rec.call("rev1", "claim_task", task_id=review)
    # Page beyond the delivered range: 404 delivery_page_missing.
    rec.call("rev1", "get_delivery_diff", task_id=review, page=3)


def c02(rec):
    rec.call("boss", "start_task", agent="dev1", project_id="proj", goal="")
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    # decide_task only applies to needs_decision.
    rec.call("boss", "decide_task", task_id=t1, action="done", reason="提前收")
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="做完了",
             pr_url="notaurl")


def c03(rec):
    rec.call("boss", "start_task", agent="dev1", reviewer="rev1",
             project_id="proj", goal=GOAL)
    t1 = rec.task(1)
    rec.call("dev1", "claim_task", task_id=t1)
    rec.call("dev1", "submit_result", task_id=t1, summary="已实现",
             branch="feat/login")
    pack = rec.task(2)
    rec.call("pack1", "claim_task", task_id=pack)
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=1,
             total_pages=2, text=DIFF1)
    # total_pages must stay consistent across pages.
    rec.call("pack1", "attach_delivery_diff", task_id=pack, page=2,
             total_pages=3, text=DIFF2)


SCENARIOS = [
    ("a01-create-and-list", seed_basic, a01,
     "Create with reviewer + idempotency replay; assignee/owner list views."),
    ("a02-claim-flow", seed_basic, a02,
     "Claim by assignee only; invisible to others; re-claim renews; 409 when taken."),
    ("a03-fencing", seed_basic, a03,
     "expected_version optimistic lock (409 stale_version) + attempt_id replay."),
    ("a04-progress-heartbeat-release", seed_basic, a04,
     "Progress records an event, heartbeat does not; releasing work -> needs_decision."),
    ("a05-submit-evidence-gate", seed_basic, a05,
     "Submit without evidence is refused (422); bad commit sha refused."),
    ("a06-submit-review-approve", seed_basic, a06,
     "Submit opens a review subtask; approve -> work approved."),
    ("a07-rework-rounds", seed_basic, a07,
     "changes_requested requeues with round+1 reserved for the implementer; "
     "rounds exhausted -> needs_decision; decide done."),
    ("a08-decide-cancel", seed_basic, a08,
     "No reviewer -> needs_decision on submit; decide cancel."),
    ("a09-lease-expiry", seed_basic, a09,
     "Expired claim returns to queue on sweep; acting on expired lease refused."),
    ("a10-pack-flow", seed_with_pack, a10,
     "Pack agent present: submit opens pack subtask; pages complete -> review opens; "
     "delivery diff readable by reviewer only."),
    ("a11-pack-timeout-skip", seed_with_pack, a11,
     "Pack task unclaimed >30 min: sweep cancels it and opens review directly."),
    ("a12-submit-state-guards", seed_basic, a12,
     "Submit before claim refused; verdict/summary cross-use refused."),
    ("a13-report-status", seed_basic, a13,
     "Quota report upsert; empty and oversize reports refused."),
    ("a14-release-review", seed_basic, a14,
     "Reviewer release cancels review; work escalates to needs_decision."),
    ("a15-reservation-expiry", seed_basic, a15,
     "Rework reservation blocks others; after it expires the baseline is still "
     "assignee-only (dev2 gets 404) - cross-agent pickup requires update_task."),
    ("c01-adversarial-delivery", seed_with_pack, c01,
     "Adversarial: bool expected_version, page 0, non-pack attach, missing diff page."),
    ("c02-adversarial-basics", seed_basic, c02,
     "Adversarial: empty goal, decide on a queued task, malformed pr_url."),
    ("c03-adversarial-pages", seed_with_pack, c03,
     "Adversarial: total_pages changes mid-delivery."),
]


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    baseline_dir, out_dir = sys.argv[1], sys.argv[2]
    os.makedirs(out_dir, exist_ok=True)
    platform = load_platform(baseline_dir)
    for name, seed, fn, description in SCENARIOS:
        rec = Recorder(platform)
        seed(rec)
        fn(rec)
        fixture = {
            "name": name,
            "group": name[0].upper(),
            "description": description,
            "seed": {"agents": [
                {"id": "boss", "scopes": ["owner"], "roles": []},
                {"id": "dev1", "scopes": ["agent"], "roles": ["dev"]},
                {"id": "dev2", "scopes": ["agent"], "roles": ["dev"]},
                {"id": "rev1", "scopes": ["agent"], "roles": ["review"]},
            ] + ([{"id": "pack1", "scopes": ["agent"], "roles": ["pack"]}]
                 if seed is seed_with_pack else []),
                "lease_seconds": 7200},
            "steps": rec.steps,
        }
        path = os.path.join(out_dir, name + ".json")
        with open(path, "w", encoding="utf-8") as fh:
            json.dump(fixture, fh, ensure_ascii=False, indent=2)
        print(f"wrote {path} ({len(rec.steps)} steps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
