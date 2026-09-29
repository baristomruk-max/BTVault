@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * FilmMakinesi (filmmakinesi.to) kendi oynaticisini kullaniyor.
 * Film sayfasindaki player iframe'leri:
 *  - https://closeload.filmmakinesi.to/video/embed/<id>/
 *  - https://rapid.filmmakinesi.to/embed-<id>/
 * Bu iki host icin CloudStream'in hazir extractor'i YOK, bu yuzden
 * m3u8 adresini sayfa icindeki obfuscated JS'ten kendimiz cozuyoruz.
 */
open class CloseLoadExtractor : ExtractorApi() {
    override val name = "CloseLoad"
    override val mainUrl = "https://closeload.filmmakinesi.to"
    override val requiresReferer = true

    companion object {
        private val ZIP_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"

        fun decodeBase64Latin1(str: String): String {
            // "=" dolgu karakterleri girdide zaten mevcut olabiliyor, once temizle
            val cleaned = str.filter { !it.isWhitespace() && it != '=' }
            if (cleaned.isEmpty()) return ""
            val padLen = (4 - cleaned.length % 4) % 4
            val padded = cleaned + "=".repeat(padLen)
            return try {
                String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
            } catch (_: Throwable) {
                try {
                    String(java.util.Base64.getDecoder().decode(padded), Charsets.ISO_8859_1)
                } catch (_: Throwable) {
                    ""
                }
            }
        }

        private fun rot13(input: String): String {
            val sb = StringBuilder(input.length)
            for (c in input) {
                sb.append(
                    when (c) {
                        in 'a'..'z' -> (((c - 'a' + 13) % 26) + 'a'.code).toChar()
                        in 'A'..'Z' -> (((c - 'A' + 13) % 26) + 'A'.code).toChar()
                        else -> c
                    }
                )
            }
            return sb.toString()
        }

        /** dc_hello("...") varyanti */
        fun dcHello(base64Input: String): String {
            val decodedOnce = decodeBase64Latin1(base64Input)
            val decodedTwice = decodeBase64Latin1(decodedOnce.reversed())
            return when {
                decodedTwice.contains("+") -> decodedTwice.substringAfterLast("+")
                decodedTwice.contains(" ") -> decodedTwice.substringAfterLast(" ")
                decodedTwice.contains("|") -> decodedTwice.substringAfterLast("|")
                else -> decodedTwice
            }
        }

        /** dc_<word>(["a","b","c"]) varyanti */
        fun dcNew(parts: List<String>): String {
            val value = parts.joinToString("")
            val decoded = decodeBase64Latin1(value).reversed()
            val rot = rot13(decoded)
            val unmix = StringBuilder(rot.length)
            for ((i, ch) in rot.withIndex()) {
                var code = ch.code
                code = (code - (399756995 % (i + 5)) + 256) % 256
                unmix.append(code.toChar())
            }
            return unmix.toString()
        }

        /**
         * Yeni varyant: varName = fn("payload".split("|")) + sources:[{file: varName}]
         */
        fun decodePayload(parts: List<String>): String {
            val list = parts.toMutableList()
            if (list.size < 3) return ""

            val aik4z = list.size - 2
            val ozqzr = aik4z % 7
            val ft0q0 = 8 + (aik4z % 5)
            if (ft0q0 >= list.size || ozqzr >= list.size) return ""

            val t2o = list.removeAt(ft0q0)
            val xtqz = list.removeAt(ozqzr)
            var uvhq = list.joinToString("")

            if (xtqz.length > 4096) {
                uvhq = decodeBase64Latin1(uvhq)
            }

            var xbgh9 = 0
            var hqbz = 0
            for (i in xtqz.indices) {
                val c = xtqz[i].code
                xbgh9 = (xbgh9 * 37 + c) % 241
                hqbz = (hqbz + ((c shl 1) xor i)) and 255
            }

            val mlr3o = (xbgh9 * 3 + hqbz) % 256
            val gjy = (hqbz % 11) + 5
            var euerq = ((hqbz * 251 + xbgh9) % 65519) + 1

            for (i in t2o.length - 1 downTo 0) {
                when (t2o[i]) {
                    '7' -> uvhq = decodeBase64Latin1(uvhq)
                    '3' -> uvhq = uvhq.reversed()
                    else -> {
                        val shift = (26 - ((t2o[i].code - 96) % 26)) % 26
                        val sb = StringBuilder(uvhq.length)
                        for (ch in uvhq) {
                            sb.append(
                                when {
                                    ch in 'A'..'Z' -> (((ch.code - 65 + shift) % 26) + 65).toChar()
                                    ch in 'a'..'z' -> (((ch.code - 97 + shift) % 26) + 97).toChar()
                                    else -> ch
                                }
                            )
                        }
                        uvhq = sb.toString()
                    }
                }
            }

            if (t2o.length > 2048) uvhq = uvhq.reversed()

            val len = uvhq.length
            if (len < 2) return ""

            val swaps = IntArray(len)
            for (i in len - 1 downTo 1) {
                euerq = (euerq * 97 + 41) % 65519
                swaps[i] = euerq % (i + 1)
            }

            val chars = uvhq.toCharArray()
            for (i in 1 until len) {
                val j = swaps[i]
                val tmp = chars[i]
                chars[i] = chars[j]
                chars[j] = tmp
            }
            uvhq = String(chars)

            var state = mlr3o
            val out = StringBuilder(uvhq.length)
            for (ch in uvhq) {
                val c = ch.code
                state = (state * 5 + gjy) % 256
                out.append((c xor state).toChar())
                state = (state + c) % 256
            }

            return out.toString()
        }

        private fun baseN(num: Int, radix: Int): String {
            if (num == 0) return "0"
            var n = num
            val sb = StringBuilder()
            while (n > 0) {
                sb.append(ZIP_ALPHABET[n % radix])
                n /= radix
            }
            return sb.reverse().toString()
        }

        /** Klasik eval(function(p,a,c,k,e,d)) seklindeki packer'i cozer. */
        private fun unpackPacker(script: String): String {
            val regex = Regex(
                """eval\(function\(p,a,c,k,e,d\).+?return\s+p\s*\}\s*\(\s*'((?:\\'|[^'])*)'\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*'((?:\\'|[^'])*)'\.split\('\|'\)""",
                RegexOption.DOT_MATCHES_ALL
            )
            val match = regex.find(script) ?: return script

            val payload = match.groupValues[1].replace("\\'", "'").replace("\\\\", "\\")
            val radix = match.groupValues[2].toIntOrNull() ?: return script
            val count = match.groupValues[3].toIntOrNull() ?: return script
            val symtab = match.groupValues[4].split('|')

            val lookup = HashMap<String, String>(count)
            for (i in 0 until count) {
                val key = baseN(i, radix)
                val sym = if (i < symtab.size && symtab[i].isNotEmpty()) symtab[i] else key
                lookup[key] = sym
            }

            return Regex("""\b\w+\b""").replace(payload) { m -> lookup[m.value] ?: m.value }
        }

        private fun looksLikeStream(value: String?): Boolean {
            if (value.isNullOrBlank()) return false
            val v = value.trim()
            if (!v.startsWith("http")) return false
            // FilmMakinesi HLS'i bazen "master.txt" olarak sunuyor
            return v.contains(".m3u8") || v.contains(".mp4") ||
                v.contains("/hls/") || v.contains("master") || v.contains(".txt")
        }

        /**
         * Sayfa icindeki stream adresini bulur. Bilinen obfuscation
         * varyantlarini sirayla dener, ilk basarili sonucu dondurur.
         */
        fun extractStreamUrl(html: String): String? {
            // 0) Dogrudan file: tanimlari
            Regex("""file:\s*["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
                .find(html)?.groupValues?.get(1)?.let { if (looksLikeStream(it)) return it }

            val unpacked = if (html.contains("eval(function(p,a,c,k,e,d)")) {
                html + "\n" + unpackPacker(html)
            } else {
                html
            }

            // 1) dc_hello("base64") varyanti
            Regex("""dc_hello\(\s*["']([^"']+)["']\s*\)""", RegexOption.IGNORE_CASE)
                .find(unpacked)?.groupValues?.get(1)?.let { encoded ->
                    val decoded = dcHello(encoded)
                    val url = if (decoded.startsWith("http")) decoded else "http$decoded"
                    if (looksLikeStream(url)) return url
                }

            // 2) dc_<word>([...]) varyanti
            Regex("""dc_\w+\(\s*\[(.*?)]\s*\)""", RegexOption.DOT_MATCHES_ALL)
                .find(unpacked)?.groupValues?.get(1)?.let { raw ->
                    val parts = Regex("""["']([^"']*)["']""").findAll(raw)
                        .map { it.groupValues[1] }.toList()
                    val decoded = dcNew(parts)
                    if (looksLikeStream(decoded)) return decoded
                }

            // 3) sources:[{file: <varName>}] + var <varName> = fn("payload".split("|"))
            val varName = Regex("""sources:\s*\[\{\s*file:\s*([a-zA-Z0-9_$]+)""")
                .findAll(unpacked).map { it.groupValues[1] }.firstOrNull { it != "atob" }

            if (varName != null) {
                val payloadRegex = Regex(
                    """var\s+""" + Regex.escape(varName) +
                        """\s*=\s*[a-zA-Z0-9_$]+\s*\(\s*["']([^"']+)["']\.split\(\s*["']([^"']+)["']\s*\)\s*\)"""
                )
                val m = payloadRegex.find(unpacked)
                if (m != null) {
                    // JS kacis karakterlerini (\/ gibi) geri coz
                    val payload = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                    val decoded = decodePayload(payload.split(m.groupValues[2]))
                    if (looksLikeStream(decoded)) return decoded
                }
            }

            // 4) Genel tarama: uzun payload + split("|") bloklari
            val generic = Regex(
                """var\s+[a-zA-Z0-9_$]+\s*=\s*[a-zA-Z0-9_$]+\s*\(\s*["']([^"']{100,})["']\.split\(\s*["']([|^*@#~])["']\s*\)\s*\)"""
            )
            for (m in generic.findAll(unpacked)) {
                val payload = m.groupValues[1].replace("\\/", "/").replace("\\\"", "\"")
                val decoded = decodePayload(payload.split(m.groupValues[2]))
                if (looksLikeStream(decoded)) return decoded
            }

            // 5) Cozulmus icerikte dogrudan m3u8 arayalim
            Regex("""(https?://[^"'\s\\]+\.m3u8[^"'\s\\]*)""")
                .find(unpacked)?.groupValues?.get(1)?.let { if (looksLikeStream(it)) return it }

            return null
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = Regex("""https?://[^/]+""").find(url)?.value ?: mainUrl
        val html = app.get(
            url,
            referer = referer ?: "https://filmmakinesi.to/",
            headers = mapOf(
                "User-Agent" to
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
            )
        ).text

        val streamUrl = extractStreamUrl(html)
        if (streamUrl.isNullOrBlank()) return

        // closeload her zaman HLS (jwplayer "type": "hls") yayinliyor
        val isM3u8 = !streamUrl.substringAfterLast('/').endsWith(".mp4")
        callback(
            newExtractorLink(
                source = name,
                name = "$name ${if (isM3u8) "HLS" else "Stream"}",
                url = streamUrl,
                type = if (isM3u8) ExtractorLinkType.M3U8 else INFER_TYPE
            ) {
                // CDN referer kontrolu yapiyor, olmazsa 404 donuyor
                this.referer = "$host/"
                this.quality = Qualities.Unknown.value
            }
        )

        // Altyazilar (varsa)
        Regex("""["']file["']\s*:\s*["'](https?:[^"']+\.vtt)["'][^}]*?["']label["']\s*:\s*["']([^"']+)["']""")
            .findAll(html).forEach { m ->
                subtitleCallback(
                    SubtitleFile(
                        lang = m.groupValues[2],
                        url = m.groupValues[1].replace("\\/", "/")
                    )
                )
            }
    }
}

/** rapid.filmmakinesi.to ayni oynaticinin kardesi, ayni cozucuyu kullaniyor. */
class RapidFMExtractor : CloseLoadExtractor() {
    override val name = "RapidFM"
    override val mainUrl = "https://rapid.filmmakinesi.to"
}
