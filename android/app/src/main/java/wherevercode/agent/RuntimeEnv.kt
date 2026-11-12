package wherevercode.agent

import android.content.Context
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The in-app runtime: Python 3 (Chaquopy, in-process), Node (provisioned binary),
 * and shell commands.
 *
 * Execution rules (see android/README.md for the verified Android 10 W^X rule):
 * - targetSdk 28 keeps execve() from the app home directory working, which is
 *   what npm-installed CLIs need. Cost: sideload only, no Google Play.
 * - Binaries shipped with the APK live in jniLibs and are executed from
 *   nativeLibraryDir (allowed for targetSdk >= 29 too).
 * - Last resort for a data-dir ELF on a raised targetSdk: run it through the
 *   system linker (/system/bin/linker64 <elf>), the Termux-Play approach.
 *   Statically linked binaries do not work that way.
 */
class RuntimeEnv(private val context: Context) {

    /** The agent workspace: inside the sandbox, plain file IO, no permission needed. */
    val workspaceDir: File get() = File(context.filesDir, "workspace").apply { mkdirs() }

    /** Node binary shipped as a jniLibs .so (see README: built from termux-packages
     *  or node-on-android's libnode.so). NOT committed in this spike: a node binary
     *  is tens of MB and must be provisioned by the build. */
    fun nodeBinary(): File? {
        val libDir = context.applicationInfo.nativeLibraryDir ?: return null
        val node = File(libDir, "libnode.so")
        return if (node.isFile && node.canExecute()) node else null
    }

    /** npm is JS run by node: <filesDir>/node/npm/bin/npm-cli.js once provisioned. */
    private fun npmCli(): File? {
        val cli = File(context.filesDir, "node/npm/bin/npm-cli.js")
        return if (cli.isFile) cli else null
    }

    fun codeBuddyBinary(): File? {
        val override = System.getenv("CODEBUDDY_BIN")?.trim().orEmpty()
        if (override.isNotEmpty()) {
            val f = File(override)
            if (f.isFile && f.canExecute()) return f
        }
        val inBin = File(context.filesDir, "node/bin/codebuddy")
        if (inBin.isFile && inBin.canExecute()) return inBin
        return null
    }

    private fun baseEnv(): Map<String, String> {
        val home = context.filesDir.absolutePath
        // Android's linker ignores DT_RPATH, so children only find the
        // provisioned sibling libs (libnode.so's libcrypto_3.so etc.) via
        // LD_LIBRARY_PATH. Verified on K70 2026-11-08: without it, running
        // libnode.so fails with 'library "libz_1.so" not found'. Prepend the
        // app's native lib dir and keep any inherited entries.
        val libDir = context.applicationInfo.nativeLibraryDir.orEmpty()
        val ldPath = listOf(libDir, System.getenv("LD_LIBRARY_PATH").orEmpty())
            .filter { it.isNotEmpty() }.joinToString(":")
        return mapOf(
            "HOME" to home,
            "TMPDIR" to context.cacheDir.absolutePath,
            "LD_LIBRARY_PATH" to ldPath,
            "PATH" to listOf(
                File(context.filesDir, "node/bin").absolutePath,
                File(context.filesDir, "bin").absolutePath,
                System.getenv("PATH") ?: "/system/bin:/system/xbin",
            ).joinToString(":"),
        )
    }

    private fun runProcess(
        argv: List<String>,
        workDir: File = workspaceDir,
        timeoutSeconds: Long = 120,
    ): Pair<Int, String> {
        val proc = ProcessBuilder(argv)
            .directory(workDir)
            .redirectErrorStream(true)
            .apply { environment().putAll(baseEnv()) }
            .start()
        val finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            proc.destroyForcibly()
            return -1 to "超时（>${timeoutSeconds}s），已杀掉"
        }
        val out = proc.inputStream.bufferedReader().readText().trim()
        return proc.exitValue() to out
    }

    /** Run a shell command the way the spike needs it: /system/bin/sh -c. */
    fun runShell(command: String, timeoutSeconds: Long = 60): Pair<Int, String> =
        runProcess(listOf("/system/bin/sh", "-c", command), timeoutSeconds = timeoutSeconds)

    /**
     * Fallback for an ELF in the writable home directory when the targetSdk is
     * ever raised past 28: exec the system linker with the ELF as its argument.
     * The kernel only sees /system/bin/linker64 being executed.
     */
    fun runDataDirElf(elf: File, args: List<String>): Pair<Int, String> {
        val linker = File("/system/bin/linker64").takeIf { it.canExecute() }
            ?: File("/system/bin/linker")
        return runProcess(listOf(linker.absolutePath, elf.absolutePath) + args)
    }

    /** Python probe via Chaquopy: real CPython in-process, no exec needed. */
    fun pythonProbe(): String = try {
        if (!Python.isStarted()) Python.start(AndroidPlatform(context))
        val module = Python.getInstance().getModule("spike_probe")
        val result = module.callAttr("probe", workspaceDir.absolutePath)
        "Python 探针：\n$result"
    } catch (e: Exception) {
        "Python 探针失败：${e.message}\n（Chaquopy 未启动或 spike_probe.py 有问题）"
    }

    fun shellProbe(): String {
        val (code, out) = runShell("echo hello-from-shell; uname -m")
        return "Shell 探针：exit=$code\n$out"
    }

    fun fileProbe(): String {
        return try {
            val file = File(workspaceDir, "spike_probe.txt")
            file.writeText("wherevercode-android-spike")
            val back = file.readText()
            if (back == "wherevercode-android-spike") {
                "文件探针：写读回一致（${file.absolutePath}）"
            } else {
                "文件探针失败：读回内容不一致：$back"
            }
        } catch (e: Exception) {
            "文件探针失败：${e.message}"
        }
    }

    /** One-tap check: run the provisioned node binary with --version. */
    fun nodeProbe(): String {
        val node = nodeBinary()
            ?: return "Node 探针：App 内没有 node（nativeLibraryDir/libnode.so 缺失）。先按 README 把 node 二进制备好，再测。"
        val (code, out) = runProcess(listOf(node.absolutePath, "--version"), timeoutSeconds = 60)
        return if (code == 0) {
            "Node 探针：node $out（${node.absolutePath}）"
        } else {
            "Node 探针失败：exit=$code\n$out\n（node 在但跑不起来，见日志。）"
        }
    }

    /** Install CodeBuddy with the provisioned node + npm. Requires node first. */
    fun installCodeBuddy(): String {
        val node = nodeBinary() ?: return "装不了：App 内没有 node（nativeLibraryDir/libnode.so 缺失）。" +
            "先按 README 把 node 二进制备好（termux-packages 编译或 node-on-android 的 libnode.so），再点安装。"
        val npm = npmCli() ?: return "装不了：npm 未备好（filesDir/node/npm/bin/npm-cli.js 缺失）。" +
            "node 已有，但 npm 文件还没随包进来，见 README 的 Node 一节。"
        val prefix = File(context.filesDir, "node").absolutePath
        val (code, out) = runProcess(
            listOf(
                node.absolutePath, npm.absolutePath, "install", "-g",
                "@tencent-ai/codebuddy-code", "--prefix", prefix,
            ),
            timeoutSeconds = 600,
        )
        return "npm install -g @tencent-ai/codebuddy-code：exit=$code\n$out"
    }
}
