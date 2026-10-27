package com.adamglassner.larksong.weather

import com.adamglassner.larksong.data.City
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

data class WeatherNow(
    val place: String,
    val gps: Boolean,
    val temp: Int,
    val code: Int,
    val high: Int,
    val low: Int,
)

/** A place to show weather for: either "my location" or a saved city. */
data class WeatherPlace(val name: String, val gps: Boolean, val city: City?)

data class ApproxLocation(val lat: Double, val lon: Double, val city: String)

object Weather {

    private fun get(url: String): String {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 10_000
        conn.setRequestProperty("User-Agent", "Larksong-Desktop")
        try {
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    suspend fun fetch(lat: Double, lon: Double, celsius: Boolean, place: String, gps: Boolean): WeatherNow? =
        withContext(Dispatchers.IO) {
            runCatching {
                val unit = if (celsius) "celsius" else "fahrenheit"
                val url = String.format(
                    Locale.US,
                    "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f" +
                        "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min" +
                        "&temperature_unit=%s&timezone=auto&forecast_days=1",
                    lat, lon, unit,
                )
                val o = JSONObject(get(url))
                val cur = o.getJSONObject("current")
                val daily = o.getJSONObject("daily")
                WeatherNow(
                    place = place,
                    gps = gps,
                    temp = Math.round(cur.getDouble("temperature_2m")).toInt(),
                    code = cur.getInt("weather_code"),
                    high = Math.round(daily.getJSONArray("temperature_2m_max").getDouble(0)).toInt(),
                    low = Math.round(daily.getJSONArray("temperature_2m_min").getDouble(0)).toInt(),
                )
            }.getOrNull()
        }

    suspend fun searchCities(query: String): List<City> = withContext(Dispatchers.IO) {
        runCatching {
            val q = URLEncoder.encode(query.trim(), "UTF-8")
            val o = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=8&language=en&format=json"))
            val arr = o.optJSONArray("results") ?: return@runCatching emptyList<City>()
            (0 until arr.length()).map { i ->
                val r = arr.getJSONObject(i)
                val region = listOf(r.optString("admin1", ""), r.optString("country", ""))
                    .filter { it.isNotBlank() }.joinToString(", ")
                City(r.getString("name"), region, r.getDouble("latitude"), r.getDouble("longitude"))
            }
        }.getOrDefault(emptyList())
    }

    /** PCs rarely have GPS, so "my location" is an approximate one based on the internet connection. */
    suspend fun approxLocation(): ApproxLocation? = withContext(Dispatchers.IO) {
        runCatching {
            val o = JSONObject(get("https://ipapi.co/json/"))
            ApproxLocation(o.getDouble("latitude"), o.getDouble("longitude"), o.optString("city", "My location"))
        }.recoverCatching {
            val o = JSONObject(get("http://ip-api.com/json/"))
            ApproxLocation(o.getDouble("lat"), o.getDouble("lon"), o.optString("city", "My location"))
        }.getOrNull()
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Clear"
        1 -> "Mostly clear"
        2 -> "Partly cloudy"
        3 -> "Overcast"
        45, 48 -> "Fog"
        51, 53, 55, 56, 57 -> "Drizzle"
        61, 63, 65, 66, 67, 80, 81, 82 -> "Rain"
        71, 73, 75, 77, 85, 86 -> "Snow"
        95, 96, 99 -> "Thunderstorms"
        else -> "—"
    }
}
