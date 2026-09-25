package com.btcozum.btvault

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class DiziBox : MainAPI() {
    override var mainUrl              = "https://www.dizibox.live"
    override var name                 = "DiziBox"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val dbxCookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198")

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())
            // Cloudflare asamasi 403/503 ile "Just a moment..." basligiyla gelir
            if (response.code == 403 || response.code == 503 || doc.text().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/ulke/turkiye"              to "Yerli",
        "${mainUrl}/dizi-arsivi/page/SAYFA/"   to "Dizi Arsivi",
        "${mainUrl}/tur/aksiyon/page/SAYFA"    to "Aksiyon",
        "${mainUrl}/tur/animasyon/page/SAYFA"  to "Animasyon",
        "${mainUrl}/tur/belgesel/page/SAYFA"   to "Belgesel",
        "${mainUrl}/tur/bilimkurgu/page/SAYFA" to "Bilimkurgu",
        "${mainUrl}/tur/dram/page/SAYFA"       to "Dram",
        "${mainUrl}/tur/fantastik/page/SAYFA"  to "Fantastik",
        "${mainUrl}/tur/gerilim/page/SAYFA"    to "Gerilim",
        "${mainUrl}/tur/gizem/page/SAYFA"      to "Gizem",
        "${mainUrl}/tur/komedi/page/SAYFA"     to "Komedi",
        "${mainUrl}/tur/korku/page/SAYFA"      to "Korku",
        "${mainUrl}/tur/macera/page/SAYFA"     to "Macera",
        "${mainUrl}/tur/romantik/page/SAYFA"   to "Romantik",
        "${mainUrl}/tur/savas/page/SAYFA"      to "Savas",
        "${mainUrl}/tur/suc/page/SAYFA"        to "Suc",
        "${mainUrl}/tur/tarih/page/SAYFA"      to "Tarih"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(url, cookies = dbxCookies, interceptor = interceptor, cacheTime = 60).document
        val selector = if (request.name == "Dizi Arsivi") "article.detailed-article" else "article.article-series-poster"
        val home     = document.select(selector).mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    // Kart uzerindeki ilk <a> afis linkidir (metni bos gelir), baslik h3 a / a[title] icindedir
    private fun Element.toMainPageResult(): SearchResponse? {
        val link = this.selectFirst("h3 a") ?: this.selectFirst("a[title]") ?: this.selectFirst("a") ?: return null

        var title = link.attr("title").trim()
        if (title.isEmpty()) title = link.text().trim()
        if (title.isEmpty()) title = this.selectFirst("a[title]")?.attr("title")?.trim() ?: ""
        if (title.isEmpty()) return null

        val href      = fixUrlNull(link.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") })
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q        = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("${mainUrl}/?s=$q", cookies = dbxCookies, interceptor = interceptor).document
        return document.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document    = app.get(url, cookies = dbxCookies, interceptor = interceptor).document
        val title       = (document.selectFirst("div.tv-overview h1 a") ?: document.selectFirst("div.tv-overview h1") ?: document.selectFirst("h1"))
            ?.text()?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.tv-overview figure img")?.attr("src"))
        val description = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']").map { it.text() }
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("div.tv-overview iframe")?.attr("src")

        val episodeList = mutableListOf<Episode>()
        val seasons = document.select("div#seasons-list a")
        if (seasons.isEmpty()) {
            // tek sezonlu dizilerde sezon listesi olmayabilir, bolumler sayfanin kendisindedir
            addEpisodes(document, episodeList)
        } else seasons.forEach {
            val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
            val epDoc = runCatching { app.get(epUrl, cookies = dbxCookies, interceptor = interceptor).document }.getOrNull() ?: return@forEach
            addEpisodes(epDoc, episodeList)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.plot      = description
            this.year      = year
            this.tags      = tags
            this.actors    = actors.map { ActorData(it) }
            if (trailer != null) this.trailers.add(TrailerData(trailer, null, false))
        }
    }

    // Bolum basligi ornek: "1.Sezon 1.Bolüm"
    private fun addEpisodes(document: Document, episodeList: MutableList<Episode>) {
        document.select("article.grid-box").forEach ep@{ epElem ->
            val link    = epElem.selectFirst("div.post-title a") ?: return@ep
            val epTitle = link.text().trim()
            val epHref  = fixUrlNull(link.attr("href"))
            if (epTitle.isEmpty() || epHref == null) return@ep

            val epSeason  = EP_SEASON.find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epEpisode = EP_EPISODE.find(epTitle)?.groupValues?.get(1)?.toIntOrNull()

            episodeList.add(newEpisode(epHref) {
                this.name    = epTitle
                this.season  = epSeason
                this.episode = epEpisode
            })
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = runCatching { app.get(data, cookies = dbxCookies, interceptor = interceptor).document }.getOrNull() ?: return false

        // ayni bolumun yansiticilari: /2/ ve /3/ (video-toolbar)
        val pages = mutableListOf(data)
        document.select("div.video-toolbar option[value]").forEach {
            val alt = fixUrlNull(it.attr("value"))
            if (alt != null && alt != data) pages.add(alt)
        }

        var found = false
        for (page in pages) {
            val pageDoc = if (page == data) document
                          else runCatching { app.get(page, cookies = dbxCookies, interceptor = interceptor).document }.getOrNull() ?: continue
            val player  = pageDoc.selectFirst("div#video-area iframe")?.attr("src")?.trim()
            if (player.isNullOrEmpty()) continue
            if (resolvePlayer(player, page, subtitleCallback, callback)) found = true
        }
        return found
    }

    // dizibox/player/*.php -> <iframe> (molystream / vidmoly / ok.ru)
    private suspend fun resolvePlayer(
        playerUrl: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var html = runCatching { app.get(playerUrl, referer = referer, cookies = dbxCookies, interceptor = interceptor).text }.getOrNull() ?: return false

        // moly.php: document.write(atob(unescape("%..")))
        OBFUSCATED.find(html)?.groupValues?.get(1)?.let { payload ->
            html = runCatching { unescape(payload).let { String(java.util.Base64.getDecoder().decode(it), Charsets.UTF_8) } }.getOrNull() ?: html
        }

        val tag     = PLAYER_IFRAME.find(html)?.value ?: return false
        val embed   = PLAYER_SRC.find(tag)?.groupValues?.get(1) ?: return false
        val embedUrl = fixUrlNull(embed) ?: return false

        // molystream: /embed/<id> sayfasi AES ile sifrelenir, HLS ucu /embed/sheila/<id>
        if (embedUrl.contains("molystream")) {
            val sheila   = embedUrl.replace("/embed/", "/embed/sheila/")
            // manifest tarayici User-Agent'i ister (java/okhttp UA ile 403/404 doner)
            val manifest = runCatching {
                app.get(
                    sheila,
                    headers     = mapOf("User-Agent" to USER_AGENT),
                    referer     = embedUrl,
                    interceptor = interceptor
                ).text
            }.getOrNull() ?: return false
            if (!manifest.contains("#EXTM3U")) return false

            val height = HLS_RESOLUTION.find(manifest)?.groupValues?.get(1)
            val label  = if (height == null) "" else "${height}p"
            callback(
                newExtractorLink(
                    source = name,
                    name   = if (label.isEmpty()) "DiziBox" else "DiziBox $label",
                    url    = sheila,
                    type   = ExtractorLinkType.M3U8
                ) {
                    // istek basligi tarayici gibi olmali, aksi halde 403
                    this.headers = mapOf(
                        "Referer"    to embedUrl,
                        "User-Agent" to USER_AGENT
                    )
                    this.quality = getQualityFromName(label)
                }
            )
            return true
        }

        return loadExtractor(embedUrl, playerUrl, subtitleCallback, callback)
    }

    private fun unescape(payload: String): String {
        val sb = StringBuilder(payload.length)
        var i = 0
        while (i < payload.length) {
            val c = payload[i]
            if (c == '%' && i + 2 < payload.length) {
                val hex = payload.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) { sb.append(hex.toChar()); i += 3; continue }
            }
            sb.append(c); i++
        }
        return sb.toString()
    }

    private companion object {
        val EP_SEASON     = Regex("""(\d+)\.?\s*Sezon""")
        val EP_EPISODE    = Regex("""(\d+)\.?\s*B[öo]l[üu]m""")
        val OBFUSCATED    = Regex("""atob\(\s*unescape\(\s*"([^"]+)"\s*\)\s*\)""")
        val PLAYER_IFRAME = Regex("<iframe[^>]*?\\s+src=\"([^\"]+)\"")
        val PLAYER_SRC    = Regex("\\ssrc=\"([^\"]+)\"")
        val HLS_RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")

        val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"
    }
}
