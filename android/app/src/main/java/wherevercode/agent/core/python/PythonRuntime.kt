package wherevercode.agent.core.python

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

/**
 * PythonRuntime：真实执行 Python 的唯一入口（2026-11-09）。
 *
 * 设计约束（来自验收要求）：
 * - 只能"真实执行"，不存在任何模拟/伪造输出/伪造退出码的分支；
 * - 运行时不可用时必须返回 available=false + 可读原因，由上层如实
 *   报告能力缺口，不得降级成"看起来成功"；
 * - 退出码、stdout、stderr 全部来自真实运行。
 *
 * 实现：
 * - [ProcessPythonRuntime]：启动系统 python3 子进程（JVM 测试 / Termux
 *   等有 python 可执行文件的环境）。
 * - Chaquopy 实现（app 层，AndroidApp 内嵌 CPython 3.11）在 app 模块，
 *   通过 [PreferEmbeddedPythonRuntime] 组合：优先内嵌运行时，不可用时
 *   回退到系统 python3。
 */
data class PythonProbe(
    val available: Boolean,
    /** 人类可读的运行时标签，例如"内嵌 CPython 3.11（Chaquopy）"。 */
    val label: String,
    val version: String? = null,
    val executable: String? = null,
    /** available=false 时的中文原因。 */
    val reason: String? = null,
    /** 真实探针输出（版本行等），供模型/用户核对。 */
    val rawOutput: String? = null,
)

data class PythonRunRequest(
    /** 要运行的工作区 .py 文件；与 [module] 二选一。 */
    val script: File? = null,
    /** `python -m <module>` 形式；与 [script] 二选一。 */
    val module: String? = null,
    /** 传给脚本/模块的参数（不含 argv[0]）。 */
    val args: List<String> = emptyList(),
    val cwd: File,
    val timeoutMillis: Long = 30_000,
)

data class PythonRunResult(
    val available: Boolean,
    val runtimeLabel: String,
    val exitCode: Int = -1,
    val stdout: String = "",
    val stderr: String = "",
    val timedOut: Boolean = false,
    val failureReason: String? = null,
)

interface PythonRuntime {
    fun probe(): PythonProbe

    fun run(request: PythonRunRequest): PythonRunResult
}

/**
 * 优先内嵌运行时、不可用时回退系统 python3 的组合运行时。
 * 两者都不可用 → 如实报告两个原因，不做任何模拟。
 */
class PreferEmbeddedPythonRuntime(
    private val embedded: PythonRuntime,
    private val fallback: PythonRuntime = ProcessPythonRuntime(),
) : PythonRuntime {

    private fun pick(): Pair<PythonRuntime, PythonProbe> {
        val primary = embedded.probe()
        if (primary.available) return embedded to primary
        val secondary = fallback.probe()
        if (secondary.available) {
            return fallback to secondary.copy(
                reason = null,
                label = secondary.label + "（内嵌运行时不可用：${primary.reason ?: "未知原因"}）",
            )
        }
        return embedded to primary.copy(
            reason = primary.reason ?: secondary.reason ?: "没有可用的 Python 运行时",
        )
    }

    override fun probe(): PythonProbe = pick().second

    override fun run(request: PythonRunRequest): PythonRunResult {
        val (runtime, probe) = pick()
        if (!probe.available) {
            return PythonRunResult(
                available = false,
                runtimeLabel = probe.label,
                failureReason = probe.reason,
            )
        }
        return runtime.run(request)
    }
}

/**
 * 用系统 python3 子进程真实执行。JVM 单测与宿主/ Termux 环境使用。
 *
 * 输出读取在独立线程里进行（先读后等），避免子进程写满管道后卡死；
 * 超时/停止会 destroyForcibly 后再读，绝不留下僵尸进程。
 */
class ProcessPythonRuntime(
    private val command: String = "python3",
    private val probeTimeoutMillis: Long = 5_000,
) : PythonRuntime {

    companion object {
        /** 单次执行保留的输出上限（字符）。超出部分丢弃并标注。 */
        const val MAX_CAPTURED_CHARS: Int = 1_000_000
    }

    @Volatile
    private var cachedProbe: PythonProbe? = null

    override fun probe(): PythonProbe {
        cachedProbe?.let { return it }
        val result = try {
            val process = ProcessBuilder(command, "--version")
                .redirectErrorStream(true)
                .start()
            val drain = Drain(process.inputStream, MAX_CAPTURED_CHARS)
            val thread = Thread(drain, "python-probe-drain").apply { isDaemon = true }
            thread.start()
            val finished = process.waitFor(probeTimeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                thread.join(1_000)
                PythonProbe(
                    available = false,
                    label = "系统 $command",
                    reason = "python3 --version 超时（>${probeTimeoutMillis}ms）",
                )
            } else {
                thread.join(1_000)
                val output = drain.text().trim()
                if (process.exitValue() == 0) {
                    val version = output.lineSequence().firstOrNull { it.isNotBlank() } ?: output
                    PythonProbe(
                        available = true,
                        label = "系统 $command",
                        version = version,
                        executable = resolveExecutable(),
                        rawOutput = output,
                    )
                } else {
                    PythonProbe(
                        available = false,
                        label = "系统 $command",
                        reason = "python3 --version 退出码 ${process.exitValue()}：$output",
                    )
                }
            }
        } catch (e: IOException) {
            PythonProbe(
                available = false,
                label = "系统 $command",
                reason = "找不到可执行的 $command：${e.message ?: "未安装或不在 PATH"}",
            )
        } catch (e: Exception) {
            PythonProbe(
                available = false,
                label = "系统 $command",
                reason = "探测 $command 失败：${e.message ?: "未知错误"}",
            )
        }
        cachedProbe = result
        return result
    }

    private fun resolveExecutable(): String? {
        val path = System.getenv("PATH").orEmpty()
        if (path.isEmpty()) return null
        for (dir in path.split(File.pathSeparator)) {
            val candidate = File(dir, command)
            if (candidate.isFile && candidate.canExecute()) return candidate.absolutePath
        }
        return null
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
        val argv = mutableListOf(command)
        if (request.script != null) {
            argv += request.script.absolutePath
        } else if (request.module != null) {
            argv += listOf("-m", request.module)
        } else {
            return PythonRunResult(
                available = true,
                runtimeLabel = probe.label,
                failureReason = "没有指定脚本或模块",
            )
        }
        argv += request.args
        return try {
            val process = ProcessBuilder(argv)
                .directory(request.cwd)
                .start()
            val outDrain = Drain(process.inputStream, MAX_CAPTURED_CHARS)
            val errDrain = Drain(process.errorStream, MAX_CAPTURED_CHARS)
            val outThread = Thread(outDrain, "python-run-stdout").apply { isDaemon = true }
            val errThread = Thread(errDrain, "python-run-stderr").apply { isDaemon = true }
            outThread.start()
            errThread.start()
            val finished = process.waitFor(request.timeoutMillis, TimeUnit.MILLISECONDS)
            if (!finished) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                outThread.join(2_000)
                errThread.join(2_000)
                PythonRunResult(
                    available = true,
                    runtimeLabel = probe.label,
                    exitCode = -1,
                    stdout = outDrain.text(),
                    stderr = errDrain.text(),
                    timedOut = true,
                    failureReason = "执行超过 ${request.timeoutMillis}ms，已强制结束",
                )
            } else {
                // 进程已退出，但读线程可能还在排空最后一点输出。
                outThread.join(5_000)
                errThread.join(5_000)
                PythonRunResult(
                    available = true,
                    runtimeLabel = probe.label,
                    exitCode = process.exitValue(),
                    stdout = outDrain.text(),
                    stderr = errDrain.text(),
                )
            }
        } catch (e: Exception) {
            PythonRunResult(
                available = true,
                runtimeLabel = probe.label,
                failureReason = "启动 $command 失败：${e.message ?: "未知错误"}",
            )
        }
    }

    private class Drain(
        private val input: InputStream,
        private val maxChars: Int,
    ) : Runnable {
        private val builder = StringBuilder()
        private var truncated = false

        override fun run() {
            try {
                val buffer = ByteArray(8192)
                var read: Int
                while (input.read(buffer).also { read = it } != -1) {
                    if (builder.length < maxChars) {
                        val room = maxChars - builder.length
                        val text = String(buffer, 0, minOf(read, room), Charsets.UTF_8)
                        builder.append(text)
                        if (read > room) truncated = true
                    } else {
                        truncated = true
                    }
                }
            } catch (_: Exception) {
                // 流被关闭/进程被杀：已读到的内容仍然有效。
            }
        }

        fun text(): String = if (truncated) {
            builder.toString() + "\n…（输出超过 $maxChars 字符，已截断）"
        } else {
            builder.toString()
        }
    }
}
