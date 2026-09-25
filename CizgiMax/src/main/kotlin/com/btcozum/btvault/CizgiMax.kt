@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu araç @keyiflerolsun tarafından | @KekikAkademi için yazılmıştır.

package com.btcozum.btvault

import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import java.net.URLEncoder

data class CZServer(
    val type: String? = null,
    val streamUrl: String? = null,
    val label: String? = null,
    val embedId: Int? = null,
    val videoId: String? = null,
)

data class CZStream(
    val id: Int? = null,
    val url: String? = null,
    val urls: List<CZQuality>? = null,
    val captions: List<CZCaption>? = null,
)

data class CZQuality(
    val label: String? = null,
    val url: String? = null,
)

data class CZCaption(
    val lang: String? = null,
    val name: String? = null,
    val url: String? = null,
)

class CizgiMax : MainAPI() {
    override var mainUrl              = "https://cizgimax.online"
    override var name                 = "CizgiMax"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Cartoon)

    override val mainPage = mainPageOf(
        "/arsiv/"        to "İçerik Arşivi",
        "/tur/aile/"     to "Aile",
        "/tur/aksiyon/"  to "Aksiyon",
        "/tur/komedi/"   to "Komedi",
        "/tur/korku/"    to "Korku",
        "/tur/bilim-kurgu/" to "Bilim Kurgu",
        "/tur/dram/"     to "Dram",
        "/tur/cocuk/"    to "Çocuk",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sep  = if (request.data.contains("?")) "&" else "?"
        val home = app.get("${mainUrl}${request.data}${sep}page=$page")
            .document
            .select("div.film-item")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val nameEl = this.selectFirst("a.film-name")
        val title  = nameEl?.text()?.trim().orEmpty()
            .ifBlank { this.attr("data-anime-name").trim() }
        val href   = nameEl?.attr("href")?.trim().orEmpty()
            .ifBlank { this.selectFirst("a.poster")?.attr("href")?.trim().orEmpty() }

        if (title.isBlank() || href.isNullOrBlank()) return null

        val img    = this.selectFirst("a.poster img")
        val poster = img?.let { it.attr("src").ifBlank { it.attr("data-src") } }

        return newTvSeriesSearchResponse(title, fixUrl(href), TvType.Cartoon) {
            this.posterUrl = fixUrlNull(poster)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        return app.get("${mainUrl}/ara/?q=$encoded")
            .document
            .select("div.film-list div.film-item")
            .mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val title       = document.selectFirst("div.anime-head-titles h1")?.text()?.trim()
            ?.ifBlank { null }
            ?: document.selectFirst("h1")?.text()?.trim()?.ifBlank { null }
            ?: return null
        val poster      = fixUrlNull(document.selectFirst("div.anime-poster img")?.attr("src"))
        val description = document.selectFirst("p.anime-desc")?.text()?.trim()
        val tags        = document.select("li.meta-genres strong a")
            .mapNotNull { it.text().trim().ifBlank { null } }
        val rating      = document.selectFirst("span.ep-score-value")?.text()?.trim()
            ?.replace(",", ".")?.toRatingInt()

        val episodes = document.select("div.ep-grid-numbers").flatMap { pane ->
            val season = pane.attr("data-season-pane").toIntOrNull()

            pane.select("a.ep-num-btn").mapNotNull { ep ->
                val href = fixUrlNull(ep.attr("href")) ?: return@mapNotNull null
                val num  = ep.selectFirst("span.ep-num-label")?.text()?.trim()?.toIntOrNull()
                val alt  = ep.attr("title").trim().ifBlank { ep.text().trim() }

                val epName = when {
                    num == null && alt.isBlank() -> return@mapNotNull null
                    num == null                 -> alt
                    alt.isBlank() || alt == "$num. Bölüm" -> "$num. Bölüm"
                    else                        -> "$num. Bölüm - $alt"
                }

                newEpisode(href) {
                    this.name    = epName
                    this.season  = season
                    this.episode = num
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Cartoon, episodes) {
            this.posterUrl = poster
            this.plot      = description
            this.tags      = tags
            this.rating    = rating
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val html = app.get(data).text

        // Sunucu listesi base64 gömülü: var servers = JSON.parse(atob("..."))
        val servers = Regex("""var\s+servers\s*=\s*JSON\.parse\(atob\("([^"]+)"\)\)""")
            .find(html)
            ?.groupValues?.get(1)
            ?.replace("\\/", "/")
            ?.let { tryParseJson<List<CZServer>>(it) }
            ?: emptyList()

        for (server in servers) {
            val streamUrl = server.streamUrl ?: continue
            val resolve   = runCatching {
                app.get(fixUrl(streamUrl), referer = data).parsedSafe<CZStream>()
            }.getOrNull()

            val qualities = resolve?.urls.orEmpty().filter { !it.url.isNullOrBlank() }
            for (quality in qualities) {
                val link = quality.url ?: continue
                callback.invoke(
                    newExtractorLink(
                        source = server.label ?: name,
                        name   = "${server.label ?: name} ${quality.label ?: ""}".trim(),
                        url    = fixUrl(link),
                        type   = ExtractorLinkType.M3U8
                    ) {
                        this.headers = mapOf("Referer" to "${mainUrl}/")
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            if (qualities.isNotEmpty()) continue

            val direct = resolve?.url
            if (!direct.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = server.label ?: name,
                        name   = server.label ?: name,
                        url    = fixUrl(direct),
                        type   = if (direct.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.headers = mapOf("Referer" to "${mainUrl}/")
                        this.quality = Qualities.Unknown.value
                    }
                )
                continue
            }

            // SibNet gömülü video adresi
            val videoId = server.videoId
            if (server.type.equals("sibnet", true) && !videoId.isNullOrBlank()) {
                loadExtractor(
                    "https://video.sibnet.ru/shares/video.php?video_id=$videoId",
                    "${mainUrl}/",
                    subtitleCallback,
                    callback
                )
            }
        }

        // Sayfada doğrudan iframe varsa klasik extractor zinciri
        for (frame in Jsoup.parse(html).select("iframe[src]")) {
            val src = frame.attr("src").trim()
            if (src.isBlank()) continue
            loadExtractor(fixUrl(src), "${mainUrl}/", subtitleCallback, callback)
        }

        Log.d("CZGM", "servers » ${servers.size} (data » $data)")
        return true
    }
}
