package com.btcozum.btvault

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import java.security.MessageDigest

/**
 * AsyaWatch -> four.pichive.online (NP player) cozucusu.
 *
 * iframe.php sayfasi `window.NP_CONFIG` icinde kaynak listesi ve bir
 * proof-of-work kapisi (gate) yayinlar:
 *
 *   gate.n + "oDesGxmweI" + nonce(base36) + "oDesGxmweI"
 *   SHA-256 -> 7. kelime (bayt 24..27, big-endian) >>> 20 == 0
 *
 * Bulunan nonce `&p=` olarak master ve altyazi adreslerine eklenir.
 * Adresler Referer istiyor; m3u8 jetonlari da ayni Referer'a bagli.
 */
object AsyaWatchPichive {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private const val SALT = "oDesGxmweI"

    private fun solveGate(n: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (i in 0 until 5_000_000) {
            val nonce = i.toString(36)
            val h = md.digest((n + SALT + nonce + SALT).toByteArray(Charsets.UTF_8))
            val w6 = ((h[24].toInt() and 0xFF) shl 24) or
                ((h[25].toInt() and 0xFF) shl 16) or
                ((h[26].toInt() and 0xFF) shl 8) or
                (h[27].toInt() and 0xFF)
            if ((w6 ushr 20) == 0) return nonce
        }
        return "0"
    }

    private fun extractConfig(html: String): JSONObject? {
        val start = html.indexOf("window.NP_CONFIG")
        if (start < 0) return null
        val braceStart = html.indexOf('{', start)
        if (braceStart < 0) return null
        val endMarker = html.indexOf("window.localStorage2", braceStart).let {
            if (it < 0) html.indexOf("</script>", braceStart) else it
        }
        if (endMarker < 0) return null
        var json = html.substring(braceStart, endMarker).trim()
        json = json.removeSuffix(";").trim()
        // NP_CONFIG her zaman } ile biter
        val lastBrace = json.lastIndexOf('}')
        if (lastBrace >= 0) json = json.substring(0, lastBrace + 1)
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e("AsyaWatchPichive", "NP_CONFIG parse hatasi: ${e.message}")
            null
        }
    }

    suspend fun resolve(
        iframeUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val origin = Regex("""^(https?://[^/]+)""").find(iframeUrl)?.groupValues?.get(1) ?: return false
        val referer = "$origin/"
        val html = try {
            app.get(
                iframeUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            ).text
        } catch (e: Exception) {
            Log.e("AsyaWatchPichive", "iframe alinamadi: ${e.message}")
            return false
        }

        val config = extractConfig(html) ?: return false
        val gateN = config.optJSONObject("gate")?.optString("n").orEmpty()
        val suffix = if (gateN.isNotBlank()) "&p=${solveGate(gateN)}" else ""

        var found = false
        val sources = config.optJSONArray("sources")
        if (sources != null) {
            for (i in 0 until sources.length()) {
                val src = sources.optJSONObject(i) ?: continue
                val master = src.optString("master").takeIf { it.isNotBlank() } ?: continue
                val quality = src.optString("title").ifBlank { "AsyaWatch" }
                val url = if (suffix.isEmpty()) master else master + suffix
                callback.invoke(
                    newExtractorLink(
                        source = "AsyaWatch",
                        name = "$quality",
                        url = url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = referer
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "User-Agent" to USER_AGENT,
                            "Referer" to referer
                        )
                    }
                )
                found = true
            }
        }

        val subs = config.optJSONArray("subtitles")
        if (subs != null) {
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                val url = sub.optString("url").takeIf { it.isNotBlank() } ?: continue
                val label = sub.optString("label").ifBlank { "Altyazı" }
                subtitleCallback.invoke(
                    SubtitleFile(label, if (suffix.isEmpty()) url else url + suffix)
                )
            }
        }

        return found
    }
}
