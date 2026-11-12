package wherevercode.agent.brain

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import wherevercode.agent.core.agent.AgentLoginState

/**
 * Adapter-id prefix for device-code membership brains (2026-11-12,
 * Qwen / Kimi / Grok membership): adapter ids are
 * `"devicecode-<vendorId>"`, e.g. `"devicecode-qwen"`. The validator
 * and the bootstrap both key off this prefix; the vendor table lives
 * in [wherevercode.agent.brain.devicecode.DeviceCodeConfigs].
 */
const val DEVICE_CODE_ADAPTER_ID_PREFIX: String = "devicecode-"

/**
 * `extra` key marking a login-only brain (2026-11-12, Gemini):
 * `extra["login_only"] == "true"` means the brain exists only as a
 * login entry — signing in never makes it usable for inference.
 * The validator exempts such a config from the model / cliBinary
 * requirements; the inference layer ([wherevercode.agent.brain.
 * devicecode.LoginOnlyBrainAdapter]) refuses chat calls honestly.
 */
const val LOGIN_ONLY_EXTRA_KEY: String = "login_only"

/**
 * BrainConfig: how the user configures a brain (stage 2, audit section 4.8).
 *
 * Key rules:
 * - The secret lives ONLY on the phone, in Keystore-backed
 *   EncryptedSharedPreferences ([BrainKeyStore]). The config holds only
 *   [BrainConfig.authRef] (a reference name such as "key:openai-1"), never
 *   the value. [exportConfig] exports the reference name only.
 * - baseUrl comes ONLY from user configuration. It must never come from
 *   task content or chat messages ([BaseUrlSource] is checked by the
 *   validator).
 * - There is NO default brain: [BrainConfigStore.currentBrainId] is null
 *   until the user configures one, and the UI must show "未配置大脑".
 *   Nothing in code pre-fills any vendor as the default.
 */
data class BrainConfig(
    val brainId: String,
    val kind: BrainKind,
    val adapterId: String,
    val label: String,
    val baseUrl: String?,
    val model: String,
    val authRef: String?,
    val extra: Map<String, String> = emptyMap(),
    val fallbackBrainIds: List<String> = emptyList(),
    /**
     * Login state, last verification time / summary, and CLI binary
     * (C3, first stage). All default to null: old stored JSON without
     * these keys reads back with nulls, no migration. The login state
     * reuses [AgentLoginState] (no second enum); the binary name is not
     * a secret and may be stored in plain text; credentials are still
     * only ever referenced by [authRef], never stored here.
     */
    val loginState: AgentLoginState? = null,
    val lastTestedAtEpochSeconds: Long? = null,
    val lastTestSummary: String? = null,
    val cliBinary: String? = null,
)

enum class BaseUrlSource {
    USER_CONFIG,
    TASK_CONTENT,
    CHAT_MESSAGE,
}

object BrainConfigValidator {
    private val SECRET_LIKE_VALUE = Regex("(?i)(sk-[A-Za-z0-9_-]{12,}|Bearer\\s+\\S+|-----BEGIN [A-Z0-9 ]*PRIVATE KEY-----)")
    private val SECRET_LIKE_KEY = Regex("(?i)(API[_-]?KEY|ACCESS[_-]?KEY|SECRET|TOKEN|PASSWORD|PASSWD|PRIVATE[_-]?KEY)")

    fun looksLikeSecretValue(value: String): Boolean = SECRET_LIKE_VALUE.containsMatchIn(value)

    /** Validate a config. Returns human-actionable errors; empty = valid. */
    fun validate(config: BrainConfig, baseUrlSource: BaseUrlSource = BaseUrlSource.USER_CONFIG): List<String> {
        val errors = mutableListOf<String>()
        if (config.brainId.isBlank()) errors += "大脑 id 不能为空"
        if (config.label.isBlank()) errors += "大脑名字不能为空"
        if (config.adapterId.isBlank()) errors += "适配器 id 不能为空"
        if (config.brainId in config.fallbackBrainIds) errors += "fallback 列表不能包含自己"
        // M3 (SIWC): the ChatGPT brain via Sign in with ChatGPT is a
        // SUBSCRIPTION brain whose authorization is an OAuth token set, not
        // a borrowed CLI login. Two narrow exceptions, only for this
        // adapterId — the general SUBSCRIPTION rules below still apply to
        // every other subscription brain:
        // - authRef is allowed: it points at the SIWC token store entry in
        //   BrainKeyStore ("siwc:tokens"), never a secret value itself.
        // - model may be blank: blank means "not chosen yet", NOT a
        //   provider default. The adapter resolves it at runtime from the
        //   account's own model directory (GET /v1/models, visibility ==
        //   "list", prefer gpt-6.1-sol) and persists the resolved model via
        //   saveConfig; blank is NEVER silently replaced by a hardcoded
        //   model (the old code did exactly that with "gpt-5", and the
        //   server rejected it with 400 model_not_found — M3 blocker fix,
        //   2026-11-08). The adapter never omits the model field: the
        //   Responses client requires a non-blank model.
        val isSiwc = config.adapterId == "siwc-chatgpt"
        // 设备码会员大脑（2026-11-12，Qwen / Kimi / Grok 会员）：
        // 与 SIWC 同款例外——会员 token 不是“借 CLI 的额度”，而是
        // 用户自己在厂商处授权的 OAuth token，存在本机 Keystore 的
        // devicecode token store 里（authRef "devicecode:<厂商>"），
        // 推理走厂商的 OpenAI 兼容端点（baseUrl 即推理地址）。
        // 因此它可以有 authRef、可以无 cliBinary、模型可空（“未填”，
        // 用户在设置里填厂商模型目录里的名字）；baseUrl 若填了必须
        // 是合法 https 地址。
        val isDeviceCode = config.adapterId.startsWith(DEVICE_CODE_ADAPTER_ID_PREFIX)
        // 仅登录大脑（2026-11-12，Gemini）：登录成功也不能当大脑用
        // 推理（会员 token 调不了 Gemini API），所以它没有模型、没有
        // cliBinary、没有 authRef——校验只放行“空壳登录项”，推理层
        // 另由 LoginOnlyBrainAdapter 诚实拒绝。
        val isLoginOnly = config.extra[LOGIN_ONLY_EXTRA_KEY] == "true"
        if (config.model.isBlank() && !isSiwc && !isDeviceCode && !isLoginOnly) errors += "模型不能为空"
        // baseUrl: only from user configuration, https only (local-model exception).
        if (config.kind == BrainKind.MODEL_API) {
            val url = config.baseUrl
            if (url.isNullOrBlank()) {
                errors += "模型 API 需要填写服务地址 baseUrl"
            } else {
                if (baseUrlSource != BaseUrlSource.USER_CONFIG) {
                    errors += "服务地址只能来自用户配置，不能来自任务内容或聊天消息"
                }
                if (!isAllowedBaseUrl(url)) {
                    errors += "服务地址只允许 https（本地模型允许 http://127.0.0.1 或 http://localhost）：$url"
                }
            }
        }
        if (config.kind == BrainKind.SUBSCRIPTION && !config.baseUrl.isNullOrBlank() && !isDeviceCode) {
            errors += "订阅授权没有服务地址：请去掉 baseUrl，改选已登录的 CLI"
        }
        if (isDeviceCode && !config.baseUrl.isNullOrBlank() && !isAllowedBaseUrl(config.baseUrl)) {
            errors += "设备码会员大脑的推理地址只允许 https：${config.baseUrl}"
        }
        if (config.kind == BrainKind.SUBSCRIPTION && config.authRef != null && !isSiwc && !isDeviceCode) {
            errors += "订阅授权没有密钥输入框：请去掉 authRef，改用已登录 CLI 的会员额度"
        }
        // C3: a SUBSCRIPTION brain that is not SIWC borrows a logged-in
        // CLI's membership quota, so it must name the CLI binary; SIWC
        // is the only SUBSCRIPTION brain allowed an authRef instead.
        // An AGENT_RUNTIME brain likewise must name its binary.
        if (config.kind == BrainKind.SUBSCRIPTION && !isSiwc && !isDeviceCode && !isLoginOnly && config.cliBinary.isNullOrBlank()) {
            errors += "订阅授权（非 ChatGPT 登录）需要已登录的 CLI：请填写 cliBinary"
        }
        if (config.kind == BrainKind.AGENT_RUNTIME && config.cliBinary.isNullOrBlank()) {
            errors += "本机 CLI 大脑需要填写 CLI 二进制名 cliBinary"
        }
        // authRef is a reference name, never a secret value.
        val ref = config.authRef
        if (ref != null) {
            if (ref.isBlank()) errors += "密钥引用名不能为空字符串"
            if (looksLikeSecretValue(ref)) errors += "authRef 只能是引用名，不能是密钥本身"
        }
        // extra must never carry a secret value.
        for ((key, value) in config.extra) {
            if (SECRET_LIKE_KEY.containsMatchIn(key) || looksLikeSecretValue(value)) {
                errors += "extra 里不能放密钥（字段 $key）"
            }
        }
        return errors
    }

    /** 本机模型地址判定：host 必须是 127.0.0.1 或 localhost（可带端口/路径），与 isAllowedBaseUrl 的本地例外同规则；AgentRecord.isLocalModelUrl 委托到这里，只留一份。 */
    fun isLocalModelUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        return lower == "http://127.0.0.1" || lower == "http://localhost" ||
            lower.startsWith("http://127.0.0.1/") || lower.startsWith("http://127.0.0.1:") ||
            lower.startsWith("http://localhost/") || lower.startsWith("http://localhost:")
    }

    fun isAllowedBaseUrl(url: String): Boolean {
        val lower = url.trim().lowercase()
        if (lower.startsWith("https://")) return true
        // Local-model exception: the host must be exactly 127.0.0.1 or
        // localhost (optionally with a port / path), never a longer host
        // that merely starts with those strings.
        for (host in listOf("http://127.0.0.1", "http://localhost")) {
            if (lower == host) return true
            if (lower.startsWith("$host/") || lower.startsWith("$host:") ||
                lower.startsWith("$host?") || lower.startsWith("$host#")
            ) {
                return true
            }
        }
        return false
    }

    /** Export a config for backup/sharing: reference names only, never secret values. */
    fun exportConfig(config: BrainConfig): Map<String, Any?> {
        val safeExtra = config.extra.filterKeys { !SECRET_LIKE_KEY.containsMatchIn(it) }
        return mapOf(
            "brainId" to config.brainId,
            "kind" to config.kind.name,
            "adapterId" to config.adapterId,
            "label" to config.label,
            "baseUrl" to config.baseUrl,
            "model" to config.model,
            "authRef" to config.authRef,
            "extra" to safeExtra,
            "fallbackBrainIds" to config.fallbackBrainIds,
            // Login state / verification summary / CLI binary are not
            // secrets, so they are exportable like the other fields.
            "loginState" to config.loginState?.name,
            "lastTestedAtEpochSeconds" to config.lastTestedAtEpochSeconds,
            "lastTestSummary" to config.lastTestSummary,
            "cliBinary" to config.cliBinary,
        )
    }
}

/**
 * Keystore-backed secret storage. Secrets go here and nowhere else:
 * never in BrainConfig, never in logs, never in task results, never in
 * exported configs.
 */
class BrainKeyStore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "wherevercode_brain_keys",
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    fun putSecret(authRef: String, value: String) {
        require(authRef.isNotBlank()) { "authRef 不能为空" }
        require(!BrainConfigValidator.looksLikeSecretValue(authRef)) { "authRef 只能是引用名" }
        prefs.edit().putString(authRef, value).apply()
    }

    fun getSecret(authRef: String): String? = prefs.getString(authRef, null)

    fun hasSecret(authRef: String): Boolean = prefs.contains(authRef)

    fun deleteSecret(authRef: String) {
        prefs.edit().remove(authRef).apply()
    }
}

interface BrainConfigStore {
    fun listConfigs(): List<BrainConfig>
    fun getConfig(brainId: String): BrainConfig?
    fun saveConfig(config: BrainConfig)
    fun deleteConfig(brainId: String)
    /** Null = no brain configured yet (UI shows "未配置大脑"). No default brain. */
    fun currentBrainId(): String?
    fun setCurrentBrainId(brainId: String?)

    /**
     * Deleted-brain tombstones (2026-11-12, brain login methods
     * completion + membership add-all): a first-line brain the user
     * deleted must NOT come back on the next app start — including
     * after an app update (an update replaces code only; the
     * SharedPreferences file holding the tombstones survives it, see
     * [SharedPreferencesBrainConfigStore.KEY_DELETED]).
     * [wherevercode.agent.brain.siwc.BrainBootstrap] reseeds first-line
     * brains only when their id is not in this set — that covers the
     * four membership brains added 2026-11-12 (Qwen / Kimi / Grok
     * membership / Gemini) exactly like the older first-line brains.
     * Default implementations are no-ops so in-memory/test stores keep
     * compiling unchanged; the production
     * [SharedPreferencesBrainConfigStore] persists the set.
     */
    fun deletedBrainIds(): Set<String> = emptySet()
    fun markBrainDeleted(brainId: String) {}
    fun clearBrainDeleted(brainId: String) {}
}

class SharedPreferencesBrainConfigStore(context: Context) : BrainConfigStore {
    private val prefs = context.getSharedPreferences("wherevercode_brain_configs", Context.MODE_PRIVATE)

    override fun listConfigs(): List<BrainConfig> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        val out = mutableListOf<BrainConfig>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                parseConfig(array.optJSONObject(i))?.let { out += it }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    override fun getConfig(brainId: String): BrainConfig? =
        listConfigs().firstOrNull { it.brainId == brainId }

    override fun saveConfig(config: BrainConfig) {
        val configs = listConfigs().filter { it.brainId != config.brainId } + config
        persist(configs)
    }

    override fun deleteConfig(brainId: String) {
        persist(listConfigs().filter { it.brainId != brainId })
        if (currentBrainId() == brainId) setCurrentBrainId(null)
    }

    override fun currentBrainId(): String? = prefs.getString(KEY_CURRENT, null)

    override fun setCurrentBrainId(brainId: String?) {
        prefs.edit().apply {
            if (brainId == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, brainId)
        }.apply()
    }

    override fun deletedBrainIds(): Set<String> {
        val json = prefs.getString(KEY_DELETED, null) ?: return emptySet()
        return try {
            val array = org.json.JSONArray(json)
            val out = mutableSetOf<String>()
            for (i in 0 until array.length()) {
                array.optString(i)?.takeIf { it.isNotBlank() }?.let { out += it }
            }
            out
        } catch (_: Exception) {
            emptySet()
        }
    }

    override fun markBrainDeleted(brainId: String) {
        val ids = deletedBrainIds().toMutableSet()
        if (ids.add(brainId)) persistDeleted(ids)
    }

    override fun clearBrainDeleted(brainId: String) {
        val ids = deletedBrainIds().toMutableSet()
        if (ids.remove(brainId)) persistDeleted(ids)
    }

    private fun persistDeleted(ids: Set<String>) {
        val array = org.json.JSONArray()
        for (id in ids.sorted()) array.put(id)
        prefs.edit().putString(KEY_DELETED, array.toString()).apply()
    }

    private fun persist(configs: List<BrainConfig>) {
        val array = org.json.JSONArray()
        for (config in configs) {
            val obj = JSONObject()
            obj.put("brainId", config.brainId)
            obj.put("kind", config.kind.name)
            obj.put("adapterId", config.adapterId)
            obj.put("label", config.label)
            obj.put("baseUrl", config.baseUrl)
            obj.put("model", config.model)
            obj.put("authRef", config.authRef)
            val extra = JSONObject()
            config.extra.forEach { (k, v) -> extra.put(k, v) }
            obj.put("extra", extra)
            val fallback = org.json.JSONArray()
            config.fallbackBrainIds.forEach { fallback.put(it) }
            obj.put("fallbackBrainIds", fallback)
            obj.put("loginState", config.loginState?.name)
            obj.put("lastTestedAtEpochSeconds", config.lastTestedAtEpochSeconds)
            obj.put("lastTestSummary", config.lastTestSummary)
            obj.put("cliBinary", config.cliBinary)
            array.put(obj)
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }

    private fun parseConfig(obj: JSONObject?): BrainConfig? {
        if (obj == null) return null
        return try {
            val extra = mutableMapOf<String, String>()
            val extraObj = obj.optJSONObject("extra")
            if (extraObj != null) {
                val keys = extraObj.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    extra[key] = extraObj.optString(key, "")
                }
            }
            val fallback = mutableListOf<String>()
            val fallbackArray = obj.optJSONArray("fallbackBrainIds")
            if (fallbackArray != null) {
                for (i in 0 until fallbackArray.length()) fallback += fallbackArray.optString(i, "")
            }
            BrainConfig(
                brainId = obj.optString("brainId", ""),
                kind = BrainKind.valueOf(obj.optString("kind", "MODEL_API")),
                adapterId = obj.optString("adapterId", ""),
                label = obj.optString("label", ""),
                baseUrl = if (obj.isNull("baseUrl")) null else obj.optString("baseUrl", ""),
                model = obj.optString("model", ""),
                authRef = if (obj.isNull("authRef")) null else obj.optString("authRef", ""),
                extra = extra,
                fallbackBrainIds = fallback.filter { it.isNotEmpty() },
                // New in C3; absent in old stored JSON -> null (zero
                // migration). An unknown loginState name is treated as
                // absent rather than crashing the whole config list.
                loginState = obj.optString("loginState", "").takeIf { it.isNotBlank() }?.let {
                    runCatching { AgentLoginState.valueOf(it) }.getOrNull()
                },
                lastTestedAtEpochSeconds =
                    if (obj.isNull("lastTestedAtEpochSeconds")) null
                    else obj.optLong("lastTestedAtEpochSeconds"),
                lastTestSummary =
                    if (obj.isNull("lastTestSummary")) null else obj.optString("lastTestSummary", ""),
                cliBinary =
                    if (obj.isNull("cliBinary")) null else obj.optString("cliBinary", ""),
            )
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val KEY_CONFIGS: String = "configs_json"
        const val KEY_CURRENT: String = "current_brain_id"
        /**
         * Deleted-brain tombstones, JSON array of brain ids. Stored in
         * the same `wherevercode_brain_configs` SharedPreferences file
         * as the configs themselves — an app update replaces the APK
         * only and leaves this file untouched, so a tombstone written
         * before an update is still read after it and the deleted
         * brain is not reseeded (see BrainBootstrap.seededConfig).
         */
        const val KEY_DELETED: String = "deleted_brain_ids_json"
    }
}
