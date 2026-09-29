@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.btcozum.btvault

import android.util.Log
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

class Dizilla : MainAPI() {
    override var mainUrl              = "https://dizilla.now"
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
        "${mainUrl}/tum-bolumler"          to "Altyazılı Bölümler",
        "${mainUrl}/dublaj-bolumler"       to "Dublaj Bölümler",
        "${mainUrl}/dizi-turu/aile"        to "Aile",
        "${mainUrl}/dizi-turu/aksiyon"     to "Aksiyon",
        "${mainUrl}/dizi-turu/bilim-kurgu" to "Bilim Kurgu",
        "${mainUrl}/dizi-turu/romantik"    to "Romantik",
        "${mainUrl}/dizi-turu/komedi"      to "Komedi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
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
        val epName = this.selectFirst("div.opacity-80")!!.text().replace(". Sezon ", "x").replace(". Bölüm", "")
        val title  = "$name - $epName"

        val epDoc     = app.get(this.attr("href")).document
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
                val doc = app.get(page).document
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
        val document = app.get(url).document

        val title       = document.selectFirst("div.page-top h1")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()?.removeSuffix(" izle")?.trim()
            ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.page-top img")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.page-top img")?.attr("data-src"))
            ?: fixUrlNull(document.selectFirst("img[src*=macellan]")?.attr("src"))
        val year        = document.selectXpath("//span[text()='Yayın tarihi']//following-sibling::span").text().trim().split(" ").last().toIntOrNull()
        val description = document.selectFirst("div.mv-det-p")?.text()?.trim()
            ?: document.selectFirst("div.w-full div.text-base")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
        val tags        = document.select("[href*='dizi-turu'], [href*='/tur/']").map { it.text() }.filter { it.isNotBlank() }.distinct()
        val jsonLd      = document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }
        val rating      = (document.selectFirst("a[href*='imdb.com'] span")?.text()?.trim()?.toRatingInt()
            ?: Regex("\"ratingValue\"\\s*:\\s*\"?([0-9.]+)").find(jsonLd)?.groupValues?.get(1)?.toFloatOrNull()?.let {
                (it * 10).toInt()
            })
        // NOTE: the old selector "div.gap-3 span.text-sm"[1] threw IndexOutOfBoundsException
        // when the page has no such element (next.js markup changed) - keep it crash free
        val duration    = document.select("div.gap-3 span.text-sm").getOrNull(1)?.text()
            ?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
            ?: document.select("div.gap-3 span.text-sm").firstNotNullOfOrNull { el ->
                Regex("(\\d+)\\s*dk").find(el.text())?.value?.let { Regex("(\\d+)").find(it)?.value?.toIntOrNull() }
            }
        val actors      = document.select("[href*='oyuncu']").map {
            Actor(it.text())
        }
        val epPosterUrl = fixUrlNull(document.selectFirst("img[src*=images.macellan]")?.attr("src"))
            ?: fixUrlNull(document.selectFirst("div.page-top img")?.attr("src"))

        val episodeList = mutableListOf<Episode>()
        document.selectXpath("//div[contains(@class, 'gap-2')]/a[contains(@href, '-sezon')]").forEach {
            val epDoc = app.get(fixUrlNull(it.attr("href")) ?: return@forEach).document
        
            epDoc.select("div.episodes div.cursor-pointer").forEach ep@ { episodeElement ->
                val epName        = episodeElement.select("a").last()?.text()?.trim() ?: return@ep
                val epHref        = fixUrlNull(episodeElement.selectFirst("a.opacity-60")?.attr("href")) ?: return@ep
                val epDescription = episodeElement.selectFirst("span.t-content")?.text()?.trim()
                val epPoster      = fixUrlNull(epDoc.selectFirst("img[src*=images.macellan]")?.attr("src")) ?: epPosterUrl
                val epEpisode     = episodeElement.selectFirst("a.opacity-60")?.text()?.toIntOrNull()
        
                val parentDiv   = episodeElement.parent()
                val seasonClass = parentDiv?.className()?.split(" ")?.find { className -> className.startsWith("szn") }
                val epSeason    = seasonClass?.substringAfter("szn")?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = epSeason
                    this.episode = epEpisode
                    this.description = epDescription
                    this.posterUrl = epPoster
                })
            }
        
            epDoc.select("div.dub-episodes div.cursor-pointer").forEach epDub@ { dubEpisodeElement ->
                val epName        = dubEpisodeElement.select("a").last()?.text()?.trim() ?: return@epDub
                val epHref        = fixUrlNull(dubEpisodeElement.selectFirst("a.opacity-60")?.attr("href")) ?: return@epDub
                val epDescription = dubEpisodeElement.selectFirst("span.t-content")?.text()?.trim()
                val epPoster      = fixUrlNull(epDoc.selectFirst("img[src*=images.macellan]")?.attr("src")) ?: epPosterUrl
                val epEpisode     = dubEpisodeElement.selectFirst("a.opacity-60")?.text()?.toIntOrNull()
        
                val parentDiv   = dubEpisodeElement.parent()
                val seasonClass = parentDiv?.className()?.split(" ")?.find { className -> className.startsWith("szn") }
                val epSeason    = seasonClass?.substringAfter("szn")?.toIntOrNull()

                episodeList.add(newEpisode(epHref) {
                    this.name = "$epName Dublaj"
                    this.season = epSeason
                    this.episode = epEpisode
                    this.description = epDescription
                    this.posterUrl = epPoster
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            addActors(actors)
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        Log.d("DZL", "data » $data")
        val document = app.get(data).document
        val iframes  = mutableSetOf<String>()

        val alternatifler = document.select("a[href*='player']")
        // iframe src'si "//four.pichive.online/..." seklinde (protokol-relative) geliyor
        fun iframeUrl(doc: org.jsoup.nodes.Document): String? {
            val raw = doc.selectFirst("div#playerLsDizilla iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*=pichive]")?.attr("src")
                ?: return null
            val abs = when {
                raw.startsWith("//") -> "https:$raw"
                raw.startsWith("http") -> raw
                else -> "${mainUrl}/$raw"
            }
            return fixUrlNull(abs)
        }

        if (alternatifler.isEmpty()) {
            val iframe = iframeUrl(document) ?: return false

            Log.d("DZL", "iframe » $iframe")

            loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
        } else {
            alternatifler.forEach {
                val playerDoc = app.get(fixUrlNull(it.attr("href")) ?: return@forEach).document
                val iframe    = iframeUrl(playerDoc) ?: return@forEach

                if (iframe in iframes) { return@forEach }
                iframes.add(iframe)

                Log.d("DZL", "iframe » $iframe")

                loadExtractor(iframe, "${mainUrl}/", subtitleCallback, callback)
            }
        }

        return true
    }
}
