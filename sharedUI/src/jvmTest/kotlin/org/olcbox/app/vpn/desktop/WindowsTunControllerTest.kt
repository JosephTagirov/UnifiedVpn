package org.olcbox.app.vpn.desktop

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WindowsTunControllerTest {
    @Test
    fun onlyLoopbackPortCredentialsAndPinnedEndpointAreSentToHelper() {
        val config = Json.parseToJsonElement(WindowsTunController.helperConfiguration(
            10810, "local\"user", "escaped\\password\n", listOf("203.0.113.42")
        )).jsonObject
        assertEquals(setOf("port", "username", "password", "endpoints"), config.keys)
        assertEquals(10810, config.getValue("port").jsonPrimitive.int)
        assertEquals("local\"user", config.getValue("username").jsonPrimitive.content)
        assertEquals("escaped\\password\n", config.getValue("password").jsonPrimitive.content)
        assertEquals("203.0.113.42", config.getValue("endpoints").jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun rejectsMissingAndMultipleServerAddresses() {
        for (endpoints in listOf(emptyList(), listOf("203.0.113.42", "203.0.113.43"))) {
            assertFailsWith<IllegalArgumentException> {
                WindowsTunController.helperConfiguration(10810, "", "", endpoints)
            }
        }
    }

    @Test
    fun rejectsPortsAndCredentialsThatTheElevatedHelperCannotAccept() {
        for (port in listOf(-1, 0, 1023, 65536)) {
            assertFailsWith<IllegalArgumentException> {
                WindowsTunController.helperConfiguration(port, "", "", listOf("203.0.113.42"))
            }
        }
        for ((username, password) in listOf("x".repeat(65) to "", "" to "x".repeat(65), "\u0000" to "", "" to "\u0000")) {
            assertFailsWith<IllegalArgumentException> {
                WindowsTunController.helperConfiguration(10810, username, password, listOf("203.0.113.42"))
            }
        }
    }
}
