@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.btcozum.btvault

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class RapidVid : ExtractorApi() {
    override val name            = "RapidVid"
    override val mainUrl         = "https://rapidvid.org"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val extRef   = referer ?: ""
        val videoReq = app.get(url, referer = extRef).text

        // Yeni oynatici: window._p8='...' -> reverse + iki kat base64 + karakter kaydirma
        val p8Payload  = Regex("""window\._p8\s*=\s*'([^']+)'""").find(videoReq)?.groupValues?.get(1)
        val p8Json     = p8Payload?.let { decodeP8(it) }
        val decoded    = p8Json?.let { Regex(""""cm"\s*:\s*"([^"]+)"""").find(it)?.groupValues?.get(1)?.replace("\\/", "/") }
            ?: decodeLegacy(videoReq)

        if (decoded.isNullOrBlank()) throw ErrorLoadingException("File not found")

        // Altyazilar: p8 JSON icindeki "ct" listesi veya eski captions bloklari
        val subUrls  = mutableSetOf<String>()
        val subSource = videoReq + "\n" + (p8Json ?: "")
        Regex(""""file"\s*:\s*"([^"]+\.vtt)",\s*"label"\s*:\s*"([^"]+)"""").findAll(subSource).forEach {
            val (subUrl, subLang) = it.destructured
            if (subUrls.add(subUrl)) {
                subtitleCallback.invoke(
                    SubtitleFile(
                        lang = unescapeUnicode(subLang).trim(),
                        url  = fixUrl(subUrl.replace("\\", ""))
                    )
                )
            }
        }

        Log.d("Kekik_${this.name}", "decoded » $decoded")

        callback.invoke(
            newExtractorLink(
                source  = this.name,
                name    = this.name,
                url     = decoded,
                type    = ExtractorLinkType.M3U8
            ) {
                this.referer = url
                this.quality = Qualities.Unknown.value
            }
        )
    }

    /** _p8: reverse -> base64 -> "K9L" kaydirmasi -> base64 -> JSON dondurur */
    private fun decodeP8(payload: String): String? {
        return try {
            val step1 = b64Latin1(payload.reversed()) ?: return null
            val key   = "K9L"
            val shifted = buildString(step1.length) {
                step1.forEachIndexed { i, ch ->
                    append((ch.code - (key[i % 3].code % 5 + 1)).toChar())
                }
            }
            b64Latin1(shifted)
        } catch (e: Exception) {
            Log.e("Kekik_${this.name}", "p8 decode failed: ${e.message}")
            null
        }
    }

    /** Eski oynatici: file:"..." veya eval(packer) varyanti */
    private fun decodeLegacy(videoReq: String): String? {
        Regex(""""file": "(.*)","""").find(videoReq)?.groupValues?.get(1)?.let { extractedValue ->
            if (!extractedValue.contains("\\x")) return extractedValue.replace("\\", "")
            val bytes = extractedValue.split("\\x").filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()
            return String(bytes, Charsets.UTF_8)
        }

        val evalJWSsetup = Regex("""\};\s*(eval\(function[\s\S]*?)var played = \d+;""").find(videoReq)?.groupValues?.get(1)
            ?: return null
        @Suppress("LocalVariableName")
        val JWSsetup = getAndUnpack(getAndUnpack(evalJWSsetup)).replace("\\\\", "\\")
        val extracted = Regex(""""file":"(.*)","label"""").find(JWSsetup)?.groupValues?.get(1)?.replace("\\\\x", "")
            ?: return null
        val bytes = extracted.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        return String(bytes, Charsets.UTF_8)
    }

    private fun b64Latin1(value: String): String? {
        val cleaned = value.filter { !it.isWhitespace() }
        if (cleaned.isEmpty()) return null
        val padded = cleaned + "=".repeat((4 - cleaned.length % 4) % 4)
        return try {
            String(Base64.decode(padded, Base64.DEFAULT), Charsets.ISO_8859_1)
        } catch (_: Throwable) {
            try {
                String(java.util.Base64.getDecoder().decode(padded), Charsets.ISO_8859_1)
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun unescapeUnicode(value: String): String =
        Regex("""\\u([0-9a-fA-F]{4})""").replace(value) { m ->
            m.groupValues[1].toInt(16).toChar().toString()
        }
}
