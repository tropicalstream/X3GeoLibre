package com.x3geolibre.app

import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * IP-based geolocation for the X3 Pro, which has no GNSS and no Google
 * location services (guide-verified: on-glasses GPS exists only via the
 * RayNeo companion-phone IPC). City-level accuracy (~5–30 km) is plenty
 * for "center the map on the user" at load; GeoLibre's own tools take it
 * from there. Two providers, first success wins.
 */
object IpLocator {

    private const val TAG = "X3GeoLibre-IpLoc"

    data class Fix(val lat: Double, val lon: Double, val accuracyM: Int, val label: String)

    /** Blocking — call from a worker thread. */
    fun locate(): Fix? {
        // ip-api.com: generous free tier, http-only on the free plan
        // (manifest allows cleartext, inherited from the shell).
        runCatching {
            val o = getJson("http://ip-api.com/json/?fields=status,lat,lon,city,country")
            if (o.optString("status") == "success") {
                return Fix(
                    o.getDouble("lat"), o.getDouble("lon"), 25_000,
                    listOf(o.optString("city"), o.optString("country"))
                        .filter { it.isNotBlank() }.joinToString(", ")
                )
            }
        }.onFailure { Log.w(TAG, "ip-api failed: ${it.message}") }
        runCatching {
            val o = getJson("https://ipapi.co/json/")
            val lat = o.optDouble("latitude", Double.NaN)
            val lon = o.optDouble("longitude", Double.NaN)
            if (!lat.isNaN() && !lon.isNaN()) {
                return Fix(
                    lat, lon, 25_000,
                    listOf(o.optString("city"), o.optString("country_name"))
                        .filter { it.isNotBlank() }.joinToString(", ")
                )
            }
        }.onFailure { Log.w(TAG, "ipapi.co failed: ${it.message}") }
        return null
    }

    private fun getJson(url: String): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            setRequestProperty("User-Agent", "x3geolibre/1.0")
        }
        try {
            val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return JSONObject(body)
        } finally {
            conn.disconnect()
        }
    }
}
