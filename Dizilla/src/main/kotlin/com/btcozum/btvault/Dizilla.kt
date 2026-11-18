@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araÃ§ @keyiflerolsun tarafÄ±ndan | @KekikAkademi iÃ§in yazÄ±lmÄ±ÅŸtÄ±r.

package com.btcozum.btvault

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Dizilla : MainAPI() {
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller): Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val response = chain.proceed(request)
            if (response.body?.string()?.contains("Just a moment") == true) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }
    override var mainUrl              = "https://dizilla.la"
    override var name                 = "Dizilla"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries)

    // ! CloudFlare bypass
    override var sequentialMainPage = true        // * https://recloudstream.github.io/dokka/-cloudstream/com.lagradost.cloudstream3/-main-a-p-i/index.html#-2049735995%2FProperties%2F101969414
    // override var sequentialMainPageDelay       = 250L // ? 0.25 saniye
    // override var sequentialMainPageScrollDelay = 250L // ? 0.25 saniye

    override val mainPage = mainPageOf(
        "${mainUrl}/tum-bolumler"          to "AltyazÄ±lÄ± BÃ¶lÃ¼mler",
        "${mainUrl}/dublaj-bolumler"       to "Dublaj BÃ¶lÃ¼mler",
        "${mainUrl}/dizi-turu/aile"        to "Aile",
        "${mainUrl}/dizi-turu/aksiyon"     to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/romantik"    to "Romantik",
        "${mainUrl}/dizi-turu/komedi"      to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, interceptor = interceptor).document
        // Yeni Tailwind temasi: kartlar <li class="hover-border-top"><a href="dizi/slug">
        val home = document.select("a[href*='dizi/']").mapNotNull { it.diziler() }
            .distinctBy { it.url }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.diziler(): SearchResponse? {
        val hrefRaw = attr("href")?.trim() ?: return null
        if (!hrefRaw.contains("dizi/")) return null
        val href = if (hrefRaw.startsWith("http")) hrefRaw
                    else "${mainUrl}/${hrefRaw.trimStart('/')}"
        val img       = selectFirst("img")
        val title     = img?.attr("alt")?.trim()?.removeSuffix(" izle")?.trim()?.takeIf { it.isNotEmpty() }
            ?: hrefRaw.substringAfterLast('/')
        val posterUrl = fixUrlNull(img?.attr("src"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private suspend fun Element.sonBolumler(): SearchResponse? {
        val name   = this.selectFirst("h2")?.text() ?: return null
        val epName = this.selectFirst("div.opacity-80")!!.text().replace(". Sezon ", "x").replace(". BÃ¶lÃ¼m", "")
        val title  = "$name - $epName"

        val epDoc     = app.get(this.attr("href"), interceptor = interceptor).document
        val href      = fixUrlNull(epDoc.selectFirst("a.relative")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(epDoc.selectFirst("img.imgt")?.attr("onerror")?.substringAfter("= '")?.substringBefore("';"))

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    private fun SearchItem.toSearchResponse(): SearchResponse? {
        return newTvSeriesSearchResponse(
            title ?: return null,
            "${mainUrl}/${slug}",
            TvType.TvSeries,
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Yeni temada sunucu tarafi arama yok (?s= ve /arama artik calismiyor),
        // arsiv sayfasindaki diziler istemci tarafinda filtreleniyor.
        val q = query.trim().lowercase()
        val out = LinkedHashMap<String, SearchResponse>()

        listOf("${mainUrl}/arsiv", "${mainUrl}/yabanci-dizi-izle", mainUrl).forEach { page ->
            runCatching {
                val doc = app.get(page, interceptor = interceptor).document
                doc.select("a[href*='dizi/']").forEach { el ->
                    val item = el.diziler() ?: return@forEach
                    val name = item.name?.lowercase() ?: ""
                    if (q.isNotEmpty() && !name.contains(q) && !item.url.lowercase().contains(q)) return@forEach
                    out.putIfAbsent(item.url, item)
                }
            }
        }

        return out.values.toList()
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document

        val title = document.selectFirst("div.page-top h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")?.trim()
            ?: return null
        val poster = fixUrlNull(document.selectFirst("div.page-top img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.page-top img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("img[src*=macellan]")?.attr("src"))
        val description = document.selectFirst("div.mv-det-p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val jsonLd = document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }
        val numberOfSeasons = Regex("\"numberOfSeasons\"\\s*:\\s*(\\d+)").find(jsonLd)?.groupValues?.get(1)?.toIntOrNull() ?: 1

        val slug = url.substringAfterLast('/').removePrefix("dizi/").trim('/')

        val episodes = mutableListOf<Episode>()
        for (season in 1..numberOfSeasons.coerceAtLeast(1)) {
            val seasonUrl = "${mainUrl}/${slug}-${season}-sezon"
            val seasonDoc = runCatching { app.get(seasonUrl, interceptor = interceptor).document }.getOrNull() ?: continue
            seasonDoc.select("article.ep4 a.ep4-open").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                val m = Regex("(\\d+)-sezon-(\\d+)-bolum").find(href)
                val epSeason = m?.groupValues?.get(1)?.toIntOrNull() ?: season
                val epNum = m?.groupValues?.get(2)?.toIntOrNull()
                val epName = a.selectFirst("span.ep4-t")?.text()?.trim()
                    ?: a.attr("aria-label")?.substringBefore("sırada")?.trim()
                episodes.add(newEpisode(href) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epNum
                })
            }
        }

        val unique = episodes.distinctBy { it.data }

        if (unique.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, unique) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data, interceptor = interceptor).text
        val pichiveUrls = Regex("https://[A-Za-z0-9.\\-]+/iframe\\.php\\?v=[A-Za-z0-9_\\-]+")
            .findAll(html).map { it.value }.distinct().toList()
        if (pichiveUrls.isEmpty()) return false

        var resolved = false
        for (u in pichiveUrls) {
            if (DizillaPichive.resolve(u, subtitleCallback, callback)) resolved = true
        }
        return resolved
    }
}