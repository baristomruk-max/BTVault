package com.btcozum.btvault

import com.fasterxml.jackson.databind.ObjectMapper
import com.lagradost.cloudstream3.Actor
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
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.util.Base64

class HDFilmCehennemi2 : MainAPI() {
    override var mainUrl = "https://www.hdfilmcehennemi2.biz"
    override var name = "HDFilmCehennemi2"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/filmler/" to "Filmler",
        "${mainUrl}/dizi-izle/" to "Diziler",
        "${mainUrl}/tur/aksiyon-filmleri-izle/" to "Aksiyon",
        "${mainUrl}/tur/dram-filmleri/" to "Dram",
        "${mainUrl}/tur/komedi-filmleri/" to "Komedi",
        "${mainUrl}/tur/gerilim-filmleri/" to "Gerilim",
        "${mainUrl}/tur/korku-filmleri/" to "Korku",
        "${mainUrl}/tur/bilim-kurgu-filmleri-izle/" to "Bilim Kurgu",
        "${mainUrl}/tur/fantastik-filmleri/" to "Fantastik",
        "${mainUrl}/tur/macera-filmleri/" to "Macera",
        "${mainUrl}/tur/romantik-filmler/" to "Romantik",
        "${mainUrl}/tur/animasyon-film-izle/" to "Animasyon",
        "${mainUrl}/tur/suc-filmleri/" to "Su\u00e7",
        "${mainUrl}/tur/gizem-filmleri/" to "Gizem",
        "${mainUrl}/tur/aile-filmleri/" to "Aile",
        "${mainUrl}/tur/biyografi-filmleri/" to "Biyografi",
        "${mainUrl}/tur/tarih-filmleri/" to "Tarih",
        "${mainUrl}/tur/spor-filmleri/" to "Spor",
        "${mainUrl}/tur/western-filmleri/" to "Western",
        "${mainUrl}/tur/muzik-filmleri/" to "M\u00fczik",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}?page=$page").document
        val items = document.select("a.group/poster").mapNotNull { it.toMainPageResult() }
        return newHomePageResponse(request.name, items)
    }

    private fun Element.toMainPageResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        val title = this.selectFirst("h3")?.text() ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        val isSeries = href.contains("/dizi/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "${mainUrl}/api/search?q=${query.encodeUri()}",
            headers = mapOf(
                "X-Requested-With" to "XMLHttpRequest",
                "Accept" to "application/json"
            )
        )
        val body = response.text
        val mapper = ObjectMapper()
        val root = mapper.readValue(body, Map::class.java) as Map<String, Any>
        val data = root["data"] as? List<*> ?: return emptyList()
        return data.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val title = map["title"]?.toString() ?: return@mapNotNull null
            val slug = map["slug"]?.toString() ?: return@mapNotNull null
            val href = fixUrlNull("$mainUrl/$slug") ?: return@mapNotNull null
            val posterUrl = fixUrlNull(map["posterUrl"]?.toString())
            val type = map["contentableType"]?.toString() ?: ""
            val isSeries = type.contains("Series", ignoreCase = true)
            val year = map["releaseYear"]?.toString()?.toIntOrNull()
            val rating = map["imdbRating"]?.toString()
            return@mapNotNull if (isSeries) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.score = Score.from10(rating ?: "")
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                    this.year = year
                    this.score = Score.from10(rating ?: "")
                }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        // JSON-LD yap\u0131l\u0131r\u0131 verisi (film / dizi meta)
        val ldJson = document.selectFirst("script[type=\"application/ld+json\"]")?.data() ?: ""
        val mapper = ObjectMapper()
        val ld = mapper.readValue(ldJson, Map::class.java) as Map<String, Any>
        val title = ld["name"]?.toString() ?: ""
        val description = ld["description"]?.toString()
        val posterUrl = fixUrlNull(ld["image"]?.toString())
        val year = ld["dateCreated"]?.toString()?.substringBefore("-")?.toIntOrNull()
        val ratingObj = ld["aggregateRating"] as? Map<*, *>
        val rating = ratingObj?.get("ratingValue")?.toString() ?: ""
        val actors = mutableListOf<Actor>()
        (ld["actor"] as? List<*> ?: emptyList()).forEach { a ->
            val am = a as? Map<*, *> ?: return@forEach
            actors.add(Actor(name = am["name"]?.toString() ?: ""))
        }
        val tags = (ld["genre"] as? List<*> ?: emptyList()).mapNotNull { it.toString() }

        val isSeries = url.contains("/dizi/")
        val episodes = mutableListOf<Episode>()
        if (isSeries) {
            document.select("a[href]").forEach { el ->
                val epHref = fixUrlNull(el.attr("href")) ?: return@forEach
                if (!epHref.contains("/sezon-") || !epHref.contains("/bolum-")) return@forEach
                val epName = el.text()
                val m = Regex("""sezon-(\d+)/bolum-(\d+)""").find(epHref)
                val season = m?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val episode = m?.groupValues?.get(2)?.toIntOrNull() ?: 1
                episodes.add(newEpisode(epHref) {
                    this.name = epName
                    this.season = season
                    this.episode = episode
                })
            }
        }

        if (!isSeries) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        } else {
            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = posterUrl
                this.plot = description
                this.year = year
                this.tags = tags
                this.score = Score.from10(rating)
                addActors(actors)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val element = document.select("[data-video-player]").first()
        val xData = element?.attr("x-data") ?: return true

        // videoPlayerData(JSON.parse('{\u0022dual\u0022:[...]}'), 'dual', [])
        val jsonArg = Regex("""JSON\.parse\('(.*?)'\)""", RegexOption.DOT_MATCHES_ALL)
            .find(xData)?.groupValues?.get(1) ?: return true
        val unescaped = unescapeUnicode(jsonArg)
        val mapper = ObjectMapper()
        val videos = mapper.readValue(unescaped, Map::class.java) as Map<String, Any>

        videos.forEach { (_, items) ->
            (items as? List<*> ?: emptyList()).forEach { item ->
                val itemMap = item as? Map<*, *> ?: return@forEach
                val link = itemMap["link"]?.toString() ?: return@forEach
                val slug = itemMap["slug"]?.toString() ?: ""
                val serviceName = itemMap["service_name"]?.toString() ?: "Player"
                val quality = itemMap["quality"]?.toString() ?: "HD"
                val template = itemMap["template"]?.toString() ?: ""
                val decoded = String(Base64.getDecoder().decode(template), Charsets.UTF_8)
                val iframeHtml = decoded
                    .replace("{url}", link)
                    .replace("{slug}", slug)
                val iframeSrc = Regex("""data-src=["']([^"']+)["']""")
                    .find(iframeHtml)?.groupValues?.get(1) ?: return@forEach
                val playerUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc

                if (playerUrl.contains("vidload.top")) {
                    vidloadExtract(playerUrl, serviceName, quality, callback)
                } else if (playerUrl.contains("vidmoly.net")) {
                    vidmolyExtract(playerUrl, serviceName, quality, callback)
                }
            }
        }
        return true
    }

    private suspend fun vidloadExtract(
        playerUrl: String,
        serviceName: String,
        quality: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(playerUrl, headers = mapOf("Referer" to mainUrl)).document
        val script = document.select("script").map { it.data() }.joinToString("\n")
        val m = Regex("""jwplayer\('[^']+'\)\.setup\(JSON\.parse\('(.*?)'\)\)""", RegexOption.DOT_MATCHES_ALL)
            .find(script) ?: return
        val unescaped = unescapeUnicode(m.groupValues[1])
        val mapper = ObjectMapper()
        try {
            val setup = mapper.readValue(unescaped, Map::class.java) as Map<String, Any>
            val file = setup["file"]?.toString() ?: return
            callback(newExtractorLink(
                source = "Vidload",
                name = "$serviceName $quality",
                url = file,
                ExtractorLinkType.M3U8
            ) {
                this.referer = playerUrl
                this.quality = Qualities.Unknown.value
            })
        } catch (_: Exception) {
        }
    }

    private suspend fun vidmolyExtract(
        playerUrl: String,
        serviceName: String,
        quality: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(playerUrl, headers = mapOf("Referer" to mainUrl)).document
        val script = document.select("script").map { it.data() }.joinToString("\n")
        val m = Regex("""sources:\s*\[\s*\{\s*file:\s*["']([^"']+)["']""").find(script) ?: return
        val file = m.groupValues[1]
        callback(newExtractorLink(
            source = "VidMoly",
            name = "$serviceName $quality",
            url = file,
            ExtractorLinkType.M3U8
        ) {
            this.referer = playerUrl
            this.quality = Qualities.Unknown.value
        })
    }

    private fun unescapeUnicode(s: String): String {
        return Regex("""\\u([0-9a-fA-F]{4})""").replace(s) { match ->
            val code = Integer.parseInt(match.groupValues[1], 16)
            String(Character.toChars(code))
        }
    }
}
