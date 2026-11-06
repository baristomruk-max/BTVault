@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * DDizi (ddizi.tel) - 2026 Kasim temasi.
 *
 * Site artik kartlarda dogrudan son bolum sayfasini (/izle/...) listeliyor.
 * Bolum sayfasindaki "dizi sayfasina git" baglantisindan dizi sayfasina
 * gecilip tum bolumler toplanir. Oynatma, bolum sayfasindaki
 * /player/oynat/<hash> iframe'inden alinir; player sayfasi Referer ister ve
 * sources: [{file:"https://...m3u8"}] seklinde dogrudan HLS adresi dondurur.
 */
class DDiziProvider : MainAPI() {
    override var mainUrl              = "https://www.ddizi.tel"
    override var name                 = "DDizi"
    override var lang                 = "tr"
    override val hasMainPage          = true
    override val hasQuickSearch       = true
    override val hasChromecastSupport = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    private val ua = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"

    override val mainPage = mainPageOf(
        "$mainUrl"                 to "Son Bölümler",
        "$mainUrl/yabanci-dizi-izle" to "Yabancı Diziler",
        "$mainUrl/eski.diziler"    to "Eski Diziler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data.trimEnd('/')}/sayfa/$page" else request.data
        val document = runCatching { app.get(url, headers = headers()).document }.getOrNull()
            ?: return newHomePageResponse(request.name, emptyList())
        val home = document.select("div.dizi-boxpost").mapNotNull { it.toCard() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home, hasNext = false)
    }

    private fun Element.toCard(): SearchResponse? {
        val link  = this.selectFirst("a[href]") ?: return null
        val href  = fixUrlNull(link.attr("href")) ?: return null
        if (!href.contains("/izle/") && !href.contains("/diziler/")) return null

        val img    = this.selectFirst("img")
        val poster = fixUrlNull(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))

        val title = link.attr("title").trim()
            .ifEmpty { img?.attr("alt")?.trim().orEmpty() }
            .ifEmpty { link.text().trim() }
            .removeSuffix(" izle")
            .trim()
        if (title.isEmpty()) return null

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()

        val out = LinkedHashMap<String, SearchResponse>()
        listOf("$mainUrl/yabanci-dizi-izle", mainUrl, "$mainUrl/eski.diziler").forEach { page ->
            runCatching {
                val doc = app.get(page, headers = headers()).document
                doc.select("div.dizi-boxpost").forEach { el ->
                    val card = el.toCard() ?: return@forEach
                    val name = card.name?.lowercase().orEmpty()
                    if (name.contains(q) || card.url.lowercase().contains(q)) out.putIfAbsent(card.url, card)
                }
            }
        }
        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        var document = app.get(url, headers = headers()).document

        // Bolum sayfasindan gelindiyse dizi sayfasina gec
        if (url.contains("/izle/")) {
            val seriesUrl = document.select("a[href*='/diziler/']")
                .mapNotNull { fixUrlNull(it.attr("href")) }
                .firstOrNull()
            if (!seriesUrl.isNullOrBlank() && seriesUrl != url) {
                document = runCatching { app.get(seriesUrl, headers = headers()).document }.getOrNull() ?: document
            }
        }

        val title = seriesTitle(document) ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
            ?: fixUrlNull(document.selectFirst("img.lazyload, img.img-back")?.attr("data-src"))
        val description = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }

        val episodes = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        document.select("a[href*='/izle/']").forEach { el ->
            val href = fixUrlNull(el.attr("href")) ?: return@forEach
            // /2, /3 parca baglantilari ayni bolumun devami
            val key = href.substringBefore("?").trimEnd('/').removeSuffix("/2").removeSuffix("/3")
            if (!seen.add(key)) return@forEach

            val epName = el.text().trim().ifEmpty { el.attr("title").trim() }
            if (epName.isEmpty()) return@forEach
            if (!epName.contains("Bölüm", ignoreCase = true) && !epName.contains("Sezon", ignoreCase = true)) return@forEach

            val season  = Regex("""(\d+)\.?\s*Sezon""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val episode = Regex("""(\d+)\.?\s*Bölüm""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(href) {
                this.name    = epName
                this.season  = season
                this.episode = episode
            })
        }

        if (episodes.isEmpty()) {
            // Tek bolum sayfasi ise film gibi davran
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot      = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers()).document
        val iframe = document.selectFirst("iframe[src]")?.attr("src")
            ?: document.selectFirst("iframe[data-src]")?.attr("data-src")
            ?: return false
        val playerUrl = fixUrl(iframe.trim())
        val playerHtml = runCatching { app.get(playerUrl, referer = data, headers = headers()).text }.getOrNull() ?: return false

        val found = LinkedHashSet<String>()
        Regex("""file\s*:\s*"([^"]+)"""").findAll(playerHtml).forEach { found.add(it.groupValues[1]) }
        Regex("""https?://[^"'\s]+\.m3u8[^"'\s]*""").findAll(playerHtml).forEach { found.add(it.value) }

        if (found.isEmpty()) return false
        found.forEach { stream ->
            val url = stream.replace("\\/", "/")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name   = name,
                    url    = url,
                    type   = if (url.contains(".m3u8", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = playerUrl
                    this.headers = mapOf("Referer" to playerUrl, "User-Agent" to ua)
                    this.quality = Qualities.Unknown.value
                }
            )
        }
        return true
    }

    private fun seriesTitle(document: Document): String? {
        val heading = document.selectFirst("h1")?.text()?.trim().orEmpty()
        if (heading.isNotEmpty()) {
            return heading.replace(Regex("""\s*\d+\.?\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "").trim()
        }
        val docTitle = document.title().trim()
        if (docTitle.isNotEmpty()) {
            val cleaned = docTitle.substringBefore(" Son Bölüm").substringBefore(" izle").trim()
            if (cleaned.isNotEmpty()) return cleaned
        }
        return document.selectFirst("a[href*='/izle/']")?.text()?.trim()
            ?.replace(Regex("""\s*\d+\.?\s*Bölüm.*$""", RegexOption.IGNORE_CASE), "")?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun headers(): Map<String, String> = mapOf(
        "User-Agent"      to ua,
        "Accept-Language" to "tr-TR,tr;q=0.9"
    )
}
