package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.util.Random

class HDFilmCehennemi : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi.land"
    override var name = "HDFilmCehennemi"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

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
        return document.select("article.item").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
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

    /** FNV-1a 32-bit hash (JavaScript Math.imul ile eşdeğer) */
    private fun fnv1a(input: String): String {
        var hash: Int = 2166136261
        for (b in input.toByteArray()) {
            hash = (hash xor b.toInt() and 0xFF)
            hash = (hash * 16777619).toInt()
        }
        return hash.toString(16).padStart(8, '0')
    }

    /** SPG koruması için X-Sp token üretir (n = spT) */
    private fun generateXSp(sp: String, spT: Long): String {
        val n = spT
        val rnd = Random()
        val r = (Math.floor(2176782336.0 * rnd.nextDouble())).toString(36)
        val hashInput = "$sp|$n|$r"
        val hash = fnv1a(hashInput)
        return "$n.$r.$hash"
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document

        // 1. nonce ve post_id parse et
        val nonce = Regex("""window\.videoAjax\s*=\s*\{[^}]*nonce:\s*'([^']+)'""").find(document.html())?.groupValues?.get(1)
            ?: Regex("""nonce:\s*'([^']+)'""").find(document.html())?.groupValues?.get(1)
            ?: return false
        val postId = document.selectFirst("#playex, .player_sist, [data-post-id]")?.attr("data-post-id")
            ?: document.selectFirst("[data-post-id]")?.attr("data-post-id")
            ?: return false
        val playerName = document.selectFirst("[data-player-name]")?.attr("data-player-name")?.takeIf { it.isNotBlank() } ?: "SetPlay"

        // 2. AJAX ile iframe URL al
        val ajaxUrl = "${mainUrl}/wp-admin/admin-ajax.php"
        val ajaxResponse = app.post(
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
        val iframeUrl = Regex(""""url"\s*:\s*"([^"]+)"""").find(ajaxResponse)?.groupValues?.get(1)?.replace("\\/", "/") ?: return false

        // 3. Jeton'ı iframe URL'den çıkar: https://setplay.sbs/player/?t=<jeton>
        val jetonMatch = Regex("""[?&]t=([^&]+)""").find(iframeUrl) ?: return false
        val jeton = jetonMatch.groupValues[1]

        // 4. fastplay.mom video sayfasını çek (FSP config + SPG_A)
        val fastplayUrl = "https://fastplay.mom/video/$jeton"
        val fastplayHtml = app.get(fastplayUrl, headers = mapOf("Referer" to iframeUrl)).text

        // FSP stream yolu
        val streamMatch = Regex("""stream:\s*"([^"]+)"""").find(fastplayHtml) ?: return false
        val streamPath = streamMatch.groupValues[1]

        // SPG_A config
        val spMatch = Regex("""sp":"([^"]+)"""").find(fastplayHtml) ?: return false
        val spTMatch = Regex("""spT":(\d+)""").find(fastplayHtml) ?: return false
        val sp = spMatch.groupValues[1]
        val spT = spTMatch.groupValues[1].toLong()

        // 5. X-Sp token üret (n = spT)
        val xSpToken = generateXSp(sp, spT)

        // 6. Manifest URL'sini M3U8 olarak döndür (X-Sp başlığı ile)
        val manifestUrl = "https://fastplay.mom$streamPath"

        callback.invoke(newExtractorLink(
            source = name,
            name = name,
            url = manifestUrl,
            ExtractorLinkType.M3U8
        ) {
            this.referer = fastplayUrl
            this.headers = mapOf("X-Sp" to xSpToken, "Referer" to fastplayUrl)
        })
        return true
    }
}
