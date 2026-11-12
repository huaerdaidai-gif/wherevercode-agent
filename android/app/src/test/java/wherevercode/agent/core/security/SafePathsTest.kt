package wherevercode.agent.core.security

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 2026-11-13 安全加固回归：[SafePaths] 的越权判定。
 * 覆盖：`..`、根外绝对路径、指向根外的悬空链接叶子、指向根外的目录链接
 * （其下目标尚不存在）、根内链接（放行）、命令参数逃逸判定。
 */
class SafePathsTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var root: File
    private lateinit var outside: File

    @Before
    fun setUp() {
        root = temp.newFolder("root")
        outside = temp.newFolder("outside")
    }

    @Test
    fun `plain paths inside the root resolve`() {
        File(root, "a").mkdirs()
        assertNotNull(SafePaths.resolveInside(root, File(root, "a/new.txt")))
        assertNotNull(SafePaths.resolveInside(root, root))
    }

    @Test
    fun `dot-dot segments are refused even when they would fold back inside`() {
        assertNull(SafePaths.resolveInside(root, File(root, "../outside/x.txt")))
        assertNull(SafePaths.resolveInside(root, File(root, "a/../b.txt")))
    }

    @Test
    fun `absolute path outside the root is refused`() {
        assertNull(SafePaths.resolveInside(root, File(outside, "x.txt")))
    }

    @Test
    fun `dangling symlink leaf pointing outside is refused`() {
        val link = File(root, "evil.txt").toPath()
        Files.createSymbolicLink(link, File(outside, "not-yet.txt").toPath())
        assertNull("悬空链接会让写入落到根外", SafePaths.resolveInside(root, link.toFile()))
    }

    @Test
    fun `directory symlink outside with a missing child is refused`() {
        Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
        assertNull(SafePaths.resolveInside(root, File(root, "linkdir/new/deeper.txt")))
    }

    @Test
    fun `symlink that stays inside the root is allowed`() {
        val real = File(root, "real").apply { mkdirs() }
        Files.createSymbolicLink(File(root, "alias").toPath(), real.toPath())
        val resolved = SafePaths.resolveInside(root, File(root, "alias/f.txt"))
        assertNotNull(resolved)
        assertEquals(File(real.canonicalFile, "f.txt").path, resolved!!.path)
    }

    @Test
    fun `nul byte is refused`() {
        assertNull(SafePaths.resolveInside(root, File(root, "a\u0000b")))
    }

    @Test
    fun `argsEscapingRoot flags absolute, home and dot-dot arguments outside the root`() {
        File(root, "src").mkdirs()
        val escaping = SafePaths.argsEscapingRoot(
            listOf("cat", "src/a.txt", File(outside, "secret").path, "~/.ssh/id_rsa", "../x", "--output=/etc/x"),
            root,
        )
        assertEquals(
            listOf(File(outside, "secret").path, "~/.ssh/id_rsa", "../x", "--output=/etc/x"),
            escaping,
        )
        assertTrue(SafePaths.argsEscapingRoot(listOf("cat", File(root, "src").path), root).isEmpty())
    }

    @Test
    fun `hasPathLikeEscapeArgs is conservative`() {
        assertTrue(SafePaths.hasPathLikeEscapeArgs(listOf("cat", "/etc/passwd")))
        assertTrue(SafePaths.hasPathLikeEscapeArgs(listOf("grep", "-r", "x", "../..")))
        assertTrue(!SafePaths.hasPathLikeEscapeArgs(listOf("grep", "-rn", "TODO", "src")))
    }
}
