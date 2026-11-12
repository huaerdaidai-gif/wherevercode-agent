package wherevercode.agent.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class WorkspaceBrowserTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `lists directories first then files by name`() {
        val root = temp.newFolder("workspace")
        File(root, "z.txt").writeText("z")
        File(root, "a.txt").writeText("a")
        File(root, "src").mkdirs()
        val browser = WorkspaceBrowser(root)

        val listing = browser.list()

        assertNull(listing.error)
        assertEquals(listOf("src", "a.txt", "z.txt"), listing.entries.map { it.name })
        assertTrue(listing.entries.first().isDirectory)
    }

    @Test
    fun `reads utf8 text and reports truncation`() {
        val root = temp.newFolder("workspace")
        File(root, "hello.txt").writeText("hello")
        val browser = WorkspaceBrowser(root, maxReadBytes = 3)

        val read = browser.read("hello.txt")

        assertEquals(WorkspaceReadKind.TEXT, read.kind)
        assertEquals("hel", read.text)
        assertTrue(read.truncated)
        assertEquals(5L, read.sizeBytes)
    }

    @Test
    fun `rejects paths outside the workspace`() {
        val root = temp.newFolder("workspace")
        val browser = WorkspaceBrowser(root)

        assertNull(browser.resolve("../secret.txt"))
        assertNull(browser.resolve("/etc/passwd"))
        val read = browser.read("../secret.txt")
        assertEquals(WorkspaceReadKind.OUTSIDE, read.kind)
    }

    @Test
    fun `detects binary files instead of dumping them into a text view`() {
        val root = temp.newFolder("workspace")
        File(root, "blob.bin").writeBytes(byteArrayOf(0x41, 0x00, 0x42))
        val browser = WorkspaceBrowser(root)

        val read = browser.read("blob.bin")

        assertEquals(WorkspaceReadKind.BINARY, read.kind)
        assertNotNull(read.error)
        assertEquals("", read.text)
    }

    @Test
    fun `missing directories and files are reported honestly`() {
        val root = temp.newFolder("workspace")
        val browser = WorkspaceBrowser(root)

        assertNotNull(browser.list("nope").error)
        assertEquals(WorkspaceReadKind.MISSING, browser.read("nope.txt").kind)
        assertTrue(browser.exists())
    }

    @Test
    fun `search matches file names case-insensitively across subdirectories`() {
        val root = temp.newFolder("workspace")
        File(root, "README.md").writeText("r")
        File(root, "calc.py").writeText("c")
        val sub = File(root, "src").apply { mkdirs() }
        File(sub, "Calculator.kt").writeText("k")
        val browser = WorkspaceBrowser(root)

        val found = browser.search("CALC")

        assertEquals(listOf("calc.py", "src/Calculator.kt"), found.map { it.relativePath })
        assertTrue(browser.search("").isEmpty())
        assertTrue(browser.search("zzz").isEmpty())
    }

    @Test
    fun `read tiers truncate at 512KB first then 2MB`() {
        val root = temp.newFolder("workspace")
        val big = "x".repeat(700 * 1024)
        File(root, "big.txt").writeText(big)
        val browser = WorkspaceBrowser(root)

        val first = browser.read("big.txt", 512 * 1024)
        assertTrue(first.truncated)
        assertEquals(512 * 1024, first.text.length)

        val more = browser.read("big.txt", 2 * 1024 * 1024)
        assertTrue(!more.truncated)
        assertEquals(big.length, more.text.length)
    }

    @Test
    fun `list with a limit reports truncation for load-more`() {
        val root = temp.newFolder("workspace")
        repeat(5) { File(root, "f$it.txt").writeText("x") }
        val browser = WorkspaceBrowser(root)

        val page = browser.list("", 2)

        assertEquals(2, page.entries.size)
        assertTrue(page.truncated)
        assertTrue(!browser.list("", 10).truncated)
    }
}
