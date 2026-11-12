package wherevercode.agent.python

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import org.json.JSONObject
import wherevercode.agent.core.python.PythonProbe
import wherevercode.agent.core.python.PythonRunRequest
import wherevercode.agent.core.python.PythonRunResult
import wherevercode.agent.core.python.PythonRuntime

/**
 * ChaquopyPythonRuntime：App 内嵌 CPython（3.11）的真实执行实现。
 *
 * 为什么需要它（2026-11-09 K70 能力缺口）：
 * - APK 已经打包了 libpython3.11.so，但 stock Android 的 PATH 上没有
 *   可执行的 `python3`；只靠 ProcessBuilder 的话，Agent 永远只能报告
 *   "Python 不可用"，写好的 calculator.py / 测试文件跑不起来。
 * - Chaquopy 在同一进程里调用 CPython，不受 Android 10 的 W^X 限制，
 *   是真执行（同样的解释器、同样的退出码与输出），不是模拟。
 *
 * 线程与 cwd：Python 调用串行化（[pythonLock]），helper 在运行期间
 * chdir 到工作区并在 finally 恢复，避免并发执行互相踩 cwd。
 *
 * 失败语义：初始化/调用失败一律返回 available=false + 原因；绝不伪造
 * 成功结果。
 */
class ChaquopyPythonRuntime(context: Context) : PythonRuntime {

    private val appContext = context.applicationContext
    private val pythonLock = Any()

    @Volatile
    private var initError: String? = null

    @Volatile
    private var cachedProbe: PythonProbe? = null

    private fun ensureStarted(): String? {
        if (Python.isStarted()) return null
        synchronized(pythonLock) {
            if (Python.isStarted()) return null
            initError?.let { return it }
            return try {
                Python.start(AndroidPlatform(appContext))
                null
            } catch (e: Throwable) {
                // Chaquopy not bundled / abi mismatch / missing asset:
                // report the real reason instead of pretending Python works.
                val reason = "内嵌 CPython 启动失败：${e.javaClass.simpleName}: ${e.message ?: "未知错误"}"
                initError = reason
                reason
            }
        }
    }

    private fun helperModule(): com.chaquo.python.PyObject {
        ensureStarted()?.let { throw IllegalStateException(it) }
        return Python.getInstance().getModule(HELPER_MODULE)
    }

    override fun probe(): PythonProbe {
        cachedProbe?.let { return it }
        val result = try {
            ensureStarted()?.let { reason ->
                return PythonProbe(
                    available = false,
                    label = LABEL,
                    reason = reason,
                ).also { cachedProbe = it }
            }
            val raw = synchronized(pythonLock) {
                helperModule().callAttr("version").toString()
            }
            val json = JSONObject(raw)
            val version = json.optString("version", "").ifBlank { null }
            PythonProbe(
                available = true,
                label = LABEL,
                version = version,
                executable = json.optString("executable", "").ifBlank { null },
                rawOutput = raw,
            )
        } catch (e: Throwable) {
            PythonProbe(
                available = false,
                label = LABEL,
                reason = "内嵌 CPython 探针失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
        cachedProbe = result
        return result
    }

    override fun run(request: PythonRunRequest): PythonRunResult {
        val probe = probe()
        if (!probe.available) {
            return PythonRunResult(
                available = false,
                runtimeLabel = probe.label,
                failureReason = probe.reason,
            )
        }
        val argsJson = org.json.JSONArray(request.args).toString()
        return try {
            val raw = synchronized(pythonLock) {
                helperModule().callAttr(
                    "run",
                    request.script?.absolutePath ?: "",
                    request.cwd.absolutePath,
                    argsJson,
                    request.module ?: "",
                    request.timeoutMillis,
                ).toString()
            }
            parseRunResult(raw)
        } catch (e: Throwable) {
            PythonRunResult(
                available = true,
                runtimeLabel = LABEL,
                exitCode = -1,
                failureReason = "内嵌 CPython 执行失败：${e.message ?: e.javaClass.simpleName}",
            )
        }
    }

    companion object {
        const val LABEL: String = "内嵌 CPython（Chaquopy，进程内真实执行）"
        const val HELPER_MODULE: String = "agent_runner"

        /**
         * 解析 agent_runner.py 的 JSON 返回。单独抽出来是为了能在
         * Robolectric（Android 框架 org.json）下做回归测试；真机 APK 与
         * JVM 单测的 org.json 对 JSON null 的处理不同。
         *
         * Android 的 `optString(name, fallback)` 在值为 JSON null 时返回
         * 字面量 "null" 而不是 fallback；helper 成功执行时 `error` 就是
         * JSON null。不先 isNull 判断，failureReason 会变成字符串 "null"，
         * 上层把退出码 0 的成功执行误报成 "Python 执行失败：null"。
         */
        internal fun parseRunResult(raw: String): PythonRunResult {
            val json = JSONObject(raw)
            return PythonRunResult(
                available = true,
                runtimeLabel = LABEL,
                exitCode = json.optInt("exit_code", -1),
                stdout = json.optString("stdout", ""),
                stderr = json.optString("stderr", ""),
                timedOut = json.optBoolean("timed_out", false),
                failureReason = if (json.isNull("error")) {
                    null
                } else {
                    json.optString("error", "").ifBlank { null }
                },
            )
        }
    }
}
