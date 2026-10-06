package com.v2ray.ang.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class CountryProfilesTest {
    private fun shared(): String {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val f = File(dir, "shared/country-profiles.json")
            if (f.exists()) return f.readText()
            dir = dir.parentFile
        }
        error("shared/country-profiles.json not found")
    }

    private fun list(obj: String, key: String): List<String> =
        Regex("\"$key\":\\s*\\[([^\\]]*)]").find(obj)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }

    private fun str(obj: String, key: String): String = Regex("\"$key\":\\s*\"([^\"]*)\"").find(obj)!!.groupValues[1]

    @Test
    fun sameAsSharedJson() {
        val objects = Regex("\\{\\s*\"id\"[^}]*}").findAll(shared()).map { it.value }.toList()
        assertEquals(objects.size, CountryProfiles.ALL.size)
        objects.zip(CountryProfiles.ALL).forEach { (o, c) ->
            assertEquals(str(o, "id"), c.id)
            assertEquals(str(o, "nameRu"), c.nameRu)
            assertEquals(str(o, "nameEn"), c.nameEn)
            assertEquals(str(o, "flag"), c.flag)
            assertEquals(str(o, "geoip"), c.geoip)
            assertEquals(list(o, "geosites"), c.geosites)
            assertEquals(list(o, "domainSuffixes"), c.domainSuffixes)
        }
    }

    @Test
    fun rulesForRussia() {
        val ru = CountryProfiles.byId("ru")!!
        assertEquals(listOf("geoip:ru"), CountryProfiles.ips(ru))
        assertEquals("geosite:category-ru", CountryProfiles.domains(ru).first())
        assertTrue("domain:ru" in CountryProfiles.domains(ru))
    }

    @Test
    fun nothingAndUnknown() {
        assertNull(CountryProfiles.byId(CountryProfiles.NONE))
        assertNull(CountryProfiles.byId(null))
        assertEquals("—", CountryProfiles.label("zz", "—"))
        assertEquals("🇧🇾 Беларусь", CountryProfiles.label("by", "—"))
    }

    @Test
    fun idsAreUniqueAndLowercase() {
        val ids = CountryProfiles.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        ids.forEach { assertEquals(it.lowercase(), it) }
    }
}
