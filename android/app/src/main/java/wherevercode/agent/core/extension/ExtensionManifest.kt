package wherevercode.agent.core.extension

import org.json.JSONArray
import org.json.JSONObject

/** Versioned, inert metadata. Declared permissions never grant runtime authority. */
data class ExtensionManifest(
    val id: String,
    val kind: String,
    val name: String,
    val version: String,
    val permissions: List<String>,
) {
    fun toJson(): JSONObject = JSONObject().put("schemaVersion", 1).put("id", id)
        .put("kind", kind).put("name", name).put("version", version)
        .put("permissions", JSONArray(permissions))

    companion object {
        val KINDS = setOf("agent", "model", "harness", "mcp", "skills", "automation", "network", "device", "ui_extension")
        val PERMISSIONS = setOf("workspace.read", "workspace.write", "process.execute", "network.connect",
            "device.pair", "task.create", "task.control", "ui.render")
        private val FIELDS = setOf("schemaVersion", "id", "kind", "name", "version", "permissions")

        fun parse(text: String): ExtensionManifest {
            require(text.length <= 32768) { "清单超过 32 KiB 字符限制" }
            val json = JSONObject(text)
            require(json.keys().asSequence().all { it in FIELDS }) { "未知字段：配置和凭据不能存入元数据清单" }
            require(json.opt("schemaVersion") is Int && json.getInt("schemaVersion") == 1) { "仅支持 schemaVersion=1" }
            fun string(key: String): String {
                require(json.opt(key) is String) { "$key 必须是字符串" }
                return json.getString(key)
            }
            val id = string("id")
            require(id.matches(Regex("[a-z][a-z0-9._-]{0,63}"))) { "id 格式无效" }
            val kind = string("kind")
            require(kind in KINDS) { "未知扩展类型" }
            val name = string("name").trim()
            require(name.isNotBlank() && name.length <= 80 && name.none { it.isISOControl() }) { "name 格式无效" }
            val version = string("version")
            require(version.matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) { "version 必须是三段版本号" }
            val array = json.getJSONArray("permissions")
            require(array.length() <= PERMISSIONS.size) { "权限过多" }
            val permissions = (0 until array.length()).map {
                require(array.get(it) is String) { "权限必须是字符串" }
                array.getString(it)
            }
            require(permissions.distinct().size == permissions.size && permissions.all { it in PERMISSIONS }) { "未知或重复权限" }
            return ExtensionManifest(id, kind, name, version, permissions)
        }
    }
}
