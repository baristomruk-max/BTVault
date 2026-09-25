package com.btcozum.btvault
// v1.0
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*


class FilmMakinesi : MainAPI() {
    override var mainUrl              = "https://filmmakinesi.to"
    override var name                 = "FilmMakinesi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override var supportedTypes       = setOf(TvType.Movie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:137.0) Gecko/20100101 Firefox/137.0"

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler-1/sayfa/"                                    to "Son Filmler",
        "${mainUrl}/film-izle/olmeden-izlenmesi-gerekenler-fm1/sayfa/"   to "Olmeden Izle",
        "${mainUrl}/tur/aksiyon-fmy54y/film/sayfa/"                      to "Aksiyon",
        "${mainUrl}/tur/bilim-kurgu-fm3/film/sayfa/"                     to "Bilim Kurgu",
        "${mainUrl}/tur/macera-fm1/film/sayfa/"                          to "Macera",
        "${mainUrl}/tur/komedi-fm1/film/sayfa/"                          to "Komedi",
        "${mainUrl}/tur/romantik-fm1/film/sayfa/"                        to "Romantik",
        "${mainUrl}/tur/belgesel/film/sayfa/"                            to "Belgesel",
        "${mainUrl}/tur/fantastik-fm1/film/sayfa/"                       to "Fantastik",
        "${mainUrl}/tur/polisiye/film/sayfa/"                            to "Polisiye Suc",
        "${mainUrl}/tur/korku-fm2/film/sayfa/"                           to "Korku",
        "${mainUrl}/tur/dram-fm1/film/sayfa/"                            to "Dram",
        "${mainUrl}/tur/gerilim-fm1/film/sayfa/"                         to "Gerilim",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val cleanedUrl = request.data.removeSuffix("/")
        val url = if (page > 1) {
            "$cleanedUrl/$page"
        } else {
            cleanedUrl.replace(Regex("/sayfa/?$"), "")
        }

        val document = app.get(url, headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to mainUrl
        )).document

        val home = document.select("div.film-list div.item-relative")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = selectFirst("a.item") ?: return null
        val title = aTag.attr("data-title").takeIf { it.isNotBlank() } ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val posterUrl = fixUrlNull(aTag.selectFirst("img")?.attr("src") ?: aTag.selectFirst("img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toRecommendResult(): SearchResponse? {
        val title     = this.select("a").last()?.text() ?: return null
        val href      = fixUrlNull(this.select("a").last()?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama/?s=${query}").document
        return document.select("div.film-list div.item-relative").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster      = fixUrlNull(document.selectFirst("[property='og:image']")?.attr("content"))
        val description = document.select("div.info-description p").last()?.text()?.trim()
        val tags        = document.select("div.type a").map { it.text().trim() }.filter { it.isNotEmpty() }

        val ogDescription = document.selectFirst("[property='og:description']")?.attr("content") ?: ""
        val year = Regex("""\b(19|20)\d{2}\b""").find(ogDescription)?.value?.toIntOrNull()
            ?: Regex("""-(?:19|20)\d{2}-""").find(url)?.value?.trim('-')?.toIntOrNull()

        val durationText = document.html()
        val duration = Regex(""""duration"\s*:\s*"PT(\d+)M"""").find(durationText)?.groupValues?.get(1)?.toIntOrNull()
            ?: Regex("""(\d+)\s*Dakika""", RegexOption.IGNORE_CASE).find(document.selectFirst("div.time")?.text() ?: "")?.groupValues?.get(1)?.toIntOrNull()
            ?: 0

        val recommendations = document.select("div.film-list div.item-relative").mapNotNull { it.toRecommendResult() }
        val actors = document.select("div.oyuncu-list a.cast").mapNotNull {
            val name = it.selectFirst("div.cast-name")?.text()?.trim() ?: return@mapNotNull null
            val pic  = fixUrlNull(it.selectFirst("img")?.attr("src") ?: it.selectFirst("img")?.attr("data-src"))
            Actor(name, pic)
        }

        val trailer = document.selectFirst("div.left a.trailer-button")?.attr("data-video_url")
            ?.substringAfter("embed/", "")?.let {
                if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null
            }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl       = poster
            this.year            = year
            this.plot            = description
            this.tags            = tags
            this.duration        = duration
            this.recommendations = recommendations
            this.actors = actors.map { ActorData(it) }
            if (trailer != null) this.trailers.add(TrailerData(trailer, null, false))
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val videoUrls = document.select(".video-parts a[data-video_url]").map { it.attr("data-video_url") }
        // the trailer iframe also carries a data-src, never feed it to the extractor
        val iframeUrls = document.select("iframe[data-src]")
            .map { it.attr("data-src") }
            .filter { it.isNotBlank() && !it.contains("youtube.com") }

        val allUrls = (videoUrls + iframeUrls).distinct()

        allUrls.forEach { url ->
            loadExtractor(url, "${mainUrl}/", subtitleCallback, callback)
        }
        return allUrls.isNotEmpty()
    }
}
