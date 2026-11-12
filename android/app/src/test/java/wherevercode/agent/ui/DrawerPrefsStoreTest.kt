package wherevercode.agent.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import wherevercode.agent.ui.DrawerController.DrawerPrefs

/**
 * Drawer prefs codec test (V5 Phase B C2, batch 2, T1).
 *
 * Pure JVM JUnit 4, no Robolectric: the codec is plain Kotlin +
 * org.json (already used by pure-JVM tests in this tree, e.g.
 * BrainEngineTest), with no Android and no Room in the path.
 */
class DrawerPrefsStoreTest {

    private fun customPrefs(): DrawerPrefs = DrawerPrefs(
        order = listOf(
            DrawerController.ID_SETTINGS,
            DrawerController.ID_TASKS,
            DrawerController.ID_NEW_CONVERSATION,
            DrawerController.ID_HISTORY_SEARCH,
            DrawerController.ID_PROJECTS,
            DrawerController.ID_AGENTS_TEAM,
            DrawerController.ID_DEVICES,
            DrawerController.ID_TOOLS_CONNECTIONS,
            DrawerController.ID_RESULTS,
            DrawerController.ID_HELP,
        ),
        hidden = setOf(DrawerController.ID_RESULTS, DrawerController.ID_HELP),
        favorites = setOf(DrawerController.ID_TASKS),
        nestedUnder = mapOf(DrawerController.ID_DEVICES to DrawerController.ID_AGENTS_TEAM),
        moreExpanded = true,
    )

    @Test
    fun `codec round-trips every field`() {
        val prefs = customPrefs()
        val decoded = DrawerPrefsCodec.decode(DrawerPrefsCodec.encode(prefs))
        assertEquals(prefs, decoded)
    }

    @Test
    fun `default prefs round-trip unchanged`() {
        val prefs = DrawerPrefs()
        assertEquals(prefs, DrawerPrefsCodec.decode(DrawerPrefsCodec.encode(prefs)))
    }

    @Test
    fun `encoded row always uses the fixed single-row id`() {
        assertEquals("default", DrawerPrefsCodec.encode(customPrefs()).id)
        assertEquals("default", DrawerPrefsEntity.DEFAULT_ID)
    }

    @Test
    fun `missing row decodes to defaults`() {
        assertEquals(DrawerPrefs(), DrawerPrefsCodec.decode(null))
    }

    @Test
    fun `unknown ids are discarded on decode`() {
        val prefs = DrawerPrefs(
            order = listOf("nope", DrawerController.ID_TASKS, DrawerController.ID_SETTINGS),
            hidden = setOf("ghost", DrawerController.ID_HELP),
            favorites = setOf("phantom", DrawerController.ID_TASKS),
            nestedUnder = mapOf(
                "ghost-child" to DrawerController.ID_TASKS,
                DrawerController.ID_DEVICES to "ghost-parent",
                DrawerController.ID_DEVICES to DrawerController.ID_AGENTS_TEAM,
            ),
            moreExpanded = true,
        )
        val decoded = DrawerPrefsCodec.decode(DrawerPrefsCodec.encode(prefs))
        assertEquals(
            listOf(DrawerController.ID_TASKS, DrawerController.ID_SETTINGS),
            decoded.order,
        )
        assertEquals(setOf(DrawerController.ID_HELP), decoded.hidden)
        assertEquals(setOf(DrawerController.ID_TASKS), decoded.favorites)
        assertEquals(
            mapOf(DrawerController.ID_DEVICES to DrawerController.ID_AGENTS_TEAM),
            decoded.nestedUnder,
        )
        assertTrue(decoded.moreExpanded)
    }

    @Test
    fun `corrupt JSON cells decode to empty collections without throwing`() {
        val entity = DrawerPrefsEntity(
            orderJson = "not json[",
            hiddenJson = "{broken",
            favoritesJson = "",
            nestedJson = "[1,2",
            moreExpanded = true,
        )
        val decoded = DrawerPrefsCodec.decode(entity)
        assertTrue(decoded.order.isEmpty())
        assertTrue(decoded.hidden.isEmpty())
        assertTrue(decoded.favorites.isEmpty())
        assertTrue(decoded.nestedUnder.isEmpty())
        // The boolean column is not JSON; a corrupt JSON cell does not
        // lose it.
        assertTrue(decoded.moreExpanded)
    }

    @Test
    fun `self-nesting is discarded on decode`() {
        val prefs = DrawerPrefs(
            nestedUnder = mapOf(DrawerController.ID_TASKS to DrawerController.ID_TASKS),
        )
        assertTrue(DrawerPrefsCodec.decode(DrawerPrefsCodec.encode(prefs)).nestedUnder.isEmpty())
    }

    @Test
    fun `moreExpanded survives a round-trip both ways`() {
        assertFalse(DrawerPrefsCodec.decode(DrawerPrefsCodec.encode(DrawerPrefs(moreExpanded = false))).moreExpanded)
        assertTrue(DrawerPrefsCodec.decode(DrawerPrefsCodec.encode(DrawerPrefs(moreExpanded = true))).moreExpanded)
    }
}
