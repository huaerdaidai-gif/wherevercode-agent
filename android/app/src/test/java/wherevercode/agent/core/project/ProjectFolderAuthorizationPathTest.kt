package wherevercode.agent.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * R5: the App's project-folder authorization path
 * ([verifyAndRecordProjectFolderAuthorization], used by MainActivity) against
 * the exact receipts both Hand versions send. The fixtures are the literal
 * `json.dumps(..., ensure_ascii=False)` outputs of the source Hand
 * (server/hand_server.py) and the bundled Hand
 * (assets/install/hand_server.py), captured from their authenticated
 * `_dispatch` path with a symlinked request folder (temp root replaced by
 * /srv); the two differ in key order. The legacy fixtures are the shapes
 * those Hands sent before receipts were bound to the request.
 */
@RunWith(RobolectricTestRunner::class)
class ProjectFolderAuthorizationPathTest {

    private lateinit var prefs: android.content.SharedPreferences
    private lateinit var store: KeystoreProjectFolderAuthorizationReceiptStore

    @Before
    fun setUp() {
        prefs = RuntimeEnvironment.getApplication()
            .getSharedPreferences("receipts-${System.nanoTime()}", android.content.Context.MODE_PRIVATE)
        store = KeystoreProjectFolderAuthorizationReceiptStore.forPreferences(prefs)
    }

    private fun authorize(raw: String, agentId: String = AGENT, requested: String = REQUESTED) =
        verifyAndRecordProjectFolderAuthorization(raw, FINGERPRINT, agentId, requested, store, NOW)

    private fun assertRejected(raw: String, agentId: String = AGENT, requested: String = REQUESTED) {
        try {
            authorize(raw, agentId, requested)
            fail("receipt must be rejected: $raw")
        } catch (_: IllegalArgumentException) {
            // Expected.
        }
        assertTrue("nothing may be recorded for a rejected receipt", prefs.all.isEmpty())
    }

    @Test
    fun `success - both Hand versions, first grant and repeat grant`() {
        for ((hand, receipts) in listOf("source" to SOURCE_HAND, "bundled" to BUNDLED_HAND)) {
            for ((raw, already) in listOf(receipts.first to false, receipts.second to true)) {
                val ack = authorize(raw)
                assertEquals("$hand canonical folder", CANONICAL, ack.folder)
                assertEquals("$hand already", already, ack.already)
                val receipt = store.get(FINGERPRINT.uppercase(), AGENT, CANONICAL)
                assertNotNull("$hand receipt stored under the canonical folder", receipt)
                receipt!!
                assertEquals(FINGERPRINT, receipt.peerFingerprintSha256)
                assertEquals(AGENT, receipt.agentId)
                assertEquals(REQUESTED, receipt.requestedFolder)
                assertEquals(already, receipt.alreadyAuthorized)
                assertEquals(NOW, receipt.receivedAtEpochSeconds)
                // Evidence only: a digest of the response, never the raw response.
                assertEquals(64, receipt.responseSha256.length)
                assertFalse(prefs.all.values.joinToString().contains("\"authorized\""))
                // Bound to this agent and this paired Hand.
                assertNull(store.get(FINGERPRINT, "other-agent", CANONICAL))
                assertNull(store.get("cd".repeat(32), AGENT, CANONICAL))
            }
            prefs.edit().clear().commit()
        }
    }

    @Test
    fun `missing identity - legacy receipts without agent_id or requested_folder are rejected`() {
        assertRejected(LEGACY_SOURCE_HAND)       // no agent_id, no requested_folder
        assertRejected(LEGACY_BUNDLED_HAND)      // agent_id but no requested_folder
        for (raw in listOf(SOURCE_HAND.first, BUNDLED_HAND.first)) {
            assertRejected(raw.replace(""""agent_id": "agent", """, "").replace(""", "agent_id": "agent"""", ""))
            assertRejected(raw.replace(""""agent_id": "agent"""", """"agent_id": null"""))
            assertRejected(raw.replace(""""agent_id": "agent"""", """"agent_id": """""))
        }
    }

    @Test
    fun `identity mismatch - receipt for another agent is rejected`() {
        for (raw in listOf(SOURCE_HAND.first, BUNDLED_HAND.second)) {
            assertRejected(raw, agentId = "other-agent")
            assertRejected(raw.replace(""""agent_id": "agent"""", """"agent_id": "other-agent""""))
            // Case or whitespace variants are a different identity, not a match.
            assertRejected(raw.replace(""""agent_id": "agent"""", """"agent_id": "Agent""""))
            assertRejected(raw.replace(""""agent_id": "agent"""", """"agent_id": "agent """"))
        }
    }

    @Test
    fun `folder mismatch - receipt for another requested folder is rejected`() {
        for (raw in listOf(SOURCE_HAND.second, BUNDLED_HAND.first)) {
            assertRejected(raw, requested = "/srv/other-project")
            assertRejected(raw.replace(""""requested_folder": "$REQUESTED"""", """"requested_folder": "/srv/other-project""""))
            // Trailing slash or the canonical target is not the requested folder.
            assertRejected(raw, requested = "$REQUESTED/")
            assertRejected(raw, requested = CANONICAL)
            // Canonical folder must be absolute.
            assertRejected(raw.replace(""""folder": "$CANONICAL"""", """"folder": "real-project""""))
        }
    }

    @Test
    fun `not authorized or malformed receipts are rejected`() {
        for (raw in listOf(SOURCE_HAND.first, BUNDLED_HAND.first)) {
            assertRejected(raw.replace(""""authorized": true""", """"authorized": false"""))
            assertRejected(raw.replace(""""authorized": true""", """"authorized": "true""""))
            assertRejected(raw.replace(""""already": false""", """"already": "false""""))
        }
        assertRejected("authorized")
        assertRejected("")
    }

    private companion object {
        const val AGENT = "agent"
        const val REQUESTED = "/srv/project-link"
        const val CANONICAL = "/srv/real-project"
        const val NOW = 1_700_000_000L
        val FINGERPRINT = "ab".repeat(32)

        /** server/hand_server.py: (first grant, repeat grant). */
        val SOURCE_HAND = Pair(
            """{"authorized": true, "requested_folder": "/srv/project-link", "agent_id": "agent", "already": false, "folder": "/srv/real-project"}""",
            """{"authorized": true, "requested_folder": "/srv/project-link", "agent_id": "agent", "already": true, "folder": "/srv/real-project"}""",
        )

        /** android/app/src/main/assets/install/hand_server.py: (first grant, repeat grant). */
        val BUNDLED_HAND = Pair(
            """{"authorized": true, "agent_id": "agent", "requested_folder": "/srv/project-link", "folder": "/srv/real-project", "already": false}""",
            """{"authorized": true, "agent_id": "agent", "requested_folder": "/srv/project-link", "folder": "/srv/real-project", "already": true}""",
        )

        /** Pre-binding source Hand (before 4d0212c). */
        const val LEGACY_SOURCE_HAND = """{"authorized": true, "already": false, "folder": "/srv/real-project"}"""

        /** Pre-binding bundled Hand (before 4d0212c). */
        const val LEGACY_BUNDLED_HAND = """{"authorized": true, "agent_id": "agent", "folder": "/srv/real-project", "already": false}"""
    }
}
