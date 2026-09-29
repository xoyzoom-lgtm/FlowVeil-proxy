package com.v2ray.ang.handler

/** Recognises servers located in Russia by their name (flag or country/city name). */
object ServerCountry {
    private val russianMarkers = listOf(
        "🇷🇺", "россия", "russia", "москва", "moscow", "санкт-петербург", "петербург", "спб",
        "st. petersburg", "saint petersburg", "новосибирск", "екатеринбург",
    )
    private val ruCode = Regex("""(^|[^a-z])ru([^a-z]|$)""", RegexOption.IGNORE_CASE)

    private const val REGIONAL_A = 0x1F1E6
    private const val REGIONAL_Z = 0x1F1FF

    /** Two-letter country code from a flag emoji in the name ("🇩🇪 Berlin" -> "DE"), or null. */
    fun code(remarks: String?): String? {
        if (remarks.isNullOrEmpty()) return null
        val points = remarks.codePoints().toArray()
        for (i in 0 until points.size - 1) {
            if (points[i] in REGIONAL_A..REGIONAL_Z && points[i + 1] in REGIONAL_A..REGIONAL_Z) {
                return "${'A' + (points[i] - REGIONAL_A)}${'A' + (points[i + 1] - REGIONAL_A)}"
            }
        }
        return null
    }

    fun isRussian(remarks: String?): Boolean {
        val name = remarks?.lowercase() ?: return false
        return russianMarkers.any { it in name } || ruCode.containsMatchIn(name)
    }
}
