package wherevercode.agent.core.dispatch

import android.content.Context
import java.io.File
import wherevercode.agent.core.task.TaskDatabase

/**
 * App lifecycle for the MCP dispatch endpoint. State and audit files live in
 * private app storage; DispatchTokenRegistry persists only token hashes.
 */
object McpDispatchRuntime {
    private const val PREFS = "mcp_dispatch_settings"
    private const val KEY_ENABLED = "enabled"
    const val PORT = 18744

    private val lock = Any()
    @Volatile private var host: McpDispatchHost? = null

    fun isEnabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun ensureRunning(context: Context): String? {
        val app = context.applicationContext
        if (!isEnabled(app)) return null
        return synchronized(lock) { ensureHost(app).server.url }
    }

    fun setEnabled(context: Context, enabled: Boolean): String? {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return synchronized(lock) {
            if (!enabled) {
                prefs.edit().putBoolean(KEY_ENABLED, false).apply()
                host?.service?.tokens?.revokeAll()
                host?.stop()
                host = null
                null
            } else {
                val active = ensureHost(app)
                active.service.tokens.revokeAll()
                prefs.edit().putBoolean(KEY_ENABLED, true).apply()
                active.server.url
            }
        }
    }

    fun issueOwnerToken(context: Context): IssuedDispatchToken {
        val app = context.applicationContext
        check(isEnabled(app)) { "请先启用 MCP 派工服务" }
        return synchronized(lock) {
            val active = ensureHost(app)
            active.service.tokens.revokePrincipal("owner")
            active.service.tokens.issue(
                principalId = "owner",
                role = DispatchRole.OWNER,
                capabilities = emptySet(),
                label = "本机所有者",
            )
        }
    }

    /** Called by the existing foreground AgentService after process restart. */
    fun restore(context: Context) {
        val app = context.applicationContext
        if (!isEnabled(app)) return
        Thread {
            runCatching { ensureRunning(app) }
        }.apply {
            name = "mcp-dispatch-restore"
            isDaemon = true
            start()
        }
    }

    /** Stops the socket without changing the user's persisted preference. */
    fun stop() {
        synchronized(lock) {
            host?.stop()
            host = null
        }
    }

    private fun ensureHost(app: Context): McpDispatchHost {
        host?.let { return it }
        val stateDir = File(app.filesDir, "mcp-dispatch")
        check(stateDir.exists() || stateDir.mkdirs()) { "无法创建本机 MCP 状态目录" }
        return McpDispatchHost.create(
            appContext = app,
            taskDb = TaskDatabase.create(app),
            stateDir = stateDir,
            port = PORT,
            bindHost = "127.0.0.1",
        ).start().also { host = it }
    }
}
