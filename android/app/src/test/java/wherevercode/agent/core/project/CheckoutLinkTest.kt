package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 粘 git 仓库地址后链已有检出（2026-11-11 主流做法）：
 * 只认与仓库同名的文件夹，不猜、不拉代码；是不是该仓库的检出，
 * 调用方再核（本机看 .git，远程设备由用户在确认单上认）。
 */
class CheckoutLinkTest {

    @Test
    fun `same name folder is a checkout candidate`() {
        assertEquals(
            listOf("/home/user/code/shop"),
            checkoutCandidates("shop", listOf("/home/user/code/shop", "/home/user/code/other")),
        )
    }

    @Test
    fun `nested same name folder is a candidate`() {
        assertEquals(
            listOf("a/b/shop"),
            checkoutCandidates("shop", listOf("a/b/shop", "a/b/shop-web")),
        )
    }

    @Test
    fun `different name is not a candidate`() {
        assertEquals(
            emptyList<String>(),
            checkoutCandidates("shop", listOf("/home/user/code/other")),
        )
    }

    @Test
    fun `name match is exact not prefix`() {
        assertEquals(
            emptyList<String>(),
            checkoutCandidates("shop", listOf("/home/user/code/shop2", "/home/user/code/myshop")),
        )
    }

    @Test
    fun `empty folder list has no candidates`() {
        assertEquals(emptyList<String>(), checkoutCandidates("shop", emptyList()))
    }
}
