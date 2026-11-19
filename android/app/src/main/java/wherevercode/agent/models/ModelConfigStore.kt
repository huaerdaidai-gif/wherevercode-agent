package wherevercode.agent.models

import android.content.Context
import org.json.JSONObject
import wherevercode.agent.brain.BrainKind
import wherevercode.agent.core.agent.AgentLoginState

/**
 * 差异 9（B3）：独立模型存储（SharedPreferences 实现）。
 *
 * prefs 文件 `wherevercode_model_configs`，键名与 iOS 严格对称：
 * `model.configs_json` / `model.current_model_id` /
 * `model.deleted_builtin_ids_json` / `model.migration_v1_done`。
 *
 * 与大脑存储（wherevercode_brain_configs）互不读写：本类只碰自己
 * 的 prefs 文件。实现无状态（每次读都走 prefs 本体），所以装配层
 * 与 MainActivity 各持一个实例也始终一致（getSharedPreferences
 * 同名文件在进程内是同一份）。序列化字段与大脑存储同构（id 键
 * 写 modelId，读时兼容 brainId），条目与 [ModelEntry] 一一对应。
 */
class ModelConfigStore(context: Context) {
    private val prefs =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun listEntries(): List<ModelEntry> {
        val json = prefs.getString(KEY_CONFIGS, null) ?: return emptyList()
        val out = mutableListOf<ModelEntry>()
        try {
            val array = org.json.JSONArray(json)
            for (i in 0 until array.length()) {
                parseEntry(array.optJSONObject(i))?.let { out += it }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    fun getEntry(modelId: String): ModelEntry? =
        listEntries().firstOrNull { it.modelId == modelId }

    fun saveEntry(entry: ModelEntry) {
        val entries = listEntries().filter { it.modelId != entry.modelId } + entry
        persist(entries)
    }

    /** 删条目；删的正是当前模型时当前项一并清空（与大脑存储同行为）。 */
    fun deleteEntry(modelId: String) {
        persist(listEntries().filter { it.modelId != modelId })
        if (currentModelId() == modelId) setCurrentModelId(null)
    }

    fun currentModelId(): String? = prefs.getString(KEY_CURRENT, null)

    fun setCurrentModelId(modelId: String?) {
        prefs.edit().apply {
            if (modelId == null) remove(KEY_CURRENT) else putString(KEY_CURRENT, modelId)
        }.apply()
    }

    /**
     * 已删内置模型的墓碑：用户在模型页删掉的内置条目不能被每次
     * 启动的幂等补齐从大脑存储里再复制回来（见 [ModelStoreMigration]）。
     */
    fun deletedBuiltinIds(): Set<String> {
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

    fun markBuiltinDeleted(modelId: String) {
        val ids = deletedBuiltinIds().toMutableSet()
        if (ids.add(modelId)) persistDeleted(ids)
    }

    fun clearBuiltinDeleted(modelId: String) {
        val ids = deletedBuiltinIds().toMutableSet()
        if (ids.remove(modelId)) persistDeleted(ids)
    }

    /** 一次性状态迁移完成标记（[ModelStoreMigration] 专用，数据落盘后最后写）。 */
    fun isMigrationV1Done(): Boolean = prefs.getBoolean(KEY_MIGRATION_DONE, false)

    fun markMigrationV1Done() {
        prefs.edit().putBoolean(KEY_MIGRATION_DONE, true).apply()
    }

    private fun persistDeleted(ids: Set<String>) {
        val array = org.json.JSONArray()
        for (id in ids.sorted()) array.put(id)
        prefs.edit().putString(KEY_DELETED, array.toString()).apply()
    }

    private fun persist(entries: List<ModelEntry>) {
        val array = org.json.JSONArray()
        for (entry in entries) {
            val obj = JSONObject()
            obj.put("modelId", entry.modelId)
            obj.put("kind", entry.kind.name)
            obj.put("adapterId", entry.adapterId)
            obj.put("label", entry.label)
            obj.put("baseUrl", entry.baseUrl)
            obj.put("model", entry.model)
            obj.put("authRef", entry.authRef)
            val extra = JSONObject()
            entry.extra.forEach { (k, v) -> extra.put(k, v) }
            obj.put("extra", extra)
            val fallback = org.json.JSONArray()
            entry.fallbackModelIds.forEach { fallback.put(it) }
            obj.put("fallbackModelIds", fallback)
            obj.put("loginState", entry.loginState?.name)
            obj.put("lastTestedAtEpochSeconds", entry.lastTestedAtEpochSeconds)
            obj.put("lastTestSummary", entry.lastTestSummary)
            obj.put("cliBinary", entry.cliBinary)
            array.put(obj)
        }
        prefs.edit().putString(KEY_CONFIGS, array.toString()).apply()
    }

    private fun parseEntry(obj: JSONObject?): ModelEntry? {
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
            val fallbackArray = obj.optJSONArray("fallbackModelIds")
                ?: obj.optJSONArray("fallbackBrainIds")
            if (fallbackArray != null) {
                for (i in 0 until fallbackArray.length()) fallback += fallbackArray.optString(i, "")
            }
            ModelEntry(
                modelId = obj.optString("modelId", "").ifBlank { obj.optString("brainId", "") },
                kind = BrainKind.valueOf(obj.optString("kind", "MODEL_API")),
                adapterId = obj.optString("adapterId", ""),
                label = obj.optString("label", ""),
                baseUrl = if (obj.isNull("baseUrl")) null else obj.optString("baseUrl", ""),
                model = obj.optString("model", ""),
                authRef = if (obj.isNull("authRef")) null else obj.optString("authRef", ""),
                extra = extra,
                fallbackModelIds = fallback.filter { it.isNotEmpty() },
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
        const val PREFS_NAME: String = "wherevercode_model_configs"
        const val KEY_CONFIGS: String = "model.configs_json"
        const val KEY_CURRENT: String = "model.current_model_id"
        const val KEY_DELETED: String = "model.deleted_builtin_ids_json"
        const val KEY_MIGRATION_DONE: String = "model.migration_v1_done"
    }
}
