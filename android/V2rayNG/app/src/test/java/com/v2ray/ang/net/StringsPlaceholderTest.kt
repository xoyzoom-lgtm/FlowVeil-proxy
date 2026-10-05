package com.v2ray.ang.net

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every translated string keeps the placeholders of the English one, in the same order and kind (a mismatch crashes String.format). */
class StringsPlaceholderTest {
    private fun res(): File = listOf("src/main/res", "app/src/main/res", "V2rayNG/app/src/main/res")
        .map { File(it) }.firstOrNull { File(it, "values/strings.xml").exists() }
        ?: error("res folder not found from ${File(".").absolutePath}")

    private fun strings(f: File): Map<String, String> =
        Regex("""<string name="([^"]+)"[^>]*>(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).findAll(f.readText()).associate { it.groupValues[1] to it.groupValues[2] }

    private val ph = Regex("""%(\d+\$)?[-#+ 0,(]*\d*(\.\d+)?[sdfxXcb%]""")

    private fun placeholders(v: String) = ph.findAll(v).map { it.value }.filter { it != "%%" }.sorted().toList()

    @Test
    fun russianStringsKeepThePlaceholders() {
        val dir = res()
        val en = strings(File(dir, "values/strings.xml"))
        val ru = strings(File(dir, "values-ru/strings.xml"))
        val bad = ru.filter { (k, v) -> en[k] != null && placeholders(v) != placeholders(en[k]!!) }.keys
        assertTrue("placeholders differ: $bad", bad.isEmpty())
    }
}
