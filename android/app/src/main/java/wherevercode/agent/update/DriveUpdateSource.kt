package wherevercode.agent.update

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新流程的错误。所有消息都是直接给用户看的中文，不含任何 token 或
 * 密钥值；调用方原样显示即可，不用再加工。
 */
sealed class AppUpdateError(message: String) : Exception(message) {
    /** 未配置 Google OAuth 客户端 ID（maintainer 的一次性控制台步骤还没做）。 */
    class NotConfigured(message: String) : AppUpdateError(message)

    /** 未登录 Google Drive。 */
    class NotSignedIn(message: String = "未连接 Google Drive。") : AppUpdateError(message)

    /** 授权失效（token 被撤回 / refresh 失败）：需要重新登录。 */
    class AuthFailed(message: String) : AppUpdateError(message)

    /** 网络或服务端暂不可用（可重试）。 */
    class Transport(message: String) : AppUpdateError(message)

    /** Drive 上的分发文件找不到了（被删 / 被移走 / 账号不对）。 */
    class NotFound(message: String) : AppUpdateError(message)

    /** 下载的文件校验和与 Drive 元数据不符：绝不安装，已删掉下载文件。 */
    class ChecksumMismatch(message: String) : AppUpdateError(message)
}

/** 一次 HTTP 响应：状态码 + 文本正文。 */
data class DriveHttpResponse(val status: Int, val body: String)

/**
 * Drive API 的 HTTP 端口（测试可替换 fake；生产走
 * [UrlConnectionDriveHttpPort]）。下载是二进制流，不经过文本层。
 */
interface DriveHttpPort {
    /** GET JSON（元数据）。`accessToken` 只放 Authorization 头，绝不放 URL 里。 */
    fun getJson(url: String, accessToken: String): DriveHttpResponse

    /**
     * GET 二进制并流式写进 [out]。返回总字节数；[onProgress] 在每写一块
     * 后回调（已写字节，Content-Length，未知时为 -1）。
     */
    fun download(
        url: String,
        accessToken: String,
        out: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Long
}

/**
 * [DriveHttpPort] 的 [HttpURLConnection] 实现。不引入新依赖。
 * 只接受 https；`instanceFollowRedirects = false`（Drive API 的
 * `alt=media` 不走重定向；万一返回 3xx，按服务端错误处理，不跟随）。
 */
class UrlConnectionDriveHttpPort(
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : DriveHttpPort {

    override fun getJson(url: String, accessToken: String): DriveHttpResponse {
        requireHttps(url)
        val connection = open(url, accessToken)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            return readTextResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    override fun download(
        url: String,
        accessToken: String,
        out: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Long {
        requireHttps(url)
        val connection = open(url, accessToken)
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "*/*")
            val status = connection.responseCode
            if (status !in 200..299) {
                // 读掉错误体再抛，让调用方按状态码分类（401 / 404 / 其他）。
                val errorBody = readErrorBody(connection)
                throw DriveHttpStatusException(status, errorBody)
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            val input: InputStream = connection.inputStream
            val buffer = ByteArray(64 * 1024)
            var read: Int
            while (input.read(buffer).also { read = it } != -1) {
                out.write(buffer, 0, read)
                downloaded += read
                onProgress(downloaded, total)
            }
            out.flush()
            return downloaded
        } finally {
            connection.disconnect()
        }
    }

    private fun open(url: String, accessToken: String): HttpURLConnection {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.instanceFollowRedirects = false
        connection.setRequestProperty("Authorization", "Bearer $accessToken")
        connection.setRequestProperty(
            "User-Agent",
            "WhereverCode-Android/0.1.0-spike (app-update)",
        )
        return connection
    }

    private fun readTextResponse(connection: HttpURLConnection): DriveHttpResponse {
        val status = connection.responseCode
        val stream = if (status < HttpURLConnection.HTTP_BAD_REQUEST) {
            connection.inputStream
        } else {
            connection.errorStream
        }
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        return DriveHttpResponse(status, body)
    }

    private fun readErrorBody(connection: HttpURLConnection): String {
        return try {
            connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun requireHttps(url: String) {
        require(url.startsWith("https://")) { "Drive API 只允许 https：$url" }
    }
}

/** 下载时收到的非 2xx 状态（调用方按状态码分类；不含 token）。 */
class DriveHttpStatusException(val status: Int, val body: String) :
    IOException("Drive 下载失败（HTTP $status）")

/**
 * Google Drive API v3 的更新源：只访问本构建配置的文件 ID。
 *
 * - 元数据：`GET /drive/v3/files/{id}?fields=id,name,size,modifiedTime,md5Checksum,sha256Checksum`
 * - 下载：`GET /drive/v3/files/{id}?alt=media`
 *
 * 端点与字段以 Drive API v3 参考为准（files.get / files.get?alt=media；
 * `sha256Checksum` 是 File 资源的标准输出字段）。不分页、不列目录、
 * 不写文件——只读这一个文件。
 */
class DriveUpdateSource(
    private val http: DriveHttpPort,
    private val fileId: String = DRIVE_APK_FILE_ID,
) {
    /** 读分发文件的元数据。401 → [AppUpdateError.AuthFailed]，404 → [AppUpdateError.NotFound]。 */
    fun fetchMetadata(accessToken: String): DriveFileInfo {
        if (fileId.isBlank()) {
            throw AppUpdateError.NotConfigured("此构建尚未配置更新源。")
        }
        val url = "$DRIVE_API_BASE_URL/files/$fileId" +
            "?fields=id,name,size,modifiedTime,md5Checksum,sha256Checksum"
        val response = try {
            http.getJson(url, accessToken)
        } catch (e: IOException) {
            throw AppUpdateError.Transport("连接 Google Drive 失败（网络错误），可稍后重试。")
        }
        when (response.status) {
            200 -> Unit
            401 -> throw AppUpdateError.AuthFailed("Google Drive 授权已失效，请重新连接 Google Drive。")
            403 -> throw AppUpdateError.AuthFailed(
                "Google Drive 拒绝访问（HTTP 403）：可能是 Drive API 未在该 OAuth 客户端的项目里启用，" +
                    "或账号无权读这个文件。",
            )
            404 -> throw AppUpdateError.NotFound("Drive 上找不到更新包文件（可能被删或被移走）。")
            429, in 500..599 -> throw AppUpdateError.Transport(
                "Google Drive 暂不可用（HTTP ${response.status}），可稍后重试。",
            )
            else -> throw AppUpdateError.Transport(
                "读取 Drive 文件信息失败（HTTP ${response.status}）。",
            )
        }
        return try {
            parseMetadata(response.body)
        } catch (_: Exception) {
            throw AppUpdateError.Transport("Google Drive 返回了无法解析的文件信息。")
        }
    }

    /** 下载分发文件到 [out]。返回字节数；401/404 按元数据同样分类。 */
    fun downloadApk(
        accessToken: String,
        out: OutputStream,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): Long {
        if (fileId.isBlank()) {
            throw AppUpdateError.NotConfigured("此构建尚未配置更新源。")
        }
        val url = "$DRIVE_API_BASE_URL/files/$fileId?alt=media"
        return try {
            http.download(url, accessToken, out, onProgress)
        } catch (e: DriveHttpStatusException) {
            when (e.status) {
                401 -> throw AppUpdateError.AuthFailed("Google Drive 授权已失效，请重新连接 Google Drive。")
                403 -> throw AppUpdateError.AuthFailed(
                    "Google Drive 拒绝下载（HTTP 403）：可能是 Drive API 未在该 OAuth 客户端的项目里启用。",
                )
                404 -> throw AppUpdateError.NotFound("Drive 上找不到更新包文件（可能被删或被移走）。")
                429, in 500..599 -> throw AppUpdateError.Transport(
                    "Google Drive 暂不可用（HTTP ${e.status}），可稍后重试。",
                )
                else -> throw AppUpdateError.Transport("下载更新包失败（HTTP ${e.status}）。")
            }
        } catch (e: IOException) {
            throw AppUpdateError.Transport("下载更新包时网络中断，可稍后重试。")
        }
    }

    companion object {
        /** 解析 `files.get` 的 JSON。缺 `size` 时按 0 计（比大小时走 CannotTell 分支）。 */
        fun parseMetadata(body: String): DriveFileInfo {
            val json = JSONObject(body)
            return DriveFileInfo(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                sizeBytes = json.optString("size", "0").toLongOrNull() ?: 0L,
                modifiedTime = json.optString("modifiedTime", ""),
                md5Checksum = json.optString("md5Checksum", "").ifBlank { null },
                sha256Checksum = json.optString("sha256Checksum", "").ifBlank { null },
            )
        }
    }
}
