package wherevercode.agent.core.platform

import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Test

/**
 * 任务派工对拍夹具执行器（W2）：把 contracts/task-dispatch/fixtures/
 * 逐份喂给本机 TaskDispatchService，按 spec §8 的口径核对——
 * result 子集比对、error 与 state_after 精确比对、时间戳 "<ts>"
 * 通配、任务 id 按创建顺序归一为 TASK_n。
 *
 * 夹具按落地包逐步启用（ENABLED）：未启用的整份跳过（Assume），
 * 不是通过——W15 前必须全部启用。A/C 组由线上基线录制，B 组按
 * spec 手写，runner 不区分来源。
 */
class TaskDispatchFixtureTest {

    companion object {
        private const val BASE_TS = 1_700_000_000.0

        /** 已启用的夹具（按 W 包推进逐步加满，W15 前清空跳过）。 */
        private val ENABLED = setOf(
            "a01-create-and-list",
            "a02-claim-flow",
            "a03-fencing",
            "a04-progress-heartbeat-release",
            "a05-submit-evidence-gate",
            "a06-submit-review-approve",
            "a07-rework-rounds",
            "a08-decide-cancel",
            "a09-lease-expiry",
            "a10-pack-flow",
            "a11-pack-timeout-skip",
            "a12-submit-state-guards",
            "a13-report-status",
            "a14-release-review",
            "a15-reservation-expiry",
            "b06-role-exposure",
            "c01-adversarial-delivery",
            "c02-adversarial-basics",
            "c03-adversarial-pages",
        )

        /** 录制时的显示名（recorder 口径）：directory 与 callerName 都用它。 */
        private val DISPLAY_NAMES = mapOf(
            "boss" to "Boss",
            "dev1" to "DevA",
            "dev2" to "DevB",
            "rev1" to "RevA",
            "pack1" to "PackA",
        )

        private fun fixturesDir(): File {
            var dir: File? = File(System.getProperty("user.dir")).absoluteFile
            while (dir != null) {
                val candidate = File(dir, "contracts/task-dispatch/fixtures")
                if (candidate.isDirectory) return candidate
                dir = dir.parentFile
            }
            fail("找不到 contracts/task-dispatch/fixtures（user.dir=${System.getProperty("user.dir")}）")
            throw AssertionError("unreachable")
        }
    }

    @Test
    fun allEnabledFixturesPass() {
        val dir = fixturesDir()
        val listed = dir.listFiles { f -> f.name.endsWith(".json") }
        if (listed == null || listed.isEmpty()) throw AssertionError("夹具目录为空")
        val files = listed.sortedBy { it.name }
        val failures = mutableListOf<String>()
        var ran = 0
        for (file in files) {
            val fixture = JSONObject(file.readText(Charsets.UTF_8))
            val name = fixture.getString("name")
            if (name !in ENABLED) continue
            ran += 1
            try {
                runFixture(fixture)
            } catch (e: AssertionError) {
                failures += "$name: ${e.message}"
            }
        }
        assertTrue("没有启用任何夹具", ran > 0)
        if (failures.isNotEmpty()) {
            fail("夹具失败 ${failures.size} 份:\n" + failures.joinToString("\n"))
        }
    }

    @Test
    fun disabledFixturesAreSkippedNotPassed() {
        // 占位提醒：未启用的夹具必须显式跳过，禁止静默当通过。
        val dir = fixturesDir()
        val total = dir.listFiles { f -> f.name.endsWith(".json") }?.size ?: 0
        Assume.assumeTrue("全部夹具已启用", ENABLED.size >= total)
    }

    // ---- 执行 ------------------------------------------------------------

    private fun runFixture(fixture: JSONObject) {
        val seed = fixture.getJSONObject("seed")
        val seedAgents = seed.getJSONArray("agents")
        val scopes = mutableMapOf<String, String>()
        val names = mutableMapOf<String, String>()
        val rolesById = mutableMapOf<String, List<String>>()
        val seedOrder = mutableListOf<String>()
        for (i in 0 until seedAgents.length()) {
            val agent = seedAgents.getJSONObject(i)
            val id = agent.getString("id")
            names[id] = DISPLAY_NAMES[id] ?: id
            seedOrder += id
            val scopeList = agent.getJSONArray("scopes")
            var scope = TaskDispatchService.SCOPE_AGENT
            for (j in 0 until scopeList.length()) {
                if (scopeList.getString(j) == "owner") scope = TaskDispatchService.SCOPE_OWNER
            }
            scopes[id] = scope
            val roleList = agent.optJSONArray("roles")
            val roles = mutableListOf<String>()
            if (roleList != null) {
                for (j in 0 until roleList.length()) roles += roleList.getString(j)
            }
            rolesById[id] = roles
        }
        val roster = object : TaskAgentRoster {
            override fun agentRoles(agentId: String): List<String>? = rolesById[agentId]
            override fun firstAgentWithRole(role: String): Pair<String, String>? =
                seedOrder.firstOrNull { rolesById[it]?.contains(role) == true }
                    ?.let { it to (names[it] ?: it) }
        }
        var now = BASE_TS
        val recMap = recorderIdMap(fixture)
        val store = TaskDispatchStore(InMemoryTaskDispatchPersistence()) { now }
        val service = TaskDispatchService(
            store = store,
            directory = TaskAgentDirectory { id -> names[id] },
            bridge = null,
            clock = { now },
            leaseSeconds = seed.optDouble("lease_seconds", TaskDispatchSpec.LEASE_SECONDS),
            roster = roster,
        )
        val steps = fixture.getJSONArray("steps")
        for (index in 0 until steps.length()) {
            val step = steps.getJSONObject(index)
            if (step.has("advance_clock_seconds")) {
                // 纯推进时钟步：没有调用，只核对推进后的状态快照
                // （清扫是懒触发的，推进本身不改账本）。
                now += step.getDouble("advance_clock_seconds")
                val idMapAdvance = buildIdMap(store)
                val advanceMismatch = exactMismatch(
                    toKotlin(step.get("state_after")),
                    snapshotState(store, idMapAdvance),
                    "state_after",
                )
                if (advanceMismatch != null) {
                    fail("step ${index + 1} (advance_clock) $advanceMismatch")
                }
                continue
            }
            val call = step.getJSONObject("call")
            val actor = call.getString("actor")
            val tool = call.getString("tool")
            val rawArgs = call.optJSONObject("args") ?: JSONObject()
            val args = substituteArgs(rawArgs, store) as JSONObject
            var result: Any? = null
            var error: TaskDispatchException? = null
            try {
                result = when (tool) {
                    "tools/list" -> JSONObject().put(
                        "tools",
                        JSONArray(TaskDispatchService.toolNames(scopes[actor] ?: "agent")),
                    )
                    "ui_delete_task" -> service.uiDeleteTask(args, actor)
                    else -> service.dispatch(
                        tool, args, actor, names[actor] ?: actor, scopes[actor] ?: "agent",
                    )
                }
            } catch (e: TaskDispatchException) {
                error = e
            }
            val idMap = buildIdMap(store)
            val expect = step.getJSONObject("expect")
            val where = "step ${index + 1} ($actor $tool)"
            if (expect.has("error")) {
                val expected = expect.getJSONObject("error")
                if (error == null) {
                    fail("$where 应报错 ${expected.getString("code")}，实际成功: $result")
                }
                assertEq("$where error.status", expected.getInt("status"), error!!.status)
                assertEq("$where error.code", expected.getString("code"), error.code)
                assertEq("$where error.message", expected.getString("message"), error.message)
            } else {
                if (error != null) {
                    fail("$where 应成功，实际报错 ${error.code}: ${error.message}")
                }
                val expectedResult = normalize(toKotlin(expect.get("result")), recMap)
                val actualResult = normalize(toKotlin(result), idMap)
                val mismatch = subsetMismatch(expectedResult, actualResult, "result")
                if (mismatch != null) fail("$where $mismatch")
            }
            val expectedState = toKotlin(step.get("state_after"))
            val actualState = snapshotState(store, idMap)
            val stateMismatch = exactMismatch(expectedState, actualState, "state_after")
            if (stateMismatch != null) fail("$where $stateMismatch")
        }
    }

    private fun buildIdMap(store: TaskDispatchStore): Map<String, String> {
        val map = mutableMapOf<String, String>()
        var n = 0
        for (taskId in store.tasks.keys) {
            n += 1
            map[taskId] = "TASK_$n"
        }
        return map
    }

    private fun snapshotState(store: TaskDispatchStore, idMap: Map<String, String>): Any {
        val list = mutableListOf<Any?>()
        for (task in store.tasks.values) {
            val entry = mutableMapOf<String, Any?>()
            entry["ref"] = idMap[task.taskId]
            entry["kind"] = task.kind
            entry["parent"] = task.parentId?.let { idMap[it] ?: it }
            entry["state"] = task.state
            entry["round"] = task.round
            entry["version"] = task.version
            entry["assignee_id"] = task.assigneeId
            entry["claimed_by_id"] = task.claimedById
            entry["reserved_for_id"] = task.reservedForId
            entry["reviewer_id"] = task.reviewerId
            entry["decision_reason"] = task.decisionReason
            entry["verdict"] = task.verdict
            entry["delivery_pages"] = task.deliveryPages
            entry["delivery_total_pages"] = task.deliveryTotalPages
            entry["events"] = store.eventsOf(task.taskId).map { it.kind }
            entry["channel_id"] = task.channelId
            entry["epoch"] = task.epoch
            entry["deleted"] = task.deletedAt != null
            list += entry
        }
        return list
    }

    // ---- JSON <-> Kotlin 树 ------------------------------------------------

    private fun toKotlin(value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null
        is JSONObject -> {
            val map = mutableMapOf<String, Any?>()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                map[key] = toKotlin(value.get(key))
            }
            map
        }
        is JSONArray -> {
            val list = mutableListOf<Any?>()
            for (i in 0 until value.length()) list += toKotlin(value.get(i))
            list
        }
        is Int -> value
        is Long -> value.toDouble()
        is Double -> value
        is Boolean -> value
        is String -> value
        else -> value.toString()
    }

    private fun normalize(value: Any?, idMap: Map<String, String>): Any? = when (value) {
        is Map<*, *> -> {
            val out = mutableMapOf<String, Any?>()
            for ((k, v) in value) out[k as String] = normalize(v, idMap)
            out
        }
        is List<*> -> value.map { normalize(it, idMap) }
        is String -> idMap[value]
            ?: if (value.startsWith("v5:")) {
                idMap[value.substring(3)]?.let { "v5:$it" } ?: value
            } else {
                value
            }
        else -> value
    }

    /**
     * 期望侧的录制 id 映射：扫全部步骤期望结果里恰好 32 位十六进制的
     * 字符串（任务 id 的形状；commit SHA 是 40 位或短哈希，不会误伤），
     * 按首次出现顺序编号 TASK_n —— 录制时任务的创建顺序即首次出现顺序。
     */
    private fun recorderIdMap(fixture: JSONObject): Map<String, String> {
        val map = mutableMapOf<String, String>()
        fun visit(value: Any?) {
            when (value) {
                is Map<*, *> -> value.values.forEach { visit(it) }
                is List<*> -> value.forEach { visit(it) }
                is String -> {
                    val candidate = if (value.startsWith("v5:")) value.substring(3) else value
                    if (candidate.length == 32 &&
                        candidate.all { it in '0'..'9' || it in 'a'..'f' } &&
                        candidate !in map
                    ) {
                        map[candidate] = "TASK_${map.size + 1}"
                    }
                }
            }
        }
        val steps = fixture.getJSONArray("steps")
        for (i in 0 until steps.length()) {
            val expect = steps.getJSONObject(i).optJSONObject("expect") ?: continue
            if (expect.has("result")) visit(toKotlin(expect.get("result")))
        }
        return map
    }

    /** 调用参数里的 TASK_n 占位换成账本里的真 id（按创建顺序）。 */
    private fun substituteArgs(value: Any?, store: TaskDispatchStore): Any? = when (value) {
        is JSONObject -> {
            val out = JSONObject()
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val replaced = substituteArgs(value.get(key), store)
                out.put(key, replaced ?: JSONObject.NULL)
            }
            out
        }
        is JSONArray -> {
            val out = JSONArray()
            for (i in 0 until value.length()) {
                val replaced = substituteArgs(value.get(i), store)
                out.put(replaced ?: JSONObject.NULL)
            }
            out
        }
        is String -> {
            val match = Regex("^TASK_(\\d+)$").matchEntire(value)
            if (match != null) {
                val ids = store.tasks.keys.toList()
                val n = match.groupValues[1].toInt()
                if (n in 1..ids.size) ids[n - 1] else value
            } else {
                value
            }
        }
        else -> value
    }

    // ---- 比对 ---------------------------------------------------------------

    private fun assertEq(where: String, expected: Any?, actual: Any?) {
        if (!scalarEquals(expected, actual)) {
            fail("$where 不符：期望 $expected，实际 $actual")
        }
    }

    private fun scalarEquals(expected: Any?, actual: Any?): Boolean {
        if (expected == null || actual == null) return expected == null && actual == null
        if (expected is Number && actual is Number) {
            return expected.toDouble() == actual.toDouble()
        }
        return expected == actual
    }

    /** 子集比对：expected 的每个键/元素都必须在 actual 中命中；"<ts>" 通配任意值。 */
    private fun subsetMismatch(expected: Any?, actual: Any?, path: String): String? {
        if (expected is String && expected == "<ts>") return null
        if (expected is Map<*, *>) {
            if (actual !is Map<*, *>) return "$path 应为对象，实际 $actual"
            for ((k, v) in expected) {
                val key = k as String
                if (!actual.containsKey(key)) {
                    if (v == null) continue
                    return "$path.$key 缺失（实际键 ${actual.keys}）"
                }
                val sub = subsetMismatch(v, actual[key], "$path.$key")
                if (sub != null) return sub
            }
            return null
        }
        if (expected is List<*>) {
            if (actual !is List<*>) return "$path 应为数组，实际 $actual"
            if (expected.size != actual.size) {
                return "$path 长度不符：期望 ${expected.size}，实际 ${actual.size}（$actual）"
            }
            for (i in expected.indices) {
                val sub = subsetMismatch(expected[i], actual[i], "$path[$i]")
                if (sub != null) return sub
            }
            return null
        }
        return if (scalarEquals(expected, actual)) null
        else "$path 不符：期望 $expected，实际 $actual"
    }

    /** 精确比对（state_after 用）：结构与标量全等，expected 有的键 actual 必须同值。 */
    private fun exactMismatch(expected: Any?, actual: Any?, path: String): String? {
        if (expected is Map<*, *>) {
            if (actual !is Map<*, *>) return "$path 应为对象，实际 $actual"
            for ((k, v) in expected) {
                val key = k as String
                val sub = exactMismatch(v, actual[key], "$path.$key")
                if (sub != null) return sub
            }
            return null
        }
        if (expected is List<*>) {
            if (actual !is List<*>) return "$path 应为数组，实际 $actual"
            if (expected.size != actual.size) {
                return "$path 长度不符：期望 ${expected.size}，实际 ${actual.size}（$actual）"
            }
            for (i in expected.indices) {
                val sub = exactMismatch(expected[i], actual[i], "$path[$i]")
                if (sub != null) return sub
            }
            return null
        }
        return if (scalarEquals(expected, actual)) null
        else "$path 不符：期望 $expected，实际 $actual"
    }
}
