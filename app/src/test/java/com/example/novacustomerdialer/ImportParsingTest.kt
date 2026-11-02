package com.example.novacustomerdialer

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.Charset

class ImportParsingTest {
    @Test
    fun csvKeepsQuotedCommasAndNewlines() {
        val rows = parseCsv("姓名,手机号,备注\n张三,13800138000,\"第一行,逗号\n第二行\"\n")
        assertEquals(2, rows.size)
        assertEquals("第一行,逗号\n第二行", rows[1][2])
    }

    @Test
    fun decodesGbkWhenNotUtf8() {
        val bytes = "姓名,手机号\n李四,13900000000".toByteArray(Charset.forName("GBK"))
        assertEquals("姓名,手机号\n李四,13900000000", decodeTextAuto(bytes))
    }

    @Test
    fun stripsUtf8Bom() {
        val bytes = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a,b".toByteArray(Charsets.UTF_8)
        assertEquals("a,b", decodeTextAuto(bytes))
    }

    @Test
    fun normalizesDates() {
        assertEquals("2026-11-02", normalizeDateText("2026/11/2"))
        assertEquals("2026-11-02", normalizeDateText("2026年11月2日"))
        assertEquals("2026-11-02", normalizeDateText("46328"))
        assertEquals("", normalizeDateText(""))
    }

    @Test
    fun keepsPlusInDialNumber() {
        assertEquals("+8613900000000", normalizeDialNumber("+86 139 0000 0000"))
        assertEquals("13800138000", normalizeDialNumber("138-0013-8000"))
    }

    @Test
    fun expandsScientificNumbers() {
        assertEquals("13800138000", plainNumber("1.3800138E10"))
        assertEquals("13800138000", plainNumber("13800138000"))
    }
}
