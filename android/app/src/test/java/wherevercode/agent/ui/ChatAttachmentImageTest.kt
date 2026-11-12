package wherevercode.agent.ui

import android.graphics.Bitmap
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * 图片下采样测试（Robolectric）：2000x1500 的原图 → data URL 必须是
 * JPEG、最长边不超过 1024px。只断言形状与尺寸，不对图片内容做判断。
 */
@RunWith(RobolectricTestRunner::class)
class ChatAttachmentImageTest {

    @Test
    fun `buildImageDataUrl downscales to at most 1024px jpeg`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "chat-attachment-img-test")
        dir.mkdirs()
        val source = File(dir, "big.png")
        val bitmap = Bitmap.createBitmap(2000, 1500, Bitmap.Config.ARGB_8888)
        FileOutputStream(source).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        val dataUrl = ChatAttachments.buildImageDataUrl(source)

        assertNotNull(dataUrl)
        assertTrue(dataUrl!!.startsWith("data:image/jpeg;base64,"))
        val bytes = Base64.decode(
            dataUrl.removePrefix("data:image/jpeg;base64,"),
            Base64.DEFAULT,
        )
        val options = android.graphics.BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        assertTrue("width ${options.outWidth} must be <= 1024", options.outWidth <= 1024)
        assertTrue("height ${options.outHeight} must be <= 1024", options.outHeight <= 1024)
        assertTrue(options.outWidth > 0 && options.outHeight > 0)
    }

    @Test
    fun `buildImageDataUrl returns null for non-image files`() {
        val dir = File(System.getProperty("java.io.tmpdir"), "chat-attachment-img-test")
        dir.mkdirs()
        val source = File(dir, "not-an-image.txt")
        source.writeText("hello")

        assertTrue(ChatAttachments.buildImageDataUrl(source) == null)
    }
}
