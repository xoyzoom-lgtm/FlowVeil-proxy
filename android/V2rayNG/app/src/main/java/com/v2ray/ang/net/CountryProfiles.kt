package com.v2ray.ang.net

/**
 * "Сайты страны напрямую": the country's addresses and domains go directly, the rest through the server.
 * Data is the same as shared/country-profiles.json and Windows Handler/CountryProfiles.cs (tests compare them).
 * Every geosite/geoip name exists in the bundled geo files; countries without a geosite list use their national domain zones.
 */
object CountryProfiles {
    data class Country(
        val id: String,
        val nameRu: String,
        val nameEn: String,
        val flag: String,
        val geoip: String,
        val geosites: List<String>,
        val domainSuffixes: List<String>,
    )

    const val NONE = "none"

    val ALL: List<Country> = listOf(
        Country("ru", "Россия", "Russia", "🇷🇺", "ru", listOf("category-ru", "category-gov-ru", "category-bank-ru", "tld-ru"), listOf("ru", "su", "xn--p1ai")),
        Country("by", "Беларусь", "Belarus", "🇧🇾", "by", emptyList(), listOf("by", "xn--90ais")),
        Country("kz", "Казахстан", "Kazakhstan", "🇰🇿", "kz", emptyList(), listOf("kz", "xn--80ao21a")),
        Country("uz", "Узбекистан", "Uzbekistan", "🇺🇿", "uz", emptyList(), listOf("uz")),
        Country("ua", "Украина", "Ukraine", "🇺🇦", "ua", emptyList(), listOf("ua", "xn--j1amh")),
        Country("tr", "Турция", "Turkey", "🇹🇷", "tr", emptyList(), listOf("tr")),
        Country("ae", "ОАЭ", "UAE", "🇦🇪", "ae", emptyList(), listOf("ae")),
        Country("sa", "Саудовская Аравия", "Saudi Arabia", "🇸🇦", "sa", emptyList(), listOf("sa")),
        Country("ir", "Иран", "Iran", "🇮🇷", "ir", listOf("category-ir"), listOf("ir")),
        Country("cn", "Китай (материковый)", "China (mainland)", "🇨🇳", "cn", listOf("cn"), listOf("cn")),
        Country("cu", "Куба", "Cuba", "🇨🇺", "cu", emptyList(), listOf("cu")),
    )

    fun byId(id: String?): Country? = ALL.firstOrNull { it.id == id }

    /** Domain matchers for the "direct" rule: geosite lists, then national zones. */
    fun domains(country: Country): List<String> =
        country.geosites.map { "geosite:$it" } + country.domainSuffixes.map { "domain:$it" }

    fun ips(country: Country): List<String> = listOf("geoip:${country.geoip}")

    /** Short line for settings: "🇷🇺 Россия" or the "nothing" label given by the caller. */
    fun label(id: String?, nothing: String, russian: Boolean = true): String =
        byId(id)?.let { "${it.flag} ${if (russian) it.nameRu else it.nameEn}" } ?: nothing
}
