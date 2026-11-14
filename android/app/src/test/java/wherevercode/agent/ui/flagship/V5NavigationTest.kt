package wherevercode.agent.ui.flagship

import java.io.File
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import wherevercode.agent.ui.EntryRegistry

class V5NavigationTest {
    @Test fun uploadedContractRetainsEveryRouteTitleAndParent() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "docs/v5/baseline/contracts/routes_wiring.json").exists() }
        val contract = JSONArray(File(root, "docs/v5/baseline/contracts/routes_wiring.json").readText())
        assertEquals(70, contract.length())
        assertEquals(70, FlagshipRoutes.all.map { it.id }.distinct().size)
        assertEquals(FlagshipRoutes.all.map { it.id }.toSet(), V5Navigation.parentByRoute.keys)
        for (i in 0 until contract.length()) {
            val expected = contract.getJSONObject(i)
            val id = expected.getString("id")
            assertEquals(expected.getString("title"), FlagshipRoutes.find(id).title)
            assertEquals(expected.getString("parent"), V5Navigation.parentByRoute[id])
        }
        assertEquals(listOf("对话", "工作台", "控制台", "设置"), V5Navigation.parents)
    }
    @Test fun everyBindingResolvesWithoutUsingTodoOrUnregisteredEntries() {
        V5Bindings.all.forEach { (id, binding) ->
            assertTrue(id in V5Navigation.parentByRoute)
            assertTrue(binding.description.isNotBlank())
            if (binding.entry !in setOf("current_conversation", "create_task")) {
                val entry = requireNotNull(EntryRegistry.find(binding.entry)) { id }
                assertEquals(id, EntryRegistry.Status.IMPLEMENTED, entry.status)
            }
        }
    }
    @Test fun conversationDoesNotUseNewConversationAction() {
        assertEquals("current_conversation", V5Bindings.all.getValue("conversation").entry)
        assertNotEquals(V5Bindings.all.getValue("chat").entry, V5Bindings.all.getValue("conversation").entry)
        assertEquals("create_task", V5Bindings.all.getValue("compose").entry)
    }
    @Test fun searchMatchesIdentifiersAndDoesNotLeakOtherParents() {
        assertEquals(listOf("devicedetail"), V5Navigation.search("工作台", "DEVICEDETAIL").map { it.id })
        assertTrue(V5Navigation.search("对话", "devicedetail").isEmpty())
        assertTrue(V5Navigation.search("设置", "__missing__").isEmpty())
    }
    @Test(expected = IllegalStateException::class) fun invalidRouteNeverSilentlyOpensChat() {
        FlagshipRoutes.find("nonexistent")
    }
}
