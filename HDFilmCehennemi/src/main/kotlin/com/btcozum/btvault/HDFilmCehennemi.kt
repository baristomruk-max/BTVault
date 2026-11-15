package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.land"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa",
        "${mainUrl}/film/" to "Filmler",
        "${mainUrl}/dizi/" to "Diziler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("article.item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.selectFirst("div.poster a")?.attr("href")) ?: return null
        val title = this.selectFirst("h2.flbaslik")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val isSeries = href.contains("/dizi/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/?s=${query}").document
        return document.select("article.item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1")?.text() ?: return null
        val poster = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content"))
        val description = document.selectFirst("meta[name=description]")?.attr("content")
        val isSeries = url.contains("/dizi/")
        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // nonce ve post_id parse et (daha spesifik regex)
        val nonce = Regex("""window\.videoAjax\s*=\s*\{[^}]*nonce:\s*'([^']+)'""").find(document.html())?.groupValues?.get(1)
            ?: Regex("""nonce:\s*'([^']+)'""").find(document.html())?.groupValues?.get(1)
            ?: return false
        // post_id: player container'dan al
        val postId = document.selectFirst("#playex, .player_sist, [data-post-id]")?.attr("data-post-id")
            ?: document.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?: return false
        val playerName = document.selectFirst("[data-player-name]")?.attr("data-player-name")?.takeIf { it.isNotBlank() } ?: "SetPlay"

        // AJAX ile iframe URL al
        val ajaxUrl = "${mainUrl}/wp-admin/admin-ajax.php"
        val response = app.post(
            ajaxUrl,
            data = mapOf(
                "action" to "get_video_url",
                "nonce" to nonce,
                "post_id" to postId,
                "player_name" to playerName,
                "part_key" to ""
            ),
            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
        ).text

        // response.data.url parse et (JSON escaped slashes unescape)
        val iframeUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(response)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false

        // iframe URL'sini döndür; eğer setplay.sbs ise iframe'i fetch et
        if (iframeUrl.contains("setplay.sbs") || iframeUrl.contains("player")) {
            try {
                val iframeDoc = app.get(iframeUrl, headers = mapOf("Referer" to data)).document
                val videoSrc = iframeDoc.select("video source, video").firstOrNull()?.attr("src")
                    ?: iframeDoc.select("source").firstOrNull()?.attr("src")
                if (videoSrc != null) {
                    callback.invoke(newExtractorLink(
                        source = name,
                        name = name,
                        url = fixUrl(videoSrc, mainUrl),
                        ExtractorLinkType.VIDEO
                    ) {
                        this.referer = iframeUrl
                    })
                    return true
                }
            } catch (_: Exception) { }
        }

        callback.invoke(newExtractorLink(
            source = name,
            name = name,
            url = iframeUrl,
            ExtractorLinkType.VIDEO
        ) {
            this.referer = data
        })
        return true
    }
}
