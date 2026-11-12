package wherevercode.agent.core.extension.mcp

import java.io.File

/**
 * MCP 测试的共享工具：找测试服务器脚本、起 HTTP 服务器、等异步条件。
 *
 * 服务器脚本在 app/src/test/resources 下（CI 里 classpath 资源；
 * 本地也可用仓库相对路径）。起的进程由调用方登记进 processes，
 * 在 tearDown 里统一杀掉。
 */
object McpTestSupport {

    fun serverScript(name: String = "minimal_mcp_server.py"): File {
        val resource = McpTestSupport::class.java.classLoader
            ?.getResource("wherevercode/agent/core/extension/$name")
        if (resource != null) {
            val file = File(resource.toURI())
            if (file.exists()) return file
        }
        val candidates = listOf(
            File("android/app/src/test/resources/wherevercode/agent/core/extension/$name"),
            File("app/src/test/resources/wherevercode/agent/core/extension/$name"),
        )
        return candidates.firstOrNull { it.exists() } ?: candidates.first()
    }

    /** 起一台测试服务器（stdio 除外），读 READY 行里的端口；超时抛错。 */
    fun startServer(
        script: File,
        processes: MutableList<Process>,
        vararg args: String,
    ): Int {
        val proc = ProcessBuilder(
            listOf("python3", script.absolutePath) + args.toList(),
        ).redirectErrorStream(true).start()
        processes += proc
        val reader = proc.inputStream.bufferedReader()
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            if (!proc.isAlive) error("test server exited early: ${script.name} ${args.joinToString(" ")}")
            if (reader.ready()) {
                val line = reader.readLine() ?: continue
                if (line.startsWith("READY ")) return line.removePrefix("READY ").trim().toInt()
            } else {
                Thread.sleep(50)
            }
        }
        error("test server did not print READY in time: ${script.name}")
    }

    /** 等一个异步条件成立（通知触发的自动刷新等）；超时返回 false。 */
    fun waitUntil(timeoutMs: Long = 5_000, pollMs: Long = 100, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition()) return true
            } catch (_: Exception) {
            }
            Thread.sleep(pollMs)
        }
        return try {
            condition()
        } catch (_: Exception) {
            false
        }
    }

    fun killAll(processes: MutableList<Process>) {
        for (proc in processes) {
            try {
                proc.destroyForcibly()
                proc.waitFor()
            } catch (_: Exception) {
            }
        }
        processes.clear()
    }
}
