package com.minifile.explorer.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiniJsonTest {

    @Test
    fun parsesAndStringifiesEscapesUnicodeNestingAndPrimitives() {
        val original = MiniJson.obj(
            "str" to JsonValue.Str("line1\nline2\t\"quoted\"\\slash / unicode: 你好 🚀 \u0001"),
            "boolTrue" to JsonValue.Bool(true),
            "boolFalse" to JsonValue.Bool(false),
            "nullVal" to JsonValue.Null,
            "intVal" to JsonValue.Num(42),
            "negFloat" to JsonValue.Num(-3.14159),
            "arr" to JsonValue.Arr(
                listOf(
                    JsonValue.Str("nested"),
                    MiniJson.obj("inner" to JsonValue.Num(999999999999L)),
                ),
            ),
        )

        val compact = MiniJson.stringify(original)
        val pretty = MiniJson.prettyPrint(compact, indent = 2)

        val reparsed = MiniJson.parse(pretty) as JsonValue.Obj
        assertEquals("line1\nline2\t\"quoted\"\\slash / unicode: 你好 🚀 \u0001", reparsed.str("str"))
        assertEquals(true, reparsed.bool("boolTrue"))
        assertEquals(false, reparsed.bool("boolFalse"))
        assertEquals(JsonValue.Null, reparsed.entries["nullVal"])
        assertEquals(42, reparsed.int("intVal"))
        assertEquals(999999999999L, reparsed.arr("arr")?.items?.get(1)?.let { (it as JsonValue.Obj).long("inner") })

        // Explicit \uXXXX escape sequence decoding
        val unicodeDecoded = MiniJson.parse("{\"u\":\"\\u4F60\\u597D\"}") as JsonValue.Obj
        assertEquals("你好", unicodeDecoded.str("u"))
    }

    @Test
    fun rejectsMalformedInputsCleanly() {
        val malformed = listOf(
            "",
            "{",
            "{\"a\": 1} trailing",
            "{\"unterminated",
            "[1, 2, ]",
            "{\"badEscape\": \"\\x\"}",
            "{\"badUnicode\": \"\\u12G4\"}",
            "tru",
        )
        for (bad in malformed) {
            val failure = runCatching { MiniJson.parse(bad) }.exceptionOrNull()
            assertNotNull("Expected failure for malformed input: $bad", failure)
            assertTrue(failure is IllegalArgumentException)
        }
    }
}
