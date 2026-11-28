package com.myra.assistant.system

import android.text.Html
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/**
 * Public-source web research for MYRA.
 * Uses DuckDuckGo HTML search, Google News RSS and Open-Meteo (no API key required).
 * Runs from AndroidToolsExecutor on Dispatchers.IO. Results always include source URLs.
 */
class WebResearchService {
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(18, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class Result(val success: Boolean, val message: String)

    fun search(rawQuery: String): Result {
        val query = rawQuery.trim().take(240)
        if (query.isBlank()) return Result(false, "किस विषय की इंटरनेट पर खोज करनी है?")
        val url = HttpUrl.Builder().scheme("https").host("html.duckduckgo.com").addPathSegment("html")
            .addQueryParameter("q", query).build()
        val html = get(url.toString()) ?: return Result(false, "अभी इंटरनेट सर्च सेवा से जवाब नहीं मिला। नेटवर्क जाँचकर फिर कोशिश करें।")
        val anchors = Regex("""(?is)<a\\b(?=[^>]*class=["'][^"']*result__a)([^>]*)>(.*?)</a>""")
            .findAll(html).toList()
        val results = anchors.mapIndexedNotNull { index, match ->
            val attributes = match.groupValues[1]
            val rawHref = Regex("""(?is)href=["']([^"']+)["']""").find(attributes)?.groupValues?.get(1)
            val href = rawHref?.let(::resolveDuckDuckGoUrl)
            val title = cleanHtml(match.groupValues[2])
            val segmentEnd = anchors.getOrNull(index + 1)?.range?.first ?: html.length
            val segment = html.substring(match.range.last + 1, segmentEnd.coerceAtMost(html.length))
            val snippet = Regex("""(?is)class=["'][^"']*result__snippet[^"']*["'][^>]*>(.*?)</(?:a|div|td)>""")
                .find(segment)?.groupValues?.get(1)?.let(::cleanHtml).orEmpty()
            if (!href.isNullOrBlank() && title.isNotBlank() && href.startsWith("http")) Triple(title, snippet, href) else null
        }.distinctBy { it.third }.take(5)
        if (results.isEmpty()) return Result(false, "“$query” के लिए भरोसेमंद खोज परिणाम नहीं मिले। थोड़ा अलग शब्दों में पूछें।")
        val text = buildString {
            append("इंटरनेट पर “").append(query).append("” के खोज परिणाम मिले। नीचे स्रोत लिंक हैं; जानकारी खोलकर तारीख और स्रोत जाँचें।")
            results.forEachIndexed { index, item ->
                append("\n").append(index + 1).append(". ").append(item.first)
                if (item.second.isNotBlank()) append(" — ").append(item.second.take(260))
                append("\nलिंक: ").append(item.third)
            }
        }
        return Result(true, text)
    }

    fun weather(rawLocation: String): Result {
        val location = rawLocation.trim().take(120)
        if (location.isBlank()) return Result(false, "मौसम बताने के लिए शहर या जगह का नाम बताइए।")
        val geoUrl = HttpUrl.Builder().scheme("https").host("geocoding-api.open-meteo.com")
            .addPathSegment("v1").addPathSegment("search")
            .addQueryParameter("name", location).addQueryParameter("count", "1")
            .addQueryParameter("language", "hi").addQueryParameter("format", "json").build()
        val geo = get(geoUrl.toString())?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Result(false, "मौसम सेवा से जगह की जानकारी नहीं मिली। इंटरनेट जाँचें।")
        val place = geo.optJSONArray("results")?.optJSONObject(0)
            ?: return Result(false, "“$location” नाम की जगह नहीं मिली। शहर का सही नाम बताइए।")
        val lat = place.optDouble("latitude", Double.NaN)
        val lon = place.optDouble("longitude", Double.NaN)
        if (!lat.isFinite() || !lon.isFinite()) return Result(false, "इस जगह के निर्देशांक उपलब्ध नहीं हैं।")
        val forecastUrl = HttpUrl.Builder().scheme("https").host("api.open-meteo.com")
            .addPathSegment("v1").addPathSegment("forecast")
            .addQueryParameter("latitude", lat.toString()).addQueryParameter("longitude", lon.toString())
            .addQueryParameter("current", "temperature_2m,relative_humidity_2m,apparent_temperature,is_day,precipitation,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("forecast_days", "1").addQueryParameter("timezone", "auto").build()
        val forecast = get(forecastUrl.toString())?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return Result(false, "मौसम का ताज़ा डेटा अभी उपलब्ध नहीं है। थोड़ी देर बाद कोशिश करें।")
        val current = forecast.optJSONObject("current") ?: return Result(false, "मौसम का वर्तमान डेटा उपलब्ध नहीं है।")
        val daily = forecast.optJSONObject("daily")
        val max = daily?.optJSONArray("temperature_2m_max")?.optDouble(0, Double.NaN)
        val min = daily?.optJSONArray("temperature_2m_min")?.optDouble(0, Double.NaN)
        val rain = daily?.optJSONArray("precipitation_probability_max")?.optInt(0, -1)
        val code = current.optInt("weather_code", -1)
        val description = weatherDescription(code)
        val name = listOf(place.optString("name"), place.optString("admin1"), place.optString("country"))
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
        val message = buildString {
            append("मौसम अपडेट — ").append(name).append("\n")
            append("अभी: ").append(current.optDouble("temperature_2m")).append("°C, ").append(description).append("\n")
            append("महसूस हो रहा: ").append(current.optDouble("apparent_temperature")).append("°C; ")
            append("नमी: ").append(current.optInt("relative_humidity_2m")).append("%; ")
            append("हवा: ").append(current.optDouble("wind_speed_10m")).append(" km/h.\n")
            if (max?.isFinite() == true && min?.isFinite() == true) append("आज का तापमान: ").append(min).append("°C से ").append(max).append("°C.\n")
            if (rain != null && rain >= 0) append("बारिश की अधिकतम संभावना: ").append(rain).append("%.\n")
            append("स्रोत: Open-Meteo — https://open-meteo.com/")
        }
        return Result(true, message)
    }

    fun news(rawTopic: String): Result {
        val topic = rawTopic.trim().take(120)
        val builder = HttpUrl.Builder().scheme("https").host("news.google.com").addPathSegment("rss")
        if (topic.isNotBlank()) builder.addPathSegment("search").addQueryParameter("q", topic)
        val url = builder.addQueryParameter("hl", "hi").addQueryParameter("gl", "IN").addQueryParameter("ceid", "IN:hi").build()
        val xml = get(url.toString()) ?: return Result(false, "अभी समाचार फ़ीड नहीं खुल रही। इंटरनेट जाँचकर फिर कोशिश करें।")
        val items = parseNews(xml).take(7)
        if (items.isEmpty()) return Result(false, "इस समय समाचार फ़ीड में परिणाम नहीं मिले।")
        val heading = if (topic.isBlank()) "आज की मुख्य खबरें" else "“$topic” से जुड़ी खबरें"
        val message = buildString {
            append(heading).append(" — Google News RSS\n")
            items.forEachIndexed { index, item ->
                append(index + 1).append(". ").append(item.first).append("\n")
                if (item.second.isNotBlank()) append("समय: ").append(item.second).append("\n")
                append("लिंक: ").append(item.third).append("\n")
            }
            append("समाचार का समय और पूरी खबर खोलकर सत्यापित करें।")
        }
        return Result(true, message)
    }

    private fun get(url: String): String? = runCatching {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 Chrome/124.0 Mobile Safari/537.36")
            .header("Accept-Language", "hi-IN,hi;q=0.9,en;q=0.8").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            response.body?.string()?.takeIf { it.isNotBlank() && it.length < 2_000_000 }
        }
    }.getOrNull()

    private fun parseNews(xml: String): List<Triple<String, String, String>> = runCatching {
        val parser = XmlPullParserFactory.newInstance().newPullParser()
        parser.setInput(StringReader(xml))
        val found = mutableListOf<Triple<String, String, String>>()
        var inItem = false
        var title = ""; var link = ""; var date = ""
        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> when (parser.name.lowercase()) {
                    "item" -> { inItem = true; title = ""; link = ""; date = "" }
                    "title" -> if (inItem) title = parser.nextText().trim()
                    "link" -> if (inItem) link = parser.nextText().trim()
                    "pubdate" -> if (inItem) date = parser.nextText().trim()
                }
                XmlPullParser.END_TAG -> if (parser.name.equals("item", true) && inItem) {
                    if (title.isNotBlank() && link.startsWith("http")) found += Triple(title, date, link)
                    inItem = false
                }
            }
            event = parser.next()
        }
        found
    }.getOrDefault(emptyList())

    private fun resolveDuckDuckGoUrl(raw: String): String {
        val decoded = Html.fromHtml(raw, Html.FROM_HTML_MODE_LEGACY).toString()
        val target = Regex("""[?&]uddg=([^&]+)""").find(decoded)?.groupValues?.get(1)
        return if (target != null) runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(decoded) else decoded
    }

    private fun cleanHtml(raw: String): String =
        Html.fromHtml(raw.replace(Regex("""(?is)<br\s*/?>"""), " "), Html.FROM_HTML_MODE_LEGACY)
            .toString().replace(Regex("\\s+"), " ").trim()

    private fun weatherDescription(code: Int): String = when (code) {
        0 -> "आसमान साफ़"
        1, 2 -> "आंशिक बादल"
        3 -> "बादल छाए हुए"
        45, 48 -> "कोहरा"
        51, 53, 55, 56, 57 -> "हल्की बूंदाबांदी"
        61, 63, 65, 66, 67 -> "बारिश"
        71, 73, 75, 77 -> "बर्फ़बारी"
        80, 81, 82 -> "बारिश की बौछारें"
        85, 86 -> "बर्फ़ की बौछारें"
        95, 96, 99 -> "गरज-चमक के साथ तूफ़ान"
        else -> "मौसम की स्थिति उपलब्ध"
    }
}
