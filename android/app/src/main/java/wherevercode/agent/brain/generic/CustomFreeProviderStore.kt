package wherevercode.agent.brain.generic

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 自定义免费服务商（预留扩展口，Funk 2026-11-18 23:43 令）：
 * 名单（[FreeProviderPresets]）上没有的免费服务商，用户可以自己
 * 加一家——名称 + 服务地址 + 名单形态（拉取 /models 或填固定
 * 名单），存本机后与内置预设走同一条添加流程（填 key → 拉阵列
 * 或固定名单 → 勾选添加，tier=free 标记同口径）。
 *
 * 扩展口纪律：
 * - 自定义数据只存本机 SharedPreferences，绝不并入内置名单
 *   （内置名单是逐家官方核验过的，自定义是用户自己的判断，
 *   两者呈现上也分开标注）。
 * - 以后新增内置服务商 = 往 [FreeProviderPresets.PRESETS] 加一条
 *   数据，不动流程代码；自定义口是同一扩展点的用户侧版本。
 */
object CustomFreeProviders {

    const val ID_PREFIX: String = "custom-"

    fun isCustom(preset: FreeProviderPreset): Boolean = preset.id.startsWith(ID_PREFIX)

    /** 内置在前、自定义在后合并（两份数据本身不合并存储）。 */
    fun merged(builtins: List<FreeProviderPreset>, customs: List<FreeProviderPreset>): List<FreeProviderPreset> =
        builtins + customs

    fun customId(displayName: String, existingIds: Set<String>): String {
        val slug = displayName.trim().lowercase()
            .map { if (it in 'a'..'z' || it in '0'..'9' || it == '-') it else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-{2,}"), "-")
            .take(32)
            .trim('-')
            .ifBlank { "provider" }
        var candidate = ID_PREFIX + slug
        var n = 2
        while (candidate in existingIds) {
            candidate = ID_PREFIX + slug + "-" + n
            n++
        }
        return candidate
    }

    fun toJson(preset: FreeProviderPreset): JSONObject {
        val obj = JSONObject()
        obj.put("id", preset.id)
        obj.put("displayName", preset.displayName)
        obj.put("listMode", preset.listMode.name)
        val models = JSONArray()
        preset.fixedModels.forEach { models.put(it) }
        obj.put("fixedModels", models)
        obj.put("baseUrlTemplate", preset.baseUrlTemplate)
        obj.put("keyHelp", preset.keyHelp)
        obj.put("note", preset.note)
        return obj
    }

    fun fromJson(obj: JSONObject): FreeProviderPreset? {
        val id = obj.optString("id", "")
        val displayName = obj.optString("displayName", "")
        val baseUrl = obj.optString("baseUrlTemplate", "")
        if (id.isBlank() || displayName.isBlank() || baseUrl.isBlank()) return null
        val models = mutableListOf<String>()
        val array = obj.optJSONArray("fixedModels")
        if (array != null) {
            for (i in 0 until array.length()) {
                array.optString(i).takeIf { it.isNotBlank() }?.let { models += it }
            }
        }
        val mode = try {
            FreeModelListMode.valueOf(obj.optString("listMode", "LIVE"))
        } catch (_: Exception) {
            FreeModelListMode.LIVE
        }
        if (mode == FreeModelListMode.FIXED && models.isEmpty()) return null
        return FreeProviderPreset(
            id = id,
            displayName = displayName,
            fixedModels = models,
            listMode = mode,
            baseUrlTemplate = baseUrl,
            keyHelp = obj.optString("keyHelp", ""),
            note = obj.optString("note", ""),
        )
    }
}

interface CustomFreeProviderStore {
    fun list(): List<FreeProviderPreset>
    fun add(preset: FreeProviderPreset)
    fun remove(id: String)
}

class SharedPreferencesCustomFreeProviderStore(context: Context) : CustomFreeProviderStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun list(): List<FreeProviderPreset> {
        val out = mutableListOf<FreeProviderPreset>()
        try {
            val array = JSONArray(prefs.getString(KEY_JSON, "[]"))
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { CustomFreeProviders.fromJson(it) }?.let { out += it }
            }
        } catch (_: Exception) {
            return emptyList()
        }
        return out
    }

    override fun add(preset: FreeProviderPreset) {
        val all = list().filter { it.id != preset.id } + preset
        persist(all)
    }

    override fun remove(id: String) {
        persist(list().filter { it.id != id })
    }

    private fun persist(all: List<FreeProviderPreset>) {
        val array = JSONArray()
        all.forEach { array.put(CustomFreeProviders.toJson(it)) }
        prefs.edit().putString(KEY_JSON, array.toString()).apply()
    }

    companion object {
        const val FILE: String = "wherevercode_custom_free_providers"
        const val KEY_JSON: String = "providers_json"
    }
}
