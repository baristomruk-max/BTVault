package com.btcozum.btvault

import android.util.Base64
import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.StringUtils.decodeUri
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

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(10 * 1024).string())
            if (response.code == 503 || doc.selectFirst("meta[name='cloudflare']") != null) {
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
        val document = app.get(url, cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198"), interceptor = interceptor, cacheTime = 60).document
        if (request.name == "Dizi Arsivi") {
            val home = document.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
            return newHomePageResponse(request.name, home)
        }
        val home = document.select("article.article-series-poster").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val title = this.selectFirst("a")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.let { img -> img.attr("data-src").takeIf { it.isNotBlank() } ?: img.attr("src") })
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}", cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198"), interceptor = interceptor).document
        return document.select("article.detailed-article").mapNotNull { it.toMainPageResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198"), interceptor = interceptor).document
        val title       = document.selectFirst("div.tv-overview h1 a")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.tv-overview figure img")?.attr("src"))
        val description = document.selectFirst("div.tv-story p")?.text()?.trim()
        val year        = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
        val tags        = document.select("a[href*='/tur/']").map { it.text() }
        val actors      = document.select("a[href*='/oyuncu/']").map { Actor(it.text()) }
        val trailer     = document.selectFirst("div.tv-overview iframe")?.attr("src")

        val episodeList = mutableListOf<Episode>()
        document.select("div#seasons-list a").forEach {
            val epUrl = fixUrlNull(it.attr("href")) ?: return@forEach
            val epDoc = app.get(epUrl, cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198"), interceptor = interceptor).document
            epDoc.select("article.grid-box").forEach ep@{ epElem ->
                val epTitle   = epElem.selectFirst("div.post-title a")?.text()?.trim() ?: return@ep
                val epHref    = fixUrlNull(epElem.selectFirst("div.post-title a")?.attr("href")) ?: return@ep
                val epSeason  = Regex("""(\d+). Sezon""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val epEpisode = Regex("""(\d+). Bolum""").find(epTitle)?.groupValues?.get(1)?.toIntOrNull()
                episodeList.add(newEpisode(epHref) { this.name = epTitle; this.season = epSeason; this.episode = epEpisode })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster; this.plot = description; this.year = year; this.tags = tags; addActors(actors); addTrailer(trailer)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198"), interceptor = interceptor).document
        val iframe = document.selectFirst("div#video-area iframe")?.attr("src") ?: return false
        loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        document.select("div.video-toolbar option[value]").forEach {
            val altLink = it.attr("value")
            val subDoc  = app.get(altLink, cookies = mapOf("LockUser" to "true", "isTrustedUser" to "true", "dbxu" to "1743289650198"), interceptor = interceptor).document
            val altIframe = subDoc.selectFirst("div#video-area iframe")?.attr("src") ?: return@forEach
            loadExtractor(altIframe, "${mainUrl}/", subtitleCallback, callback)
        }
        return true
    }
}
