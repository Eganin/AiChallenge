// src/test/kotlin/MessageTest.kt
package org.example

import kotlin.test.Test
import kotlin.test.assertEquals

class MessageTest {

    @Test
    fun `user message has correct role and content`() {
        val msg = Message(role = "user", content = "hello")
        assertEquals("user", msg.role)
        assertEquals("hello", msg.content)
    }

    @Test
    fun `assistant message has correct role and content`() {
        val msg = Message(role = "assistant", content = "hi there")
        assertEquals("assistant", msg.role)
        assertEquals("hi there", msg.content)
    }
}
