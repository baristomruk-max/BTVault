@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")

package com.btcozum.btvault

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors

/**
 * JetFilmizle 25.09.2026 - site tamamen yenilendi (WordPress + Bootstrap):
 *   arama  : GET /arama?q=<query>
 *   liste  : GET /filmler?page=N   (film-card)
 *   detay  : /film/<slug>  -> h1.film-title + #active-player data-* alanlari
 *   oynatici: POST /jetplayer (film_id, source_index, player_type) -> <iframe>
 *   videopark iframe'i WORKER_BASE + VIDEO_ID ile /v/<id>/info (kaliteler) kullanir.
 * Eski jetfilmizle.io adresi artik yalnizca tanitim sayfasi; filmara.php 405 donuyor.
 */
class JetFilmizle : MainAPI() {
    override var mainUrl              = "https://jetfilmizle.now"
    override var name                 = "JetFilmizle"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override var supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler?page="           to "Son Filmler",
        "${mainUrl}/turkce-dublaj?page="     to "Türkçe Dublaj",
        "${mainUrl}/turkce-altyazili?page="  to "Türkçe Altyazılı",
        "${mainUrl}/orijinal-filmler?page="  to "Orijinal Filmler",
        "${mainUrl}/yerli-filmler?page="     to "Yerli Filmler",
        "${mainUrl}/diziler?page="           to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.film-card").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val wrapper = this.selectFirst("div.film-card-wrapper > a[href]") ?: this.selectFirst("a[href]")
        val href    = fixUrlNull(wrapper?.attr("href")) ?: return null
        if (!href.contains("/film/") && !href.contains("/dizi/")) return null

        var title = this.selectFirst("h3.film-title-line a")?.text()?.trim()
            ?: this.selectFirst("a[title]")?.attr("title")?.trim()
            ?: return null
        title = title.substringBefore(" Full").substringBefore(" İzle").trim()
        if (title.isBlank()) return null

        val poster = fixUrlNull(this.selectFirst("div.film-poster img")?.attr("src"))
            ?: fixUrlNull(this.selectFirst("img")?.attr("src"))

        val type = if (href.contains("/dizi/")) TvType.TvSeries else TvType.Movie
        return newMovieSearchResponse(title, href, type) { this.posterUrl = poster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/arama?q=${query.replace(" ", "+")}").document

        return document.select("div.film-card").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val player = document.selectFirst("#active-player")
        val title  = player?.attr("data-film-title")?.trim()?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1.film-title")?.text()?.substringBefore("(")?.trim()
            ?: return null

        val jsonLd = document.select("script[type=application/ld+json]").joinToString("\n") { it.data() }

        val poster = fixUrlNull(player?.attr("data-film-poster"))?.let { normalize(it) }
            ?: fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val year = player?.attr("data-film-year")?.toIntOrNull()
            ?: Regex("\"datePublished\"\\s*:\\s*\"(\\d{4})\"").find(jsonLd)?.groupValues?.get(1)?.toIntOrNull()
        val duration = player?.attr("data-film-duration")?.toIntOrNull()
            ?: Regex("\"duration\"\\s*:\\s*\"PT(\\d+)M\"").find(jsonLd)?.groupValues?.get(1)?.toIntOrNull()
        val rating = player?.attr("data-film-rating")?.toDoubleOrNull()?.let { (it * 10).toInt() }
            ?: Regex("\"ratingValue\"\\s*:\\s*\"?([0-9.]+)").find(jsonLd)?.groupValues?.get(1)
                ?.toDoubleOrNull()?.let { (it * 10).toInt() }

        val description = document.selectFirst("div.film-description div.description-text")?.text()?.trim()
            ?: document.selectFirst("div.film-description")?.text()?.trim()
            ?: fixUrlNull(document.selectFirst("meta[name=description]")?.attr("content"))

        val tags = Regex("\"genre\"\\s*:\\s*\\[([^\\]]*)\\]").find(jsonLd)?.groupValues?.get(1)
            ?.split(",")?.map { it.trim().trim('"') }?.filter { it.isNotBlank() } ?: listOf()

        val actors = Regex("\"actor\"\\s*:\\s*\\[(.*?)\\]\\s*,").find(jsonLd)?.groupValues?.get(1)
            ?.let { block -> Regex("\"name\"\\s*:\\s*\"([^\"]+)\"").findAll(block).map { it.groupValues[1] }.toList() }
            ?: listOf()

        val recommendations = document.select("div.similar-film-item div.film-card").mapNotNull { it.toSearchResult() }

        val isSeries = url.contains("/dizi/")

        if (isSeries) {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.rating    = rating
                this.duration  = duration
                this.recommendations = recommendations
                addActors(actors.map { Actor(it) })
            }
        }

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration
            this.recommendations = recommendations
            addActors(actors.map { Actor(it) })
        }
    }

    private fun normalize(url: String): String = when {
        url.startsWith("//") -> "https:$url"
        url.startsWith("/")  -> "$mainUrl$url"
        else                 -> url
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val filmId   = document.selectFirst("input[name=film_id]")?.attr("value")?.trim().orEmpty()
        if (filmId.isBlank()) return false

        // sayfadaki kaynak butonlari: data-source-index + data-player-type
        val buttons = document.select(".player-source-btn[data-source-index][data-player-type]")
            .distinctBy { it.attr("data-source-index") + "|" + it.attr("data-player-type") }

        var emitted = 0
        val seen    = HashSet<String>()

        for (button in buttons.take(8)) {
            val playerType = button.attr("data-player-type").ifBlank { "dublaj" }
            val sourceIdx  = button.attr("data-source-index")

            val playerCode = app.post(
                "${mainUrl}/jetplayer",
                referer = data,
                data    = mapOf(
                    "film_id"      to filmId,
                    "source_index" to sourceIdx,
                    "player_type"  to playerType
                ),
                headers = mapOf("X-Requested-With" to "XMLHttpRequest")
            ).text

            val embed = Regex("""iframe[^>]*\ssrc=['"]([^'"]+)['"]""").find(playerCode)?.groupValues?.get(1)?.trim()
                ?: continue
            if (!seen.add(embed)) continue
            if (embed.contains("fragman", true)) continue

            emitted += resolveEmbed(embed, data, subtitleCallback) { link -> callback(link) }
            if (emitted > 0) break
        }

        return emitted > 0
    }

    /** Bir oynatici embed adresini gercek dosya baglantilarina cevirir. */
    private suspend fun resolveEmbed(embed: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Int {
        // videopark.top -> worker uzerinden dogrudan mp4 kaliteleri
        if (embed.contains("videopark.top")) {
            val page = app.get(embed, referer = referer).text
            val worker = Regex("""WORKER_BASE\s*=\s*["']([^"']+)["']""").find(page)?.groupValues?.get(1)
                ?: return 0
            val videoId = Regex("""VIDEO_ID\s*=\s*(\d+)""").find(page)?.groupValues?.get(1) ?: return 0
            val base    = worker.trimEnd('/')

            val info     = app.get("$base/v/$videoId/info", referer = embed).text
            val qualities = Regex("\"qualities\"\\s*:\\s*\\[([\\d,\\s]*)\\]").find(info)?.groupValues?.get(1)
                ?.split(",")?.mapNotNull { it.trim().toIntOrNull() } ?: listOf()

            if (qualities.isEmpty()) {
                callback(newExtractorLink(source = "JetFilmizle", name = "JetFilmizle", url = "$base/v/$videoId", type = ExtractorLinkType.VIDEO) {
                    quality = Qualities.Unknown.value
                })
                return 1
            }

            qualities.forEach { q ->
                callback(newExtractorLink(source = "JetFilmizle", name = "${q}p", url = "$base/v/$videoId?q=$q", type = ExtractorLinkType.VIDEO) {
                    quality = q
                })
            }
            return qualities.size
        }

        // diger kaynaklar (VK, OkRu, Moly, SPlay ...) hazir extractor'larla acilir
        loadExtractor(embed, referer, subtitleCallback, callback)
        return 1
    }
}
