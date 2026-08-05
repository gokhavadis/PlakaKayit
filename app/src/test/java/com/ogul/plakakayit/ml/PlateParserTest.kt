package com.ogul.plakakayit.ml

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlateParserTest {
    @Test
    fun findsCommonTurkishPlateFormats() {
        val result = PlateParser.findPlates(
            listOf("54 ABC 123", "34-AB-1234", "06 A 1234")
        )
        assertEquals(setOf("54 ABC 123", "34 AB 1234", "06 A 1234"), result)
    }

    @Test
    fun rejectsInvalidProvinceAndLengths() {
        assertFalse(PlateParser.isPlausible("99ABC123"))
        assertFalse(PlateParser.isPlausible("34A123"))
        assertFalse(PlateParser.isPlausible("34AB12"))
        assertTrue(PlateParser.isPlausible("34ABC12"))
    }
}
