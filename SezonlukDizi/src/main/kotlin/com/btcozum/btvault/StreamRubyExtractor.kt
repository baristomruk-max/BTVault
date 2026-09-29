@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.getAndUnpack
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * StreamRuby / Rubyvidhub gomulu oynaticisi.
 * Sayfa `eval(function(p,a,c,k,e,d)...)` ile paketlenmis; acilanca
 * `sources:[{file:"https://...streamruby.net/hls2/.../master.m3u8?t=..."}]`
 * veriyor. CloudStream cekirdeginde bu host icin cozucu YOK.
 */
class StreamRubyExtractor : ExtractorApi() {
    override val name = "StreamRuby"
    override val mainUrl = "https://rubyvidhub.com"
    override val requiresReferer = true

    private val hosts = listOf("rubyvidhub.com", "streamruby.net", "streamruby.com", "mrluz.com")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = hosts.firstOrNull { url.contains(it) } ?: return
        val html = app.get(url, referer = referer ?: "https://sezonlukdizi.cc/").text

        // paketlenmis scripti ac, olmazsa sayfayi oldugu gibi dene
        val unpacked = try { getAndUnpack(html) } catch (_: Throwable) { html }

        val stream = Regex("""sources\s*:\s*\[\s*\{\s*file\s*:\s*["']([^"']+)["']""")
            .find(unpacked)?.groupValues?.get(1)
            ?: Regex("""(https?://[^"'\s]+streamruby[^"'\s]*?\.m3u8[^"'\s]*)""")
                .find(unpacked)?.groupValues?.get(1)
            ?: return

        callback(
            newExtractorLink(
                source = name,
                name = "$name HLS",
                url = stream,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://$host/"
                this.quality = Qualities.Unknown.value
            }
        )

        // altyazi varsa (nadiren)
        Regex("""["']?file["']?\s*:\s*["'](https?://[^"']+\.vtt)["']""").findAll(html).forEach { m ->
            subtitleCallback(SubtitleFile(lang = "Turkce", url = m.groupValues[1].replace("\\/", "/")))
        }
    }
}
