package com.btcozum.btvault

import android.util.Base64
import org.jsoup.nodes.Element
import org.jsoup.nodes.Document
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.extractors.helper.AesHelper

class TurkAnime : MainAPI() {
    override var mainUrl              = "https://www.turkanime.tv"
    override var name                 = "TurkAnime"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "${mainUrl}/anime-turu/1/Aksiyon"        to "Aksiyon",
        "${mainUrl}/anime-turu/2/Macera"          to "Macera",
        "${mainUrl}/anime-turu/4/Komedi"          to "Komedi",
        "${mainUrl}/anime-turu/7/Gizem"           to "Gizem",
        "${mainUrl}/anime-turu/8/Dram"            to "Dram",
        "${mainUrl}/anime-turu/9/Ecchi"           to "Ecchi",
        "${mainUrl}/anime-turu/10/Fantastik"      to "Fantastik",
        "${mainUrl}/anime-turu/13/Tarihi"         to "Tarihi",
        "${mainUrl}/anime-turu/14/Korku"          to "Korku",
        "${mainUrl}/anime-turu/20/Parodi"         to "Parodi",
        "${mainUrl}/anime-turu/22/Romantizm"      to "Romantizm",
        "${mainUrl}/anime-turu/24/Bilim_Kurgu"    to "Bilim Kurgu",
        "${mainUrl}/anime-turu/27/Shounen"        to "Shounen",
        "${mainUrl}/anime-turu/30/Spor"           to "Spor"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val home     = document.select("div#orta-icerik div.panel").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title     = this.selectFirst("div.panel-title a")?.text() ?: return null
        val href      = fixUrlNull(this.selectFirst("div.panel-title a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        return newAnimeSearchResponse(title, href, TvType.Anime) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.post("${mainUrl}/arama", data = mapOf("arama" to query)).document
        return document.select("div#orta-icerik div.panel").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title       = document.selectFirst("div#detayPaylas div.panel-title")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div#detayPaylas div.imaj img")?.attr("data-src"))
        val description = document.selectFirst("div#detayPaylas p.ozet")?.text()?.trim()
        val year        = document.selectFirst("div#detayPaylas a[href*='yil/']")?.attr("href")?.substringAfter("yil/")?.toIntOrNull()
        val tags        = document.select("div#animedetay a[href*='anime-turu']").map { it.text() }

        val bolumlerUrl = fixUrlNull(document.selectFirst("a[data-url*='ajax/bolumler&animeId=']")?.attr("data-url")) ?: return null
        val bolumlerDoc = app.get(bolumlerUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest", "token" to document.selectFirst("meta[name='_token']")!!.attr("content")), cookies = mapOf("yasOnay" to "1")).document

        val episodes = bolumlerDoc.select("div#bolum-list li").mapNotNull {
            val epHref    = fixUrlNull(it.selectFirst("a[href*='/video/']")?.attr("href")) ?: return@mapNotNull null
            val epName    = it.selectFirst("span.bolumAdi")?.text()?.trim() ?: return@mapNotNull null
            val epTitle   = it.selectFirst("a[href*='/video/']")?.attr("title")?.trim() ?: return@mapNotNull null
            val epEpisode = Regex("""(\d+). Bolum""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            newEpisode(epHref) { this.name = epName; this.season = 1; this.episode = epEpisode }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
            this.posterUrl = poster; this.plot = description; this.year = year; this.tags = tags
        }
    }

    private fun iframe2AesLink(iframe: String): String? {
        var aesData = iframe.substringAfter("embed/#/url/").substringBefore("?status")
        aesData     = String(Base64.decode(aesData, Base64.DEFAULT))
        val aesKey  = "710^8A@3@>T2}#zN5xK?kR7KNKb@-A!LzYL5~M1qU0UfdWsZoBm4UUat%}ueUv6E--*hDPPbH7K2bp9^3o41hw,khL:}Kx8080@M"
        val aesLink = AesHelper.cryptoAESHandler(aesData, aesKey.toByteArray(), false)?.replace("\\", "") ?: throw ErrorLoadingException("failed to decrypt")
        return fixUrlNull(aesLink.replace("\"", ""))
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val iframeElement = document.selectFirst("iframe")
        val iframe = fixUrlNull(iframeElement?.attr("src"))

        if (iframe == null || iframe.contains("a-ads.com")) {
            val buttons = document.select("button[onclick*='IndexIcerik']")
            for (button in buttons) {
                val onclickAttr = button.attr("onclick")
                val subLink = onclickAttr.substringAfter("IndexIcerik('").substringBefore("'").takeIf { it.isNotBlank() }?.let { fixUrlNull(it) } ?: continue
                val subResponse = app.get(subLink, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                val subHtml = subResponse.body?.string().orEmpty()
                val subDoc = org.jsoup.Jsoup.parse(subHtml, subLink)
                val dataUrl = subDoc.selectFirst("div.artplayer-app")?.attr("data-url")
                if (dataUrl != null && dataUrl.endsWith(".m3u8")) {
                    callback(newExtractorLink(name = "TurkAnime", source = "TurkAnime", url = dataUrl, type = ExtractorLinkType.M3U8) { quality = Qualities.Unknown.value; headers = mapOf("Referer" to subLink) })
                    continue
                }
                val subFrame = fixUrlNull(subDoc.selectFirst("iframe")?.attr("src")) ?: continue
                loadExtractor(subFrame, "${mainUrl}/", subtitleCallback, callback)
            }
        } else {
            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        }
        return true
    }
}