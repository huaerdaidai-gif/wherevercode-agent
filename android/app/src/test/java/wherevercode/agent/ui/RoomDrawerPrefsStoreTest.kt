package wherevercode.agent.ui

import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import wherevercode.agent.core.task.TaskDatabase
import wherevercode.agent.ui.DrawerController.DrawerPrefs

/**
 * Room drawer-prefs test (V5 Phase B C2, batch 2, T2).
 *
 * Robolectric — the DAOs need an Android Context (same reason as
 * C1's ConversationRoomStoreTest: this cannot run on a plain JVM).
 * Run in CI (`./gradlew testDebugUnitTest`).
 *
 * Covers: save -> load round-trip through the shared TaskDatabase,
 * overwrite, missing row -> defaults, corrupt row -> empty collections
 * (no crash), and the MIGRATION_3_4 SQL itself. The full v3 -> v4
 * file upgrade (old rows survive) is covered by
 * TaskDatabaseMigration3To4Test in core.task.
 */
@RunWith(RobolectricTestRunner::class)
class RoomDrawerPrefsStoreTest {
    private lateinit var db: TaskDatabase
    private lateinit var store: RoomDrawerPrefsStore

    @Before
    fun setUp() {
        db = TaskDatabase.inMemory(RuntimeEnvironment.getApplication())
        store = RoomDrawerPrefsStore(db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun customPrefs(): DrawerPrefs = DrawerPrefs(
        order = listOf(
            DrawerController.ID_SETTINGS,
            DrawerController.ID_TASKS,
            DrawerController.ID_NEW_CONVERSATION,
        ),
        hidden = setOf(DrawerController.ID_RESULTS),
        favorites = setOf(DrawerController.ID_TASKS),
        nestedUnder = mapOf(DrawerController.ID_DEVICES to DrawerController.ID_AGENTS_TEAM),
        moreExpanded = true,
    )

    @Test
    fun `empty database loads defaults`() {
        assertEquals(DrawerPrefs(), store.load())
    }

    @Test
    fun `save then load round-trips every field`() {
        val prefs = customPrefs()
        store.save(prefs)
        assertEquals(prefs, store.load())
    }

    @Test
    fun `a second save overwrites the first`() {
        store.save(customPrefs())
        val simpler = DrawerPrefs(moreExpanded = false)
        store.save(simpler)
        assertEquals(simpler, store.load())
        assertEquals(1, db.drawerPrefsDao().countAll())
    }

    @Test
    fun `clear returns to defaults`() {
        store.save(customPrefs())
        store.clear()
        assertEquals(DrawerPrefs(), store.load())
    }

    @Test
    fun `corrupt row loads as empty collections without throwing`() {
        db.drawerPrefsDao().upsert(
            DrawerPrefsEntity(
                orderJson = "not json[",
                hiddenJson = "{broken",
                favoritesJson = "",
                nestedJson = "[1,2",
                moreExpanded = true,
            ),
        )
        val loaded = store.load()
        assertTrue(loaded.order.isEmpty())
        assertTrue(loaded.hidden.isEmpty())
        assertTrue(loaded.favorites.isEmpty())
        assertTrue(loaded.nestedUnder.isEmpty())
        assertTrue(loaded.moreExpanded)
    }

    @Test
    fun `MIGRATION_3_4 creates the drawer_prefs table`() {
        val context = RuntimeEnvironment.getApplication()
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(null)
            .callback(object : SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: SupportSQLiteDatabase) = Unit
                override fun onUpgrade(
                    db: SupportSQLiteDatabase,
                    oldVersion: Int,
                    newVersion: Int,
                ) = Unit
            })
            .build()
        val helper = FrameworkSQLiteOpenHelperFactory().create(config)
        val sqlDb = helper.writableDatabase
        try {
            TaskDatabase.MIGRATION_3_4.migrate(sqlDb)
            val cursor = sqlDb.query(
                "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'drawer_prefs'",
            )
            try {
                assertTrue(cursor.moveToFirst())
                assertEquals("drawer_prefs", cursor.getString(0))
            } finally {
                cursor.close()
            }
            // Second run is a no-op (CREATE TABLE IF NOT EXISTS).
            TaskDatabase.MIGRATION_3_4.migrate(sqlDb)
        } finally {
            sqlDb.close()
            helper.close()
        }
    }
}
