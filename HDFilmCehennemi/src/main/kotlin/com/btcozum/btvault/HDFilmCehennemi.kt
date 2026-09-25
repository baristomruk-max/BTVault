package com.btcozum.btvault

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class HDFilmCehennemi : MainAPI() {
    override var mainUrl              = "https://www.hdfilmcehennemi.nl"
    override var name                 = "HDFilmCehennemi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie, TvType.TvSeries)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 150L
    override var sequentialMainPageScrollDelay = 150L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        mainUrl                                           to "Yeni Eklenen Filmler",
        "${mainUrl}/yabancidiziizle-5/"                   to "Yeni Eklenen Diziler",
        "${mainUrl}/category/tavsiye-filmler-izle3/"      to "Tavsiye Filmler",
        "${mainUrl}/imdb-7-puan-uzeri-filmler-2/"         to "IMDB 7+ Filmler",
        "${mainUrl}/en-cok-yorumlananlar-2/"              to "En Çok Yorumlananlar",
        "${mainUrl}/en-cok-begenilen-filmleri-izle-4/"    to "En Çok Beğenilenler",
        "${mainUrl}/tur/aile-filmleri-izleyin-7/"         to "Aile Filmleri",
        "${mainUrl}/tur/aksiyon-filmleri-izleyin-8/"      to "Aksiyon Filmleri",
        "${mainUrl}/tur/animasyon-filmlerini-izleyin-5/"  to "Animasyon Filmleri",
        "${mainUrl}/tur/belgesel-filmlerini-izle-2/"      to "Belgesel Filmleri",
        "${mainUrl}/tur/bilim-kurgu-filmlerini-izleyin-5/" to "Bilim Kurgu Filmleri",
        "${mainUrl}/tur/komedi-filmlerini-izleyin-2/"     to "Komedi Filmleri",
        "${mainUrl}/tur/korku-filmlerini-izle-9/"         to "Korku Filmleri",
        "${mainUrl}/tur/romantik-filmleri-izle-3/"        to "Romantik Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data, interceptor = interceptor).document
        val home     = document.select("a.poster").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("strong.poster-title")?.text() ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val img       = this.selectFirst("img")
        val rawPoster = img?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: img?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
        val posterUrl = fixUrlNull(rawPoster)
        return newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get(
            "${mainUrl}/search/?q=${query}",
            headers = mapOf("X-Requested-With" to "fetch")
        ).parsedSafe<Results>() ?: return emptyList()
        val searchResults = mutableListOf<SearchResponse>()
        response.results.forEach { resultHtml ->
            val document  = Jsoup.parse(resultHtml)
            val title     = document.selectFirst("h4.title")?.text() ?: return@forEach
            val href      = fixUrlNull(document.selectFirst("a")?.attr("href")) ?: return@forEach
            val posterUrl = fixUrlNull(document.selectFirst("img")?.attr("src")) ?: fixUrlNull(document.selectFirst("img")?.attr("data-src"))
            searchResults.add(
                newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl?.replace("/thumb/", "/list/") }
            )
        }
        return searchResults
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, interceptor = interceptor).document
        val title       = document.selectFirst("h1.section-title")?.text()?.substringBefore(" izle") ?: return null
        val poster      = fixUrlNull(document.select("aside.post-info-poster img.lazyload").lastOrNull()?.attr("data-src"))
        val tags        = document.select("div.post-info-genres a").map { it.text() }
        val year        = document.selectFirst("div.post-info-year-country a")?.text()?.trim()?.toIntOrNull()
        val tvType      = if (document.select("div.seasons").isEmpty()) TvType.Movie else TvType.TvSeries
        val description = document.selectFirst("article.post-info-content > p")?.text()?.trim()
        val actors      = document.select("div.post-info-cast a").map {
            Actor(it.selectFirst("strong")!!.text(), it.select("img").attr("data-src"))
        }
        val recommendations = document.select("div.section-slider-container div.slider-slide").mapNotNull {
            val recName      = it.selectFirst("a")?.attr("title") ?: return@mapNotNull null
            val recHref      = fixUrlNull(it.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val recPosterUrl = fixUrlNull(it.selectFirst("img")?.attr("data-src")) ?: fixUrlNull(it.selectFirst("img")?.attr("src"))
            newTvSeriesSearchResponse(recName, recHref, TvType.TvSeries) { this.posterUrl = recPosterUrl }
        }

        return if (tvType == TvType.TvSeries) {
            val trailer  = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            val episodes = document.select("div.seasons-tab-content a").mapNotNull {
                val epName    = it.selectFirst("h4")?.text()?.trim() ?: return@mapNotNull null
                val epHref    = fixUrlNull(it.attr("href")) ?: return@mapNotNull null
                val epEpisode = Regex("""(\d+). Bolum""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                val epSeason  = Regex("""(\d+). Sezon""").find(epName)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode }
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.recommendations = recommendations; this.actors = actors.map { ActorData(it) }; if (trailer != null) this.trailers.add(TrailerData(trailer, null, false))
            }
        } else {
            val trailer = document.selectFirst("div.post-info-trailer button")?.attr("data-modal")?.substringAfter("trailer/", "")?.let { if (it.isNotEmpty()) "https://www.youtube.com/watch?v=$it" else null }
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.recommendations = recommendations; this.actors = actors.map { ActorData(it) }; if (trailer != null) this.trailers.add(TrailerData(trailer, null, false))
            }
        }
    }

    /**
     * HDFilmCehennemi oynatıcı sayfası video adresini Dean Edwards ile paketlenmiş
     * obfuscated bir JS içinde saklar. getAndUnpack() ile paket çözüldükten sonra
     * kalan obfuscated payload decode edilir (site kendi decoder'ı olan yva6y benzeri
     * fonksiyonlarla deşifre ediyor).
     */
    private fun decodeRapidrame(unpacked: String): String? = try {
        // "PAYLOAD".split("@")  ->  payload parçaları
        val match = Regex(""""([^"]+)"\s*\.split\(\s*"([^"]*)"\s*\)""").findAll(unpacked).lastOrNull()
        val payload = match?.groupValues?.get(1)
        val delim   = match?.groupValues?.get(2)
        if (payload.isNullOrEmpty() || delim.isNullOrEmpty()) null else decodeObfuscated(payload.split(delim))
    } catch (e: Exception) {
        null
    }

    private fun decodeObfuscated(input: List<String>): String {
        val arr = ArrayList(input)
        val myq = arr.size - 2
        val b0r = myq % 7
        val d3n = 8 + (myq % 5)

        val dwtqp = arr.removeAt(d3n)
        val l6w   = arr.removeAt(b0r)
        var x = arr.joinToString("")

        if (dwtqp.length > 2048) x = x.reversed()

        var uv47f = 0
        var czb = 0
        for (i in l6w.indices) {
            val z = l6w[i].code
            uv47f = (uv47f * 37 + z) % 241
            czb = (czb + ((z shl 1) xor i)) and 255
        }
        val y99wm = (uv47f * 3 + czb) % 256
        val hkaa  = (czb % 11) + 5
        var uyiz  = ((czb * 251 + uv47f) % 65519) + 1

        for (i in dwtqp.length - 1 downTo 0) {
            val seq = dwtqp[i]
            x = when {
                seq == '7' -> jsAtob(x)
                seq == '3' -> x.reversed()
                else       -> rotLetters(x, (26 - ((seq.code - 96) % 26)) % 26)
            }
        }

        if (l6w.length > 4096) x = jsAtob(x)

        val len = x.length
        val perm = IntArray(len)
        for (i in len - 1 downTo 1) {
            uyiz = (uyiz * 97 + 41) % 65519
            perm[i] = uyiz % (i + 1)
        }
        val chars = x.toCharArray()
        for (i in 1 until len) {
            val j = perm[i]
            val t = chars[i]; chars[i] = chars[j]; chars[j] = t
        }
        x = String(chars)

        var bmrq = y99wm
        val sb = StringBuilder()
        for (ch in x) {
            val z = ch.code
            bmrq = (bmrq * 5 + hkaa) % 256
            sb.append((z xor bmrq).toChar())
            bmrq = (bmrq + z) % 256
        }
        return sb.toString()
    }

    private fun rotLetters(s: String, shift: Int): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            if (ch in 'a'..'z' || ch in 'A'..'Z') {
                val base = if (ch <= 'Z') 65 else 97
                sb.append((((ch.code - base + shift) % 26) + base).toChar())
            } else sb.append(ch)
        }
        return sb.toString()
    }

    /** JS atob() davranisina birebir esdeger (byte -> char). */
    private fun jsAtob(s: String): String {
        val cleaned = s.replace(Regex("""\s"""), "")
        val padded = when (cleaned.length % 4) {
            2 -> cleaned + "=="
            3 -> cleaned + "="
            else -> cleaned
        }
        val bytes = Base64.decode(padded, Base64.DEFAULT)
        return String(bytes, Charsets.ISO_8859_1)
    }

    private suspend fun invokeLocalSource(source: String, url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val script   = app.get(url, referer = "${mainUrl}/").document.select("script").find { it.data().contains("sources:") }?.data() ?: return
        val videoUrl = decodeRapidrame(getAndUnpack(script)) ?: return
        val subData  = script.substringAfter("tracks: [").substringBefore("]")

        callback.invoke(
            newExtractorLink(
                source = source,
                name = source,
                url = videoUrl,
                type = INFER_TYPE
            ) {
                headers = mapOf("Referer" to "${mainUrl}/")
                quality = Qualities.Unknown.value
            }
        )

        tryParseJson<List<SubSource>>("[${subData}]")?.filter { it.kind == "captions" }?.forEach {
            subtitleCallback.invoke(newSubtitleFile(it.label ?: "", fixUrl(it.file ?: "")))
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, interceptor = interceptor).document
        document.select("div.alternative-links").map { element ->
            element to element.attr("data-lang").uppercase()
        }.forEach { (element, langCode) ->
            element.select("button.alternative-link").map { button ->
                button.text().replace("(HDrip Xbet)", "").trim() + " $langCode" to button.attr("data-video")
            }.forEach { (source, videoID) ->
                val apiGet = app.get(
                    "${mainUrl}/video/$videoID/", interceptor = interceptor,
                    headers = mapOf("Content-Type" to "application/json", "X-Requested-With" to "fetch"),
                    referer = data
                ).text
                val match = Regex("""data-src=\\"([^"]+)""").find(apiGet)
                if (match != null) {
                    var iframe = match.groupValues[1].replace("\\", "")
                    if (iframe.contains("?rapidrame_id=")) {
                        // sonunda "/" olmali, aksi halde site 301 ile yonlendirir
                        iframe = "${mainUrl}/playerr/" + iframe.substringAfter("?rapidrame_id=").substringBefore("&") + "/"
                    }
                    invokeLocalSource(source, iframe, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private data class SubSource(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null
    )
    data class Results(@JsonProperty("results") val results: List<String> = arrayListOf())
}