package com.example.util

import androidx.compose.runtime.Immutable
import com.example.domain.model.ServerProfile

@Immutable
data class ResolvedServerLocation(
    val countryCode: String,
    val flagEmoji: String,
    val nameFa: String,
    val nameEn: String
)

object ServerLocationResolver {

    private data class CountryPattern(
        val code: String,
        val flag: String,
        val nameFa: String,
        val nameEn: String,
        val keywords: List<String>
    )

    private val countries = listOf(
        CountryPattern("DE", "🇩🇪", "آلمان", "Germany", listOf("🇩🇪", "germany", "frankfurt", "falkenstein", "nuremberg", "berlin", "آلمان", "المان", ".de")),
        CountryPattern("NL", "🇳🇱", "هلند", "Netherlands", listOf("🇳🇱", "netherlands", "amsterdam", "rotterdam", "holland", "هلند", ".nl")),
        CountryPattern("FI", "🇫🇮", "فنلاند", "Finland", listOf("🇫🇮", "finland", "helsinki", "فنلاند", ".fi")),
        CountryPattern("US", "🇺🇸", "آمریکا", "United States", listOf("🇺🇸", "united states", "usa", "new york", "los angeles", "dallas", "chicago", "miami", "seattle", "آمریکا", "امریکا", ".us")),
        CountryPattern("GB", "🇬🇧", "انگلستان", "United Kingdom", listOf("🇬🇧", "united kingdom", "london", "manchester", "england", "britain", "انگلستان", "انگلیس", "بریتانیا", ".uk")),
        CountryPattern("FR", "🇫🇷", "فرانسه", "France", listOf("🇫🇷", "france", "paris", "marseille", "strasbourg", "فرانسه", ".fr")),
        CountryPattern("TR", "🇹🇷", "ترکیه", "Turkey", listOf("🇹🇷", "turkey", "istanbul", "ankara", "izmir", "ترکیه", "استانبول", ".tr")),
        CountryPattern("AE", "🇦🇪", "امارات", "UAE", listOf("🇦🇪", "uae", "dubai", "abu dhabi", "emirates", "امارات", "دبی", ".ae")),
        CountryPattern("SG", "🇸🇬", "سنگاپور", "Singapore", listOf("🇸🇬", "singapore", "سنگاپور", ".sg")),
        CountryPattern("JP", "🇯🇵", "ژاپن", "Japan", listOf("🇯🇵", "japan", "tokyo", "osaka", "ژاپن", ".jp")),
        CountryPattern("SE", "🇸🇪", "سوئد", "Sweden", listOf("🇸🇪", "sweden", "stockholm", "سوئد", ".se")),
        CountryPattern("CH", "🇨🇭", "سوئیس", "Switzerland", listOf("🇨🇭", "switzerland", "zurich", "geneva", "سوئیس", ".ch")),
        CountryPattern("CA", "🇨🇦", "کانادا", "Canada", listOf("🇨🇦", "canada", "toronto", "montreal", "vancouver", "کانادا", ".ca")),
        CountryPattern("AT", "🇦🇹", "اتریش", "Austria", listOf("🇦🇹", "austria", "vienna", "اتریش", ".at")),
        CountryPattern("IT", "🇮🇹", "ایتالیا", "Italy", listOf("🇮🇹", "italy", "milan", "rome", "ایتالیا", ".it")),
        CountryPattern("ES", "🇪🇸", "اسپانیا", "Spain", listOf("🇪🇸", "spain", "madrid", "barcelona", "اسپانیا", ".es")),
        CountryPattern("PL", "🇵🇱", "لهستان", "Poland", listOf("🇵🇱", "poland", "warsaw", "لهستان", ".pl")),
        CountryPattern("RU", "🇷🇺", "روسیه", "Russia", listOf("🇷🇺", "russia", "moscow", "روسیه", ".ru")),
        CountryPattern("IN", "🇮🇳", "هند", "India", listOf("🇮🇳", "india", "mumbai", "هند", ".in")),
        CountryPattern("KR", "🇰🇷", "کره جنوبی", "South Korea", listOf("🇰🇷", "korea", "seoul", "کره", ".kr")),
        CountryPattern("HK", "🇭🇰", "هنگ‌کنگ", "Hong Kong", listOf("🇭🇰", "hong kong", "hongkong", "هنگ کنگ", "هنگ‌کنگ", ".hk")),
        CountryPattern("AU", "🇦🇺", "استرالیا", "Australia", listOf("🇦🇺", "australia", "sydney", "استرالیا", ".au"))
    )

    private val unknownLocation = ResolvedServerLocation(
        countryCode = "GLOBAL",
        flagEmoji = "🌐",
        nameFa = "بین‌المللی",
        nameEn = "Global"
    )

    fun resolve(profile: ServerProfile): ResolvedServerLocation {
        val remarksLower = profile.remarks.lowercase()
        val groupLower = profile.groupName.lowercase()
        val addressLower = profile.address.lowercase()
        val hostLower = profile.host.lowercase()
        val sniLower = profile.sni.lowercase()

        for (country in countries) {
            for (kw in country.keywords) {
                if (remarksLower.contains(kw) ||
                    groupLower.contains(kw) ||
                    addressLower.endsWith(kw) ||
                    hostLower.endsWith(kw) ||
                    sniLower.endsWith(kw)
                ) {
                    return ResolvedServerLocation(
                        countryCode = country.code,
                        flagEmoji = country.flag,
                        nameFa = country.nameFa,
                        nameEn = country.nameEn
                    )
                }
            }
        }

        // Check token boundary matches for 2-letter ISO codes in remarks like "[DE]" or "DE-1" or "US_01"
        for (country in countries) {
            val codeLower = country.code.lowercase()
            if (hasIsoToken(remarksLower, codeLower) || hasIsoToken(groupLower, codeLower)) {
                return ResolvedServerLocation(
                    countryCode = country.code,
                    flagEmoji = country.flag,
                    nameFa = country.nameFa,
                    nameEn = country.nameEn
                )
            }
        }

        return unknownLocation
    }

    private fun hasIsoToken(text: String, isoLower: String): Boolean {
        if (text.length < 2) return false
        var idx = text.indexOf(isoLower)
        while (idx != -1) {
            val beforeOk = idx == 0 || !text[idx - 1].isLetter()
            val afterIdx = idx + isoLower.length
            val afterOk = afterIdx >= text.length || !text[afterIdx].isLetter()
            if (beforeOk && afterOk) return true
            idx = text.indexOf(isoLower, idx + 1)
        }
        return false
    }
}
