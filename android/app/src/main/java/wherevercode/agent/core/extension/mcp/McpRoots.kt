package wherevercode.agent.core.extension.mcp

/**
 * MCP Roots（MCP 2025-11-25）：客户端向服务端声明“我能共享哪些
 * 文件根目录”，服务端向客户端发 `roots/list` 请求来取。
 *
 * 协议（主流做法）：
 * - 客户端在 initialize 的 capabilities 里声明
 *   `{"roots": {"listChanged": true}}`（没声明时服务端不会问）。
 * - 服务端发有 id 的 `roots/list` 请求，客户端回
 *   `{"roots": [{"uri": "file:///…", "name": "…"}]}`。
 * - 根目录变了时客户端发 `notifications/roots/list_changed` 通知
 *   （本版只在 provider 内容变化时由调用方发，见 McpClient）。
 *
 * Roots 是客户端的声明，不是服务端的授权：服务端拿到 uri 也不
 * 等于能读 App 的文件——读文件仍要走 App 的工具/授权。
 * 默认不声明（provider 为空时 McpClient 不在 capabilities 里
 * 写 roots），服务端不问，行为与原来一致。
 */
data class McpRoot(
    /** 根目录 URI（`file://` 绝对路径；目录，不是单个文件）。 */
    val uri: String,
    /** 人类可读名（可空；没有时服务端用 uri 显示）。 */
    val name: String = "",
)

/** Roots 提供方：调用方决定暴露哪些根目录（可随时换）。 */
interface McpRootsProvider {
    fun roots(): List<McpRoot>
}

/** 固定一组根目录（App 侧：工作区根目录；测试：任意假目录）。 */
class StaticMcpRootsProvider(private val roots: List<McpRoot> = emptyList()) : McpRootsProvider {
    override fun roots(): List<McpRoot> = roots.toList()
}
