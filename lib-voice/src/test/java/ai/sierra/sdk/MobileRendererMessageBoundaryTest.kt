// Copyright Sierra

package ai.sierra.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileRendererMessageBoundaryTest {
    @Test
    fun dispatchesOpenMessageFromAllowedMainFrame() {
        val events = Events()
        val boundary = boundary(events)

        boundary.onPostMessage(
            data = """{"type":"onOpen"}""",
            sourceOrigin = ALLOWED_ORIGIN,
            isMainFrame = true
        )

        assertEquals(1, events.openCount)
    }

    @Test
    fun dispatchesSVPClientEventPayloadFromAllowedMainFrame() {
        val events = Events()
        val boundary = boundary(events)

        boundary.onPostMessage(
            data = """
                {
                  "type": "onSVPClientEvent",
                  "text": "hello",
                  "attachments": [{"type": "custom", "data": {"id": "attachment-1"}}]
                }
            """.trimIndent(),
            sourceOrigin = ALLOWED_ORIGIN,
            isMainFrame = true
        )

        val event = events.clientEvents.single()
        assertEquals("hello", event.text)
        assertEquals("custom", event.attachments.single()["type"])
        assertEquals(
            mapOf("id" to "attachment-1"),
            event.attachments.single()["data"]
        )
    }

    @Test
    fun rejectsMessageFromChildFrame() {
        val events = Events()
        val boundary = boundary(events)

        boundary.onPostMessage(
            data = """{"type":"onOpen"}""",
            sourceOrigin = ALLOWED_ORIGIN,
            isMainFrame = false
        )

        assertTrue(events.isEmpty())
    }

    @Test
    fun rejectsMessageFromDisallowedOrigin() {
        val events = Events()
        val boundary = boundary(events)

        boundary.onPostMessage(
            data = """{"type":"onOpen"}""",
            sourceOrigin = "https://attacker.example",
            isMainFrame = true
        )

        assertTrue(events.isEmpty())
    }

    private fun boundary(events: Events) = MobileRendererMessageBoundary(
        allowedOrigin = ALLOWED_ORIGIN,
        onOpen = { events.openCount += 1 },
        onSVPClientEvent = { text, attachments ->
            events.clientEvents.add(ClientEvent(text, attachments))
        },
        onError = events.errors::add,
        onLinkClick = events.links::add,
        onDisplayModeChanged = events.displayModes::add
    )

    private data class ClientEvent(
        val text: String,
        val attachments: List<Map<String, Any?>>
    )

    private class Events {
        var openCount = 0
        val clientEvents = mutableListOf<ClientEvent>()
        val errors = mutableListOf<Throwable>()
        val links = mutableListOf<String>()
        val displayModes = mutableListOf<MobileRendererDisplayMode>()

        fun isEmpty() = openCount == 0 &&
            clientEvents.isEmpty() &&
            errors.isEmpty() &&
            links.isEmpty() &&
            displayModes.isEmpty()
    }

    private companion object {
        const val ALLOWED_ORIGIN = "https://sierra.chat"
    }
}
