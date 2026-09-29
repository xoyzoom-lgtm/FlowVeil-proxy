package com.v2ray.ang.handler

/** Recognises servers located in Russia by their name (flag or country/city name). */
object ServerCountry {
    private val russianMarkers = listOf(
        "🇷🇺", "россия", "russia", "москва", "moscow", "санкт-петербург", "петербург", "спб",
        "st. petersburg", "saint petersburg", "новосибирск", "екатеринбург",
    )
    private val ruCode = Regex("""(^|[^a-z])ru([^a-z]|$)""", RegexOption.IGNORE_CASE)

    fun isRussian(remarks: String?): Boolean {
        val name = remarks?.lowercase() ?: return false
        return russianMarkers.any { it in name } || ruCode.containsMatchIn(name)
    }
}
