package com.btcozum.btvault

import android.util.Base64
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * japierdolevid.com tabanli oynat_ici cozucu.
 *
 * Sayfa her istekte farkli sekilde gizlenmis bir "challenge" uretir (_cr).
 * Su anki varyantlar:
 *   1) String.fromCharCode(arr[i] - OFFSET)
 *   2) String.fromCharCode(arr1[i] ^ arr2[i % arr2.length])
 *   3) atob('..') + atob('..')
 *
 * Akis:
 *   GET  {embed}                     -> _cr, _ept, _ws
 *   POST {embed}/stream  (JSON)      -> {s, d, v, p1..p4, x}
 *   AES-CBC(SHA256(p1+p2+p3+p4 + cr + x), v) ile d cozulur
 *   icteki encrypted_player_data (AES-CBC, anahtar = k1+k2+k3+k4) -> HTML -> m3u8
 *
 * m3u8 jetonu IP + User-Agent + Referer'e baglidir; bu yuzden ayni UA hem
 * isteklerde hem de ExtractorLink basliginda kullanilmalidir.
 */
object DiziMagJapier {

    const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    private fun sha256(input: String): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))

    private fun sha256Hex(input: String): String =
        sha256(input).joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray {
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            out[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return out
    }

    private fun aesDecrypt(b64: String, key: ByteArray, iv: ByteArray): String? = try {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        String(cipher.doFinal(Base64.decode(b64, Base64.DEFAULT)), Charsets.UTF_8)
    } catch (e: Exception) {
        Log.w("DiziMagJapier", "AES hatasi: ${e.message}")
        null
    }

    private fun randHex(len: Int): String {
        val chars = "0123456789abcdef"
        return buildString(len) {
            repeat(len) { append(chars[Random.nextInt(chars.length)]) }
        }
    }

    private fun hexXor(enc: String, key: String): String {
        val sb = StringBuilder(enc.length)
        for (i in enc.indices) {
            val a = enc[i].digitToInt(16)
            val b = key[i % key.length].digitToInt(16)
            sb.append((a xor b).toString(16))
        }
        return sb.toString()
    }

    /** Sayfadaki gizlenmis _cr degerini cozer. */
    internal fun parseChallenge(html: String): String? {
        val crIdx = html.indexOf("var _cr")
        if (crIdx < 0) return null
        val scriptStart = html.lastIndexOf("<script", crIdx).coerceAtLeast(0)
        val block = html.substring(scriptStart, crIdx + 40)

        val fromCharCode = Regex("""String\.fromCharCode\(([^)]{1,160})\)""").find(block)
        if (fromCharCode != null) {
            val arrays = Regex("""var\s+([A-Za-z_]\w*)\s*=\s*\[([0-9,\s]+)]""")
                .findAll(block)
                .associate { m ->
                    m.groupValues[1] to m.groupValues[2].split(',')
                        .mapNotNull { it.trim().toIntOrNull() }
                }
            val expr = fromCharCode.groupValues[1].trim()

            // arr1[i] ^ arr2[i % arr2.length]
            val xorMatch = Regex("^([A-Za-z_]\\w*)\\s*\\[[^\\]]+]\\s*\\^\\s*([A-Za-z_]\\w*)").find(expr)
            if (xorMatch != null) {
                val a = arrays[xorMatch.groupValues[1]]
                val b = arrays[xorMatch.groupValues[2]]
                if (a != null && b != null && a.isNotEmpty() && b.isNotEmpty()) {
                    return buildString(a.size) {
                        for (i in a.indices) {
                            append(((a[i] xor b[i % b.size]) and 0xFF).toChar())
                        }
                    }
                }
                return null
            }

            // arr[i] - OFFSET
            val subMatch = Regex("""^([A-Za-z_]\w*)\s*\[[^\]]+]\s*-\s*(\d+)""").find(expr)
            if (subMatch != null) {
                val a = arrays[subMatch.groupValues[1]]
                val offset = subMatch.groupValues[2].toIntOrNull()
                if (a != null && offset != null) {
                    return buildString(a.size) {
                        for (v in a) append((v - offset).toChar())
                    }
                }
                return null
            }

            return null
        }

        val atobMatches = Regex("""atob\('([^']*)'\)""").findAll(block).toList()
        if (atobMatches.isNotEmpty()) {
            return atobMatches.joinToString("") { m ->
                String(Base64.decode(m.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
            }
        }

        Regex("""var\s+[A-Za-z_]\w*\s*=\s*'([^']{8,80})'\s*;""")
            .find(block)?.let { return it.groupValues[1] }

        return null
    }

    suspend fun resolve(
        embedUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val embed = embedUrl.substringBefore('?')
        val html = try {
            app.get(
                embedUrl,
                headers = mapOf("User-Agent" to USER_AGENT, "Referer" to referer)
            ).text
        } catch (e: Exception) {
            Log.e("DiziMagJapier", "embed alinamadi: ${e.message}")
            return false
        }

        val cr = parseChallenge(html)
        if (cr == null || cr.length < 8) {
            Log.e("DiziMagJapier", "challenge cozulemedi")
            return false
        }

        val ept = Regex("""var _ept\s*=\s*'([0-9a-fA-F]+)'""").find(html)?.groupValues?.get(1)
            ?: return false
        val ws = Regex("""var _ws\s*=\s*'([0-9a-fA-F]+)'""").find(html)?.groupValues?.get(1)
            ?: return false

        val pt = hexXor(ept, cr)
        val wc = sha256Hex(ws + cr).take(16)
        val sig = randHex(64)
        val ref = Base64.encodeToString(embedUrl.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
            .take(Random.nextInt(20, 41))
        val now = System.currentTimeMillis()

        val body = JSONObject().apply {
            put("_sig", sig)
            put("_nonce", randHex(32))
            put("_ts2", now - Random.nextInt(100, 501))
            put("_v", "2.4.17")
            put("_sid", randHex(24))
            put("_ref", ref)
            put("_enc", "x25519")
            put("_mode", "ofb")
            put("_iter", 2500)
            put("_salt", randHex(16))
            put("_hmac", randHex(40))
            put("_pad", "none")
            put("_cmp", "none")
            put("_fmt", 2)
            put("_fl", 3)
            put("pt", pt)
            put("cr", cr)
            put("wc", wc)
            put("bs", JSONObject().apply {
                put("ts", now)
                put("sw", 1920)
                put("sh", 1080)
                put("plt", 1200)
                put("tz", -180)
                put("lang", "tr-TR")
                put("pl", "Win32")
                put("ct", 8)
                put("dm", 24)
                put("td", 0)
                put("cv", 1)
                put("wg", 1)
            })
            put("_x", sig.take(8))
        }

        val origin = Regex("""^(https?://[^/]+)""").find(embed)?.groupValues?.get(1)
            ?: "https://japierdolevid.com"

        val raw = try {
            app.post(
                "$embed/stream",
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to embedUrl,
                    "Origin" to origin,
                    "Accept" to "application/json, text/plain, */*"
                ),
                requestBody = body.toString().toRequestBody("application/json".toMediaType())
            ).text
        } catch (e: Exception) {
            Log.e("DiziMagJapier", "stream istegi basarisiz: ${e.message}")
            return false
        }

        val res = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.e("DiziMagJapier", "yanit JSON degil: ${raw.take(80)}")
            return false
        }

        val d = res.optString("d")
        if (d.isEmpty()) {
            Log.e("DiziMagJapier", "d yok: ${raw.take(80)}")
            return false
        }

        val seed = res.optString("p1") + res.optString("p2") +
            res.optString("p3") + res.optString("p4")
        val key = sha256(seed + cr + res.optString("x"))
        val iv = res.optString("v")
        if (iv.length < 32) return false
        val payload = aesDecrypt(d, key, hexToBytes(iv)) ?: return false

        val data = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return false
        }

        val inner = when {
            data.has("encrypted_player_data") -> {
                val epd = data.getJSONObject("encrypted_player_data")
                val innerKeyHex = epd.optString("k1") + epd.optString("k2") +
                    epd.optString("k3") + epd.optString("k4")
                val innerIvHex = epd.optString("iv")
                if (innerKeyHex.length != 32 || innerIvHex.length != 32) return false
                aesDecrypt(
                    epd.optString("ct"),
                    hexToBytes(innerKeyHex),
                    hexToBytes(innerIvHex)
                )
            }
            data.has("processed_template") -> data.optString("processed_template")
            else -> null
        } ?: return false

        val html2 = inner.replace("\\/", "/")
        val streams = Regex("""https?://[^"'\s<>\\]+\.m3u8[^"'\s<>\\]*""")
            .findAll(html2).map { it.value }.distinct().toList()

        if (streams.isEmpty()) {
            Log.e("DiziMagJapier", "m3u8 bulunamadi")
            return false
        }

        Regex("""https?://[^"'\s<>\\]+\.vtt[^"'\s<>\\]*""").findAll(html2)
            .map { it.value }.distinct().forEach { sub ->
                subtitleCallback.invoke(SubtitleFile("Türkçe", sub))
            }

        streams.forEach { url ->
            callback.invoke(
                newExtractorLink(
                    source = "DiziMag",
                    name = "DiziMag",
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
        }
        return true
    }
}
