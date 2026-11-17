package com.btcozum.btvault

import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.util.regex.Pattern

class HDFilmIzle : MainAPI() {
    override var mainUrl = "https://www.hdfilmizle.live"
    override var name = "HDFilmİzle"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = true
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "${mainUrl}/tur/aile/" to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon/" to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon/" to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel/" to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/dram/" to "Dram Filmleri",
        "${mainUrl}/tur/fantastik/" to "Fantastik Filmleri",
        "${mainUrl}/tur/gerilim/" to "Gerilim Filmleri",
        "${mainUrl}/tur/gizem/" to "Gizem Filmleri",
        "${mainUrl}/tur/komedi/" to "Komedi Filmleri",
        "${mainUrl}/tur/korku/" to "Korku Filmleri",
        "${mainUrl}/tur/macera/" to "Macera Filmleri",
        "${mainUrl}/tur/romantik/" to "Romantik Filmler",
        "${mainUrl}/tur/savas/" to "Savaş Filmleri",
        "${mainUrl}/tur/suc/" to "Suç Filmleri",
        "${mainUrl}/tur/tarih/" to "Tarih Filmleri",
        "${mainUrl}/tur/vahsi-bati/" to "Vahşi Batı Filmleri",
        "${mainUrl}/tur/yerli-film-izle/" to "Yerli Filmler",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document

        val home: List<SearchResponse>?

        home = document.select("div#moviesListResult a.poster").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse {
        val title = this.selectFirst("h2.title")?.text() ?: ""
        val href = fixUrlNull(this.attr("href")) ?: ""
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src"))
        val score = this.selectFirst("div.poster-imdb")?.text()?.trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            this.score = Score.from10(score)
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.post(
            "$mainUrl/search/",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            referer = mainUrl,
            data = mapOf("query" to query)
        ).document
        val searchResults = mutableListOf<SearchResponse>()

        val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
        try {
            val videos: List<Video> = objectMapper.readValue(response.body().text())
            videos.forEach { video ->
                val title = video.name
                val href = fixUrlNull(video.slug) ?: return@forEach
                val posterUrl = fixUrlNull(video.thumbUrl) ?: fixUrlNull(video.thumbWebp)

                searchResults.add(
                    newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
                )
            }
        } catch (e: Exception) {
            println("Error parsing JSON: ${e.message}")
        }

        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val orgTitle = document.selectFirst("div.page-title h1")?.text() ?: ""
        val altTitle =
            document.selectFirst("div.page-title")?.selectFirst("small.text-muted.alt-name")?.text()
                ?: ""
        val title =
            if (altTitle.isNotEmpty() && orgTitle != altTitle) "$orgTitle - $altTitle" else orgTitle
        val poster = fixUrlNull(document.selectFirst("picture.poster-auto img")?.attr("data-src"))
        val tags = document.select("div.pb-2.genres a").map { it.text() }
        val year = document.selectFirst("div.page-title")?.selectFirst("small.text-muted")?.text()
            ?.replace("(", "")?.replace(")", "")?.toIntOrNull()
        val description = document.selectFirst("article.text-white > p")?.text()?.trim()
        val rating = document.selectFirst("div.rate.mb-2 span")?.text()
        val actors = document.select("div.stories-wrapper a").map {
            Actor(
                it.selectFirst("div.story-item-title")!!.text(),
                fixUrlNull(it.select("img").attr("data-src"))
            )
        }

        val recommendations = document.select("div#swiper-wrapper-benzer").mapNotNull {
            val recName = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src"))
                ?: fixUrlNull(it.selectFirst("img")?.attr("src"))

            newMovieSearchResponse(recName, recHref, TvType.Movie) {
                this.posterUrl = recPosterUrl
            }
        }
        val trailer = document.selectFirst("div.nav-link")?.attr("data-trailer")

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year = year
            this.plot = description
            this.tags = tags
            this.score = Score.from10(rating)
            this.recommendations = recommendations
            addActors(actors)
            addTrailer(trailer)
        }
    }


    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("HDF", "data » ${data}")
        val document = app.get(data).document

        // Vidrame iframe'i (data-src attribute)
        val iframe = document.selectFirst("iframe")?.attr("data-src") ?: return false
        Log.d("HDF", "iframe » ${iframe}")

        // Vidrame player sayfasını referer ile getir
        val playerHtml = runCatching {
            app.get(iframe, referer = data, headers = mapOf("User-Agent" to UA)).text
        }.getOrNull() ?: return false

        // Vidrame XOR decode: sources: [{file: (function(d,k){...})([...], 'key')}]
        val encoded = Pattern.compile("""sources\s*:\s*\[\s*\{\s*file\s*:\s*\(function\(d,k\)\{.*?\}\)\((\[.*?\]),\s*['\"]([^'\"]+)['\"]\)""", Pattern.DOTALL)
            .matcher(playerHtml)
        if (!encoded.find()) {
            Log.d("HDF", "Vidrame XOR pattern not found")
            return false
        }

        val arrStr = encoded.group(1)
        val key = encoded.group(2)
        val nums = Pattern.compile("""\d+""").matcher(arrStr)
            .results()
            .map { it.group().toInt() }
            .toList()

        // XOR decode: d[i] ^ key[i % key.length] ^ ((i * 17 + 13) & 0xFF)
        val sb = StringBuilder()
        nums.forEachIndexed { index, value ->
            val keyChar = key[index % key.length].toInt()
            val xor = (index * 17 + 13) and 0xFF
            val decoded = (value xor keyChar xor xor).toChar()
            sb.append(decoded)
        }
        val streamUrl = sb.toString()

        if (!streamUrl.startsWith("http")) {
            Log.d("HDF", "Decoded URL invalid: $streamUrl")
            return false
        }

        Log.d("HDF", "Decoded stream: $streamUrl")

        callback.invoke(
            newExtractorLink(
                source = name,
                name = "Vidrame",
                url = streamUrl,
                ExtractorLinkType.M3U8
            ) {
                this.referer = iframe
                this.headers = mapOf("Referer" to iframe, "User-Agent" to UA)
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }

    companion object {
        const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    }