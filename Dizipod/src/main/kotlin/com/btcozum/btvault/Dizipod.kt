@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.JsUnpacker
import com.lagradost.cloudstream3.utils.getQualityFromName
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element

class Dizipod : MainAPI() {
    override var mainUrl = "https://dizipod.com"
    override var name = "Dizipod"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/" to "Diziler",
        "${mainUrl}/filmler/" to "Filmler",
        "${mainUrl}/kore-dizileri/" to "Kore Dizileri",
        "${mainUrl}/cin-dizileri/" to "Çin Dizileri",
        "${mainUrl}/netflix-dizileri/" to "Netflix",
        "${mainUrl}/asya-dizileri/" to "Asya Dizileri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // /diziler/ ve /filmler/ disindaki kategori sayfalarinda sayfalama yok.
        val paged = request.data == "$mainUrl/diziler/" || request.data == "$mainUrl/filmler/"
        val url = if (page <= 1 || !paged) request.data else "${request.data.trimEnd('/')}/page/$page/"
        val items = app.get(url).document.select("div.item, article.tease-premium")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val isArticle = this.tagName() == "article"
        val anchor = if (isArticle) this.selectFirst("a.img-link") else this.parent()
        val href = anchor?.attr("href")?.takeIf { it.isNotBlank() } ?: return null
        val title = (if (isArticle) this.selectFirst("h3.title") else this.selectFirst("div.title"))
            ?.text()?.trim() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val year = (this.selectFirst("span.year") ?: this.selectFirst("div.year"))
            ?.text()?.trim()?.toIntOrNull()
        val score = (this.selectFirst("span.imdb-badge") ?: this.selectFirst("div.imdb .imdb-score"))
            ?.text()?.trim()
        val isMovie = href.contains("/film/")

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.year = year
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=${query.replace(" ", "+")}").document
        return document.select("div.item, article.tease-premium")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("h3.movie-hero__title")?.text()?.trim()
            ?: return null

        val jsonLd = document.select("script[type=application/ld+json]")
            .joinToString("\n") { it.data() }

        val posterUrl = fixUrlNull(
            document.selectFirst("img.movie-hero__poster")?.attr("src")
                ?: document.selectFirst("img[alt$='dizisi izle']")?.attr("src")
                ?: jsonLd.jsonField("image")
        )

        val ldDescription = jsonLd.jsonField("description")
        val longParagraph = document.select("p").map { it.text().trim() }
            .filter { it.length in 80..1500 }
            .maxByOrNull { it.length }
        val plot = if (!ldDescription.isNullOrBlank() && ldDescription.length > 120) {
            ldDescription
        } else {
            longParagraph ?: ldDescription
        }

        val year = document.selectFirst("a[href*='/yil/']")?.text()?.trim()?.toIntOrNull()
            ?: jsonLd.jsonField("datePublished")?.take(4)?.toIntOrNull()

        val score = document.selectFirst(".imdb-pill__score")?.text()?.trim()
            ?: document.selectFirst("div.imdb .imdb-score")?.text()?.trim()
            ?: jsonLd.jsonField("ratingValue")

        val tags = Regex("\"genre\"\\s*:\\s*\\[(.*?)]")
            .find(jsonLd)?.groupValues?.get(1)
            ?.let { genreBlock ->
                Regex("\"([^\"]+)\"").findAll(genreBlock).map { it.groupValues[1] }.toList()
            }
            ?.takeIf { it.isNotEmpty() }
            ?: document.select(".movie-hero__meta a[href*='/tur/']").map { it.text() }

        val duration = document.select(".movie-hero__meta-item")
            .map { it.text() }
            .firstOrNull { it.contains("dk") }
            ?.let { Regex("(\\d+)").find(it)?.groupValues?.get(1)?.toIntOrNull() }

        if (url.contains("/film/")) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.year = year
                this.plot = plot
                this.tags = tags
                this.duration = duration
                this.score = Score.from10(score)
            }
        }

        val seasons = document.select("a.session-tab")
            .mapNotNull { element ->
                val href = element.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val season = Regex("-([0-9]+)-sezon/").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@mapNotNull null
                season to href
            }
            .distinctBy { it.first }
            .sortedBy { it.first }

        val episodes = mutableListOf<Episode>()
        for ((season, seasonUrl) in seasons) {
            val seasonDocument = app.get(seasonUrl).document
            seasonDocument.select("div.episode-item a[href*='-bolum/']").forEach { anchor ->
                val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return@forEach
                val episode = Regex("-([0-9]+)-bolum/").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: return@forEach
                val episodeName = anchor.selectFirst("span.fw-bold")?.text()?.trim()
                    ?.ifBlank { null }
                    ?: "$season. Sezon $episode. Bölüm"
                episodes.add(
                    newEpisode(href) {
                        this.name = episodeName
                        this.season = season
                        this.episode = episode
                    }
                )
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
            this.posterUrl = posterUrl
            this.year = year
            this.plot = plot
            this.tags = tags
            this.score = Score.from10(score)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, referer = "$mainUrl/").document
        val postId = Regex("data-post-id=\"([0-9]+)\"").find(document.html())
            ?.groupValues?.get(1) ?: return false

        val playerResponse = app.get(
            "$mainUrl/wp/wp-admin/admin-ajax.php?action=get_episode_player&post_id=$postId",
            referer = data,
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        val embedUrl = Regex("https://player\\.dizipod\\.com/embed/[^\"'\\s<]+")
            .find(playerResponse.replace("\\/", "/"))?.value ?: return false

        val embedDocument = app.get(embedUrl, referer = "$mainUrl/").document
        val packedScript = embedDocument.select("script")
            .firstOrNull { it.data().contains("eval(function(p,a,c,k,e,d)") }
            ?.data() ?: return false

        val unpacked = JsUnpacker(packedScript).unpack()
            ?.replace("\\/", "/") ?: return false

        val file = Regex("\"file\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)
            ?.groupValues?.get(1)?.replace("\\/", "/") ?: return false
        val label = Regex("\"label\"\\s*:\\s*\"([^\"]+)\"").find(unpacked)?.groupValues?.get(1)

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name = this.name,
                url = file,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "$mainUrl/"
                this.quality = getQualityFromName(label)
            }
        )

        return true
    }

    private fun String.jsonField(name: String): String? {
        return Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"")
            .find(this)?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.replace("\\\"", "\"")
    }
}
