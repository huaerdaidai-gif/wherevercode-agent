package wherevercode.agent.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * “+”菜单附件：相机拍照 / 相册选图 / 选文件（V5 按主流改，maintainer 2026-11-11）。
 *
 * 主流做法对照（ChatGPT / Claude 的 composer 附件）：
 * - 图片进对话后 inline 显示缩略图，大脑能看懂内容（走大脑的多模态能力）；
 * - 文件进对话后大脑能读内容（文本文件），其他类型只显示文件名和大小；
 * - 附件先落到 App 私有目录再发送：SAF 的 content URI 权限可能随重启丢失，
 *   私有拷贝才是对话能一直打开的底。
 *
 * 诚实边界：
 * - 附件只在 App 私有目录（filesDir/chat-attachments）存一份，不写其他地方；
 * - 图片下到大脑之前先缩到 1024px、JPEG 80——原图 base64 又大又贵，
 *   按 maintainer 的性价比原则：该省的省，该花的花；
 * - 文本文件内容只读前 32K 字符，超了截断并明说；
 * - 二进制文件（apk/zip/视频等）不读内容，只显示文件名和大小，绝不假装读过。
 */
enum class ChatAttachmentKind {
    IMAGE,
    FILE,
}

data class ChatAttachment(
    val id: String,
    val kind: ChatAttachmentKind,
    val displayName: String,
    val mimeType: String?,
    val sizeBytes: Long,
    /** App 私有拷贝：filesDir/chat-attachments/<id>/<displayName>。 */
    val localFile: File,
    /** 文本文件：读到的内容（已按上限截断）；图片/二进制文件为 null。 */
    val textContent: String? = null,
    /** 图片：下采样后的 base64 data URL（data:image/jpeg;base64,…）；非图片为 null。 */
    val imageDataUrl: String? = null,
    /** FileProvider content URI（点卡片用系统查看器打开）；无则 null。 */
    val contentUri: String? = null,
)

object ChatAttachments {

    /** 文本文件读内容上限（字符数）：超了截断并在正文里明说。 */
    const val MAX_TEXT_CHARS: Int = 32768

    /** 图片下采样上限（最长边 px）；JPEG 质量。 */
    const val MAX_IMAGE_DIMENSION: Int = 1024
    const val IMAGE_JPEG_QUALITY: Int = 80

    /** 单个附件上限：Grok 的图片上限是 20 MiB，文件也按这个收。 */
    const val MAX_ATTACHMENT_BYTES: Long = 20L * 1024L * 1024L

    /** 附件私有目录名（filesDir 下）。 */
    const val ATTACHMENTS_DIR: String = "chat-attachments"

    private val TEXT_EXTENSIONS: Set<String> = setOf(
        "txt", "md", "markdown", "json", "kt", "kts", "java", "py", "js", "ts",
        "tsx", "jsx", "css", "html", "htm", "xml", "yaml", "yml", "csv", "log",
        "sh", "gradle", "properties", "toml", "ini", "cfg", "conf", "c", "h",
        "cpp", "cc", "go", "rs", "rb", "swift", "sql", "diff", "patch",
    )

    private val IMAGE_EXTENSIONS: Set<String> = setOf(
        "jpg", "jpeg", "png", "webp", "gif",
    )

    fun extensionOf(name: String): String =
        name.substringAfterLast('.', "").lowercase()

    fun isImage(name: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.startsWith("image/")) return true
        return extensionOf(name) in IMAGE_EXTENSIONS
    }

    fun isText(name: String, mimeType: String?): Boolean {
        if (mimeType != null && mimeType.startsWith("text/")) return true
        if (mimeType == "application/json") return true
        return extensionOf(name) in TEXT_EXTENSIONS
    }

    /** 人类可读的大小：12 KB / 1.2 MB。 */
    fun formatSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        if (bytes < 1024 * 1024) return "%.1f KB".format(bytes / 1024.0)
        return "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }

    /** 对话气泡里显示的附件描述行。 */
    fun describeForDisplay(attachment: ChatAttachment): String =
        when (attachment.kind) {
            ChatAttachmentKind.IMAGE ->
                "[图片：${attachment.displayName}]"
            ChatAttachmentKind.FILE ->
                "[文件：${attachment.displayName}，${formatSize(attachment.sizeBytes)}]"
        }

    /**
     * 时间线用户气泡的正文：用户原文 + 每件附件一行描述。
     * 图片/文件内容本身不在气泡里（图片走缩略图卡片，文件内容走模型正文）。
     */
    fun buildDisplayText(userText: String, attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return userText
        val lines = buildList {
            if (userText.isNotBlank()) add(userText)
            attachments.forEach { add(describeForDisplay(it)) }
        }
        return lines.joinToString("\n")
    }

    /**
     * 发给模型（或远端 Agent）的正文：用户原文 + 附件。
     * - 文本文件：内容以围栏块 inline，超上限截断并明说；
     * - 图片：只留描述行（图片字节走 vision 通道，不进正文）；
     * - 二进制文件：只留描述行，明确说“内容未读”。
     */
    fun buildModelText(userText: String, attachments: List<ChatAttachment>): String {
        if (attachments.isEmpty()) return userText
        val out = StringBuilder()
        if (userText.isNotBlank()) out.append(userText)
        for (attachment in attachments) {
            out.append("\n\n")
            when {
                attachment.textContent != null -> {
                    out.append("[附件文件：${attachment.displayName}，${formatSize(attachment.sizeBytes)}，内容如下]\n")
                    out.append("```\n")
                    out.append(attachment.textContent)
                    out.append("\n```")
                    if (attachment.textContent.length >= MAX_TEXT_CHARS) {
                        out.append("\n（文件内容过长，已截取前 $MAX_TEXT_CHARS 字符）")
                    }
                }
                attachment.kind == ChatAttachmentKind.IMAGE -> {
                    out.append(describeForDisplay(attachment))
                    out.append("（图片内容见随附的图片数据）")
                }
                else -> {
                    out.append(describeForDisplay(attachment))
                    out.append("（该类型文件不支持读取内容，只给了文件名和大小）")
                }
            }
        }
        return out.toString().trim()
    }

    /**
     * 把 content URI / 相机文件拷贝进 App 私有附件目录，返回私有 File。
     * 超过 [MAX_ATTACHMENT_BYTES] 时返回 null（调用方如实提示，不静默截断）。
     */
    fun copyIntoAttachmentsDir(context: Context, sourceUri: Uri, displayName: String): File? {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "$ATTACHMENTS_DIR/$id").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeIf { it.isNotBlank() } ?: "file"
        val target = File(dir, safeName)
        return try {
            context.contentResolver.openInputStream(sourceUri)?.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_ATTACHMENT_BYTES) return null
                        output.write(buffer, 0, read)
                    }
                }
            } ?: return null
            target
        } catch (_: Exception) {
            null
        }
    }

    /** 相机拍好的文件也挪进附件目录（同一份私有拷贝口径）。 */
    fun moveIntoAttachmentsDir(context: Context, sourceFile: File, displayName: String): File? {
        if (!sourceFile.exists() || sourceFile.length() > MAX_ATTACHMENT_BYTES) return null
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "$ATTACHMENTS_DIR/$id").apply { mkdirs() }
        val safeName = displayName.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeIf { it.isNotBlank() } ?: "photo.jpg"
        val target = File(dir, safeName)
        return try {
            if (sourceFile.renameTo(target)) target else null
        } catch (_: Exception) {
            null
        }
    }

    /** 文本文件读内容（UTF-8，超上限截断；读失败返回 null）。 */
    fun readTextContent(file: File, maxChars: Int = MAX_TEXT_CHARS): String? {
        return try {
            val bytes = file.readBytes()
            val text = String(bytes, Charsets.UTF_8)
            if (text.length > maxChars) text.take(maxChars) else text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 图片下采样成 JPEG data URL（最长边 [MAX_IMAGE_DIMENSION]，质量 80）。
     * 失败返回 null（调用方只发描述行，不编一张假图）。
     * 注意：调用方应在 IO 线程调（解码 + 压缩是重活）。
     */
    fun buildImageDataUrl(file: File): String? {
        // 先闻文件头：只接受已知图片格式（JPEG/PNG/GIF/WebP/BMP/ISO-BMFF）。
        // 真机上 BitmapFactory 对非图片会返回 null，但 Robolectric 的
        // BitmapFactory 影子对任何文件都返回 100x100 的假位图，所以
        // 不靠它分辨“是不是图片”；文件头检查在真机和测试里都成立。
        if (!looksLikeImage(file)) return null
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) return null
            val longest = maxOf(width, height)
            var sampleSize = 1
            while (longest / sampleSize > MAX_IMAGE_DIMENSION) sampleSize *= 2
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, options) ?: return null
            val scaled = if (maxOf(bitmap.width, bitmap.height) > MAX_IMAGE_DIMENSION) {
                val scale = MAX_IMAGE_DIMENSION.toFloat() / maxOf(bitmap.width, bitmap.height)
                Bitmap.createScaledBitmap(
                    bitmap,
                    (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1),
                    true,
                ).also { if (it != bitmap) bitmap.recycle() }
            } else {
                bitmap
            }
            val stream = ByteArrayOutputStream()
            val ok = scaled.compress(Bitmap.CompressFormat.JPEG, IMAGE_JPEG_QUALITY, stream)
            scaled.recycle()
            if (!ok) return null
            val base64 = Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            "data:image/jpeg;base64,$base64"
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 文件头嗅探：读前 12 个字节，只认 JPEG / PNG / GIF / WebP / BMP，
     * 以及 ISO-BMFF 容器（HEIC/AVIF，bytes[4..7] == "ftyp"）。
     * 读不到 / 太短 / 都不匹配 → 不是图片。
     */
    private fun looksLikeImage(file: File): Boolean {
        return try {
            val head = ByteArray(12)
            val read = java.io.FileInputStream(file).use { it.read(head) }
            if (read < 3) return false
            fun ascii(offset: Int, length: Int): String =
                String(head, offset, length, Charsets.US_ASCII)
            val jpeg = head[0] == 0xFF.toByte() && head[1] == 0xD8.toByte() && head[2] == 0xFF.toByte()
            val png = read >= 8 && head[0] == 0x89.toByte() && ascii(1, 3) == "PNG"
            val gif = read >= 6 && ascii(0, 3) == "GIF"
            val webp = read >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 4) == "WEBP"
            val bmp = ascii(0, 2) == "BM"
            val bmff = read >= 8 && ascii(4, 4) == "ftyp"
            jpeg || png || gif || webp || bmp || bmff
        } catch (_: Exception) {
            false
        }
    }

    /** 从 content URI 取显示名（失败回落到调用方给的默认名）。 */
    fun displayNameOf(context: Context, uri: Uri, fallback: String): String {
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && cursor.moveToFirst()) {
                    cursor.getString(nameIndex)?.takeIf { it.isNotBlank() }
                } else {
                    null
                }
            } ?: fallback
        } catch (_: Exception) {
            fallback
        }
    }
}
