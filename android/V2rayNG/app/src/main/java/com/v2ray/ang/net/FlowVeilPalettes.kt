package com.v2ray.ang.net

/** FlowVeil's own ready colour themes: five colours each (the app derives the rest, see [ThemeBuilder]). */
object FlowVeilPalettes {
    data class Palette(val id: String, val name: String, val colors: ThemeBuilder.Colors)

    private fun p(id: String, name: String, top: String, bottom: String, accent: String, card: String, text: String) =
        Palette(id, name, ThemeBuilder.Colors(top, bottom, accent, card, text))

    val ALL: List<Palette> = listOf(
        p("fv_mint", "Мята 🌿", "0B0E14", "151A23", "5FF0C4", "1C2330", "F2F5FA"),
        p("fv_aurora", "Аврора 🌌", "071A2B", "12394D", "6EE7B7", "123246", "EAFBF5"),
        p("fv_sunset", "Закат 🌇", "1B0F1F", "3A1534", "FF8A5B", "2B1A33", "FFF1EA"),
        p("fv_ocean", "Океан 🌊", "04131F", "0B2D45", "38BDF8", "0F2A3D", "E6F4FF"),
        p("fv_graphite", "Графит ⚙️", "101114", "1A1C21", "A3A8B8", "20232A", "F2F3F5"),
        p("fv_rose", "Роза 🌸", "1F0F18", "35172A", "FF5C93", "2C1824", "FFEFF5"),
        p("fv_forest", "Лес 🌲", "07140E", "10281B", "4ADE80", "12301F", "E9FBEF"),
        p("fv_gold", "Золотая ночь ✨", "0D0B07", "1A150C", "F5C451", "221C10", "FFF6DD"),
        p("fv_neon", "Неон 💜", "0A0710", "1A0B2E", "FF3DF2", "1F1033", "F8E8FF"),
        p("fv_oled", "Чёрная OLED ⚫", "000000", "000000", "4F83F0", "0F1115", "F5F7FA"),
        p("fv_lavender", "Лаванда 💐", "F4F0FF", "E3DAFF", "6D4AE8", "FFFFFF", "1F1A33"),
        p("fv_sand", "Песок 🏜", "FBF5EA", "F0E2C8", "B45309", "FFFFFF", "2A2113"),
        p("fv_snow", "Снег ❄️", "F6F9FC", "E2EAF3", "2563EB", "FFFFFF", "0F172A"),
    )
}
