package com.btcozum.btvault

import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
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

class DiziGom : MainAPI() {
    override var mainUrl = "https://dizigom.news"
    override var name = "DiziGom"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon",
        "${mainUrl}/tur/animasyon/" to "Animasyon",
        "${mainUrl}/tur/belgesel/" to "Belgesel",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu",
        "${mainUrl}/tur/dram/" to "Dram",
        "${mainUrl}/tur/fantastik/" to "Fantastik",
        "${mainUrl}/tur/gerilim/" to "Gerilim",
        "${mainUrl}/tur/komedi/" to "Komedi",
        "${mainUrl}/tur/korku/" to "Korku",
        "${mainUrl}/tur/macera/" to "Macera",
        "${mainUrl}/tur/polisiye/" to "Polisiye",
        "${mainUrl}/tur/romantik/" to "Romantik",
        "${mainUrl}/tur/savas/" to "Savaş",
        "${mainUrl}/tur/suc/" to "Suç",
        "${mainUrl}/tur/tarih/" to "Tarih",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) "${request.data}?page=$page" else request.data
        val document = app.get(url).document
        val home = document.select("a[href][class~=group]")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(attr("href")) ?: return null
        if (href.contains("-bolum")) return null
        if (!href.contains("/diziler/") && !href.contains("/anime/") && !href.contains("/film/")) {
            return null
        }
        val title = this.selectFirst("h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")
                ?.replace(Regex("(?i)\\s*(Dizi|Film|Anime)\\s*izle\\s*$"), "")
                ?.trim()
            ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val score = this.selectFirst("span.badge-rating")?.text()?.trim()

        return if (href.contains("/film/")) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.score = Score.from10(score)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/ara?q=${query}").document
        return document.select("a[href][class~=group]")
            .mapNotNull { it.toMainPageResult() }
            .distinctBy { it.url }
    }

    private fun Element.toSearchResult(): SearchResponse? = toMainPageResult()

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val html = document.html()
        val slugUrl = url.substringBefore('?')

        val title = document.selectFirst("h1")?.text()?.trim() ?: return null
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: fixUrlNull(document.selectFirst("img[src*=posters]")?.attr("src"))
        val description = document.selectFirst("meta[property=og:description]")
            ?.attr("content")?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()
        val year = Regex("""\((\d{4})\)""").find(description.orEmpty())?.groupValues?.get(1)
            ?.toIntOrNull()
            ?: Regex(""""startDate"\s*:\s*"(\d{4})""").find(html)?.groupValues?.get(1)
                ?.toIntOrNull()
        val rating = document.selectFirst("span.badge-rating")?.text()?.trim()

        // Sezonlar: <a href="...?sezon=2#bolumler">
        val seasons = Regex("""\?sezon=(\d+)""").findAll(html)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .distinct()
            .sorted()
            .toList()

        val docs = if (seasons.isEmpty()) listOf(document)
        else seasons.map { s ->
            runCatching { app.get("$slugUrl?sezon=$s").document }.getOrElse { document }
        }

        val episodes = mutableListOf<Episode>()
        docs.forEach { doc ->
            doc.select("a[href*=/sezon-][href*=/bolum-]").forEach { a ->
                val href = fixUrlNull(a.attr("href")) ?: return@forEach
                val m = Regex("""/sezon-(\d+)/bolum-(\d+)""").find(href) ?: return@forEach
                val epName = a.selectFirst("p.mt-2")?.text()?.trim()
                    ?: a.selectFirst("p")?.text()?.trim()
                episodes.add(
                    newEpisode(href) {
                        this.name = epName
                        this.season = m.groupValues[1].toIntOrNull()
                        this.episode = m.groupValues[2].toIntOrNull()
                    }
                )
            }
        }
        val uniqueEpisodes = episodes
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

        if (uniqueEpisodes.isEmpty()) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.year = year
                this.score = Score.from10(rating)
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, uniqueEpisodes) {
            this.posterUrl = poster
            this.plot = description
            this.year = year
            this.score = Score.from10(rating)
        }
    }

    private fun Element.toRecommendationResult(): SearchResponse? {
        val title = this.selectFirst("a img")?.attr("alt") ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("a img")?.attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        Log.d("DZG", "data » ${data}")
        val document = app.get(data, referer = "$mainUrl/").document

        // 2026-11: yeni Pilavyer player - <div data-pv="slug"> + .../assets/js/core.js
        // core.js iframe'i runtime'da kurar, video adresi HTML kaynaginda yoktur.
        val pvSlug = document.selectFirst("div[data-pv]")?.attr("data-pv")?.trim()
        val coreSrc = document.select("script[src]").map { it.attr("src") }
            .firstOrNull { it.contains("/assets/js/core.js") || it.contains("/e/c.js") }
        if (!pvSlug.isNullOrEmpty() && coreSrc != null) {
            val base = when {
                coreSrc.contains("/assets/js/") -> coreSrc.substringBefore("/assets/js/")
                coreSrc.contains("/e/c.js") -> coreSrc.substringBefore("/e/c.js")
                else -> mainUrl
            }
            val playerHtml = app.get(
                "$base/assets/js/s.php?s=$pvSlug",
                referer = "$mainUrl/"
            ).text
            val playerJson = Regex("""window\.__PLAYER__\s*=\s*(\{.*?\})\s*;""", RegexOption.DOT_MATCHES_ALL)
                .find(playerHtml)?.groupValues?.get(1)
            if (playerJson != null) {
                val player = runCatching { org.json.JSONObject(playerJson) }.getOrNull()
                val stream = player?.optString("stream").orEmpty()
                if (stream.isNotBlank()) {
                    val subs = player?.optJSONArray("subs")
                    if (subs != null) {
                        for (i in 0 until subs.length()) {
                            val s = subs.optJSONObject(i) ?: continue
                            val src = s.optString("src").takeIf { it.isNotBlank() } ?: continue
                            subtitleCallback.invoke(
                                SubtitleFile(s.optString("label").ifBlank { "Türkçe" }, src)
                            )
                        }
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = this.name,
                            url = stream,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = com.lagradost.cloudstream3.utils.Qualities.Unknown.value
                            this.headers = mapOf(
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
                                "Referer" to "$mainUrl/"
                            )
                        }
                    )
                    return true
                }
            }
        }

        // Eski oynatici (yedek): div#content script JSON -> play.* -> eval unpack -> sources
        return try {
            val embed = document.selectFirst("div#content")?.selectFirst("script")?.data()
                ?: return false
            val contentJson: Gof = objectMapper.readValue(embed)
            val iframeDocument = app.get(
                contentJson.contentUrl.replace("https://", "https://play."),
                referer = "$mainUrl/"
            ).document
            val script =
                iframeDocument.select("script")
                    .find { it.data().contains("eval(function(p,a,c,k,e") }
                    ?.data()
                    ?: return false
            val unpack = JsUnpacker(script).unpack() ?: return false
            val sourceJ = unpack.substringAfter("sources:[")?.substringBefore("]")
                ?.replace("\\/", "/") ?: return false

            val source: Go = objectMapper.readValue(sourceJ)
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = this.name,
                    url = source.file,
                    ExtractorLinkType.M3U8
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = getQualityFromName(source.label)
                }
            )
            true
        } catch (e: Exception) {
            Log.e("DZG", "loadLinks hatasi: ${e.message}")
            false
        }
    }

    data class Go(
        @JsonProperty("file") val file: String,
        @JsonProperty("label") val label: String,
        @JsonProperty("type") val type: String
    )

    data class Gof(
        @JsonProperty("@context") val context: String,
        @JsonProperty("@type") val type: String,
        @JsonProperty("position") val position: String,
        @JsonProperty("name") val name: String,
        @JsonProperty("description") val description: String,
        @JsonProperty("thumbnailUrl") val thumbnailUrl: String,
        @JsonProperty("uploadDate") val uploadDate: String,
        @JsonProperty("duration") val duration: String,
        @JsonProperty("contentUrl") val contentUrl: String,
        @JsonProperty("timeRequired") val timeRequired: String,
        @JsonProperty("embedUrl") val embedUrl: String,
        @JsonProperty("interactionCount") val interactionCount: String,
    )
}