package wherevercode.agent.core.dispatch

import android.content.Context
import java.io.File
import wherevercode.agent.core.mcp.RemoteControlSender
import wherevercode.agent.core.mcp.RemoteTarget
import wherevercode.agent.core.control.ControlPlane
import wherevercode.agent.core.task.TaskDatabaseProvider

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
    @Volatile private var sharedControlPlane: ControlPlane? = null
    @Volatile private var remoteTargetResolver: ((String?) -> RemoteTarget?)? = null
    @Volatile private var remoteControlSender: RemoteControlSender? = null

    fun configureRemoteControl(
        targetResolver: ((String?) -> RemoteTarget?)?,
        controlSender: RemoteControlSender?,
    ) {
        synchronized(lock) {
            remoteTargetResolver = targetResolver
            remoteControlSender = controlSender
            host?.service?.configureRemoteControl(targetResolver, controlSender)
        }
    }

    fun bindControlPlane(controlPlane: ControlPlane) {
        synchronized(lock) {
            sharedControlPlane = controlPlane
            host?.service?.useControlPlane(controlPlane)
        }
    }

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

    fun issueAgentToken(
        context: Context,
        principalId: String,
        capability: DispatchCapability,
        projectId: String?,
    ): IssuedDispatchToken {
        val app = context.applicationContext
        return synchronized(lock) {
            check(isEnabled(app)) { "请先启用 MCP 派工服务" }
            require(!principalId.equals("owner", ignoreCase = true)) { "此名称保留给所有者" }
            if (projectId != null) {
                require(wherevercode.agent.core.project.SharedPreferencesProjectStore(app).getProject(projectId) != null) {
                    "项目不存在"
                }
            }
            ensureHost(app).service.tokens.issue(
                principalId, DispatchRole.AGENT, setOf(capability), projectIds = setOfNotNull(projectId),
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
            taskDb = TaskDatabaseProvider.get(app),
            stateDir = stateDir,
            port = PORT,
            bindHost = "127.0.0.1",
            controlPlane = sharedControlPlane,
            remoteTargetResolver = remoteTargetResolver,
            remoteControlSender = remoteControlSender,
        ).start().also { host = it }
    }
}
