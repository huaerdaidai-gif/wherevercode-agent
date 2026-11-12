package wherevercode.agent.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ChatAttachment 纯逻辑测试（V5 按主流改，maintainer 2026-11-11）。
 * 不碰 Android Context / Bitmap：只断言分类、大小文案、正文组装。
 * 图片下采样走 [ChatAttachmentImageTest]（Robolectric）。
 */
class ChatAttachmentTest {

    private fun attachment(
        name: String,
        kind: ChatAttachmentKind = ChatAttachmentKind.FILE,
        textContent: String? = null,
        sizeBytes: Long = 100,
    ): ChatAttachment = ChatAttachment(
        id = "a1",
        kind = kind,
        displayName = name,
        mimeType = null,
        sizeBytes = sizeBytes,
        localFile = File("/tmp/$name"),
        textContent = textContent,
    )

    @Test
    fun `isImage by mime and extension`() {
        assertTrue(ChatAttachments.isImage("photo.jpg", null))
        assertTrue(ChatAttachments.isImage("pic.PNG", null))
        assertTrue(ChatAttachments.isImage("noext", "image/webp"))
        assertFalse(ChatAttachments.isImage("doc.pdf", null))
        assertFalse(ChatAttachments.isImage("notes.txt", "text/plain"))
    }

    @Test
    fun `isText by mime and extension`() {
        assertTrue(ChatAttachments.isText("notes.txt", null))
        assertTrue(ChatAttachments.isText("code.kt", null))
        assertTrue(ChatAttachments.isText("data.json", "application/json"))
        assertTrue(ChatAttachments.isText("noext", "text/plain"))
        assertFalse(ChatAttachments.isText("app.apk", null))
        assertFalse(ChatAttachments.isText("photo.jpg", "image/jpeg"))
    }

    @Test
    fun `formatSize human readable`() {
        assertEquals("512 B", ChatAttachments.formatSize(512))
        assertEquals("12.0 KB", ChatAttachments.formatSize(12 * 1024))
        assertEquals("1.5 MB", ChatAttachments.formatSize(1_572_864))
    }

    @Test
    fun `buildDisplayText appends descriptor lines`() {
        val text = ChatAttachments.buildDisplayText(
            "帮我看看",
            listOf(
                attachment("photo.jpg", ChatAttachmentKind.IMAGE, sizeBytes = 2048),
                attachment("notes.txt", textContent = "hello", sizeBytes = 5),
            ),
        )

        assertEquals("帮我看看\n[图片：photo.jpg]\n[文件：notes.txt，5 B]", text)
    }

    @Test
    fun `buildDisplayText without text shows only descriptors`() {
        val text = ChatAttachments.buildDisplayText(
            "",
            listOf(attachment("photo.jpg", ChatAttachmentKind.IMAGE)),
        )

        assertEquals("[图片：photo.jpg]", text)
    }

    @Test
    fun `buildModelText inlines text file content`() {
        val text = ChatAttachments.buildModelText(
            "总结一下",
            listOf(attachment("notes.txt", textContent = "第一行\n第二行")),
        )

        assertTrue(text.startsWith("总结一下"))
        assertTrue(text.contains("[附件文件：notes.txt，100 B，内容如下]"))
        assertTrue(text.contains("第一行\n第二行"))
    }

    @Test
    fun `buildModelText marks binary files as unread`() {
        val text = ChatAttachments.buildModelText(
            "",
            listOf(attachment("app.apk", sizeBytes = 1024)),
        )

        assertTrue(text.contains("[文件：app.apk，1.0 KB]"))
        assertTrue(text.contains("不支持读取内容"))
    }

    @Test
    fun `buildModelText marks images as image data, not text`() {
        val text = ChatAttachments.buildModelText(
            "这张图里有什么",
            listOf(attachment("photo.jpg", ChatAttachmentKind.IMAGE)),
        )

        assertTrue(text.contains("[图片：photo.jpg]"))
        assertTrue(text.contains("图片内容见随附的图片数据"))
    }

    @Test
    fun `buildModelText truncates overlong text files honestly`() {
        val long = "x".repeat(ChatAttachments.MAX_TEXT_CHARS + 100)
        val text = ChatAttachments.buildModelText(
            "",
            listOf(attachment("big.txt", textContent = long)),
        )

        assertTrue(text.contains("已截取前 ${ChatAttachments.MAX_TEXT_CHARS} 字符"))
    }
}
