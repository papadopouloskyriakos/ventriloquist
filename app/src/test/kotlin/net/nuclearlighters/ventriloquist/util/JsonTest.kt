package net.nuclearlighters.ventriloquist.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {

    @Test
    fun parsesNestedValues() {
        val v = Json.parseObject("""{"a":1,"b":-2.5,"c":[true,false,null],"d":{"e":"x\"yé\n"},"f":1e3}""")
        assertEquals(1L, v["a"])
        assertEquals(-2.5, v["b"])
        assertEquals(listOf(true, false, null), v["c"])
        assertEquals(mapOf("e" to "x\"yé\n"), v["d"])
        assertEquals(1000.0, v["f"])
    }

    @Test
    fun writesWhatItReads() {
        val original = linkedMapOf<String, Any?>(
            "s" to "tab\tquote\"back\\slash\u0001",
            "n" to 42L,
            "d" to 0.5,
            "l" to listOf(1L, "two", null),
            "m" to linkedMapOf("k" to false),
        )
        assertEquals(original, Json.parse(Json.write(original)))
    }

    @Test
    fun writesNonFiniteAsNull() {
        assertEquals("[null,null]", Json.write(listOf(Double.NaN, Double.POSITIVE_INFINITY)))
    }

    @Test
    fun keepsNullMembers() {
        val v = Json.parseObject("""{"x":null}""")
        assertEquals(true, v.containsKey("x"))
        assertNull(v["x"])
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("", "{", "{\"a\":}", "[1,]", "{\"a\":1}x", "01", "\"\u0001\"", "nul", "{a:1}", "1.", "-")) {
            assertThrows(bad, Json.ParseException::class.java) { Json.parse(bad) }
        }
        assertThrows(Json.ParseException::class.java) { Json.parseObject("[1]") }
    }

    @Test
    fun rejectsDeepNesting() {
        assertThrows(Json.ParseException::class.java) { Json.parse("[".repeat(100) + "]".repeat(100)) }
    }
}
