package com.btcozum.btvault

import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

import java.net.URLEncoder

class DiziPal : MainAPI() {
    // dizipal####.com adresi belirli araliklarla degisiyor; eski adres 301 ile yonlendirildigi
    // icin istekler yine de siteye ulasir, kart baglantilari zaten yeni alan adini tasir.
    override var mainUrl              = "https://dizipal3008.com"
    override var name                 = "DiziPal"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.TvSeries, TvType.Movie)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 50L
    override var sequentialMainPageScrollDelay = 50L

    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            // asama 403 de donebilir, "Just a moment" basligi da kontrol edilir
            if (response.code == 403 || response.code == 503 || doc.html().contains("Just a moment")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler/page/SAYFA/"                    to "Yeni Diziler",
        "${mainUrl}/filmler/page/SAYFA/"                    to "Yeni Filmler",
        "${mainUrl}/animeler/page/SAYFA/"                   to "Animeler",
        "${mainUrl}/platform/netflix/page/SAYFA/"           to "Netflix",
        "${mainUrl}/platform/exxen/page/SAYFA/"             to "Exxen",
        "${mainUrl}/platform/prime-video/page/SAYFA/"       to "Amazon Prime",
        "${mainUrl}/platform/disney/page/SAYFA/"            to "Disney+",
        "${mainUrl}/platform/hbomax/page/SAYFA/"            to "HBO Max",
        "${mainUrl}/platform/gain/page/SAYFA/"              to "Gain",
        "${mainUrl}/platform/mubi/page/SAYFA/"              to "Mubi",
        "${mainUrl}/platform/tod/page/SAYFA/"               to "TOD (beIN)",
        "${mainUrl}/platform/tabii/page/SAYFA/"             to "Tabii",
        "${mainUrl}/dizi-kategori/dram/page/SAYFA/"         to "Dram Dizileri",
        "${mainUrl}/dizi-kategori/komedi/page/SAYFA/"       to "Komedi Dizileri",
        "${mainUrl}/dizi-kategori/korku/page/SAYFA/"        to "Korku Dizileri",
        "${mainUrl}/dizi-kategori/aksiyon/page/SAYFA/"      to "Aksiyon Dizileri",
        "${mainUrl}/dizi-kategori/bilim-kurgu/page/SAYFA/"  to "Bilim Kurgu Dizileri",
        "${mainUrl}/kategori/dram/page/SAYFA/"              to "Dram Filmleri",
        "${mainUrl}/kategori/komedi/page/SAYFA/"            to "Komedi Filmleri",
        "${mainUrl}/kategori/korku/page/SAYFA/"             to "Korku Filmleri"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url      = request.data.replace("SAYFA", "$page")
        val document = app.get(url, timeout = 10000, interceptor = interceptor, headers = getHeaders()).document
        val home     = document.select("div.post-item").mapNotNull { it.toCard() }
        val hasNext  = document.select("a[href*=\"page/${page + 1}/\"]").isNotEmpty()
        return newHomePageResponse(request.name, home, hasNext = hasNext)
    }

    // <div class="post-item"><a href="..." title="Baslik"> <div class="poster"><img data-src="...">
    private fun Element.toCard(): SearchResponse? {
        val link  = this.selectFirst("a[href]") ?: return null
        val title = link.attr("title").trim().ifEmpty { link.text().trim() }
        if (title.isEmpty()) return null

        val href = fixUrlNull(link.attr("href")) ?: return null
        if (!isContentUrl(href)) return null

        val img       = this.selectFirst("img")
        val posterUrl = fixUrlNull(img?.attr("data-src")?.takeIf { it.isNotBlank() } ?: img?.attr("src"))

        return if (isSeriesUrl(href))
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        else
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q        = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("${mainUrl}/?s=$q", timeout = 10000, interceptor = interceptor, headers = getHeaders()).document
        return document.select("div.post-item").mapNotNull { it.toCard() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, timeout = 15000, interceptor = interceptor, headers = getHeaders()).document

        val h1    = document.selectFirst("h1") ?: return null
        val title = h1.ownText().trim().ifEmpty { h1.text().trim() }
        if (title.isEmpty()) return null

        val poster      = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content"))
        val description = document.selectFirst("meta[name='description']")?.attr("content")?.trim()?.takeIf { it.isNotEmpty() }
        val year        = document.selectFirst("a[href*='/yapim/']")?.text()?.trim()?.toIntOrNull()

        val genreText = metaValue(document, "Tür")
        val tags = (if (genreText.isEmpty()) emptyList<String>() else genreText.split(',').map { it.trim() })
            .filter { it.isNotEmpty() }
            .ifEmpty {
                document.select("a[href*=kategori/]").map { it.text().trim() }
                    .filter { it.isNotEmpty() }.distinct()
            }

        val duration = durationMinutes(metaValue(document, "Süre"))
        val actors   = document.select("a[href*=oyuncu]").map { it.text().trim() }
            .filter { it.isNotEmpty() }.distinct().map { Actor(it) }

        if (!isSeriesUrl(url)) {
            return newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.year      = year
                this.plot      = description
                this.tags      = tags
                this.duration  = duration
                this.actors    = actors.map { ActorData(it) }
            }
        }

        // sezon listesi sunucu tarafinda: /dizi/<slug>/?sezon=N -> o sezonun bolumleri
        val episodes   = mutableListOf<Episode>()
        val seasonUrls = document.select("#season-options-list a[href]")
            .mapNotNull { fixUrlNull(it.attr("href")) }
            .distinct()

        if (seasonUrls.isEmpty()) {
            addEpisodes(document, episodes, null)
        } else {
            for (seasonUrl in seasonUrls) {
                val seasonNo  = SEASON_PARAM.find(seasonUrl)?.groupValues?.get(1)?.toIntOrNull()
                val seasonDoc = runCatching {
                    app.get(seasonUrl, timeout = 15000, interceptor = interceptor, headers = getHeaders()).document
                }.getOrNull() ?: continue
                addEpisodes(seasonDoc, episodes, seasonNo)
            }
            if (episodes.isEmpty()) addEpisodes(document, episodes, null)
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.year      = year
            this.plot      = description
            this.tags      = tags
            this.duration  = duration
            this.actors    = actors.map { ActorData(it) }
        }
    }

    // <div class="episode-item">
    //   <a href="/bolum/..." title="Dizi 1. Sezon 1. Bolum Izle"><img ...></a>
    //   <div><h4><a href="/bolum/..." title="Dizi 1. Sezon 1. Bolum Izle">Dizi</a></h4>
    //        <div><h4> 1. Bolum </h4><span>... once</span></div></div>
    private fun addEpisodes(document: Document, episodes: MutableList<Episode>, seasonOverride: Int?) {
        document.select("div.episode-item").forEach { item ->
            val link = item.selectFirst("h4 a[href]") ?: item.selectFirst("a[href]") ?: return@forEach
            val href = fixUrlNull(link.attr("href")) ?: return@forEach

            // baslikta bolum numarasi yoksa ikinci h4 ("1. Bolum") tamamlayici olarak kullanilir
            val alt     = item.select("h4").getOrNull(1)?.text()?.trim().orEmpty()
            var epName  = link.attr("title").trim().ifEmpty { link.text().trim() }
            if (epName.isNotEmpty() && !EP_EPISODE.containsMatchIn(epName) && alt.isNotEmpty()) {
                epName = "$epName - $alt"
            }
            if (epName.isEmpty()) return@forEach

            val epSeason  = seasonOverride ?: EP_SEASON.find(epName)?.groupValues?.get(1)?.toIntOrNull()
                ?: EP_SEASON.find(alt)?.groupValues?.get(1)?.toIntOrNull() ?: 1
            val epEpisode = EP_EPISODE.find(epName)?.groupValues?.get(1)?.toIntOrNull()
                ?: EP_EPISODE.find(alt)?.groupValues?.get(1)?.toIntOrNull()

            episodes.add(newEpisode(href) {
                this.name    = epName
                this.season  = epSeason
                this.episode = epEpisode
            })
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 15000, interceptor = interceptor, headers = getHeaders()).document

        val iframe = document.selectFirst("div.responsive-player iframe")?.attr("src")
            ?: document.selectFirst("div.player-area iframe")?.attr("src")
            ?: document.selectFirst(".series-player-container iframe")?.attr("src")
            ?: document.selectFirst("div#vast_new iframe")?.attr("src")
            ?: return false

        val embedUrl = fixUrlNull(iframe.trim()) ?: return false
        return resolveEmbed(embedUrl, subtitleCallback, callback)
    }

    private suspend fun resolveEmbed(
        embedUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // gomulu oynatici sayfasi Referer ister ("referer gerekir" yazisi doner)
        val embedHtml = runCatching {
            app.get(embedUrl, referer = "${mainUrl}/", timeout = 15000, headers = getHeaders()).text
        }.getOrNull() ?: return loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)

        // 1) klasik gomulu oynatici: file:"https://.../video.mp4"
        var streamUrl = INLINE_FILE.find(embedHtml)?.groupValues?.get(1)

        // 2) yeni oynatici: fetch('/dl?op=get_stream&...') -> {"url":"https://.../master.m3u8"}
        if (streamUrl.isNullOrEmpty()) {
            val path = FETCH_PATH.findAll(embedHtml).map { it.groupValues[1] }.firstOrNull { it.contains("get_stream") }
                ?: FETCH_PATH.find(embedHtml)?.groupValues?.get(1)
                ?: return loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)

            val origin = originOf(embedUrl) ?: return loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)
            val json   = runCatching {
                app.get(
                    url     = if (path.startsWith("http")) path else origin + path,
                    timeout = 15000,
                    referer = embedUrl,
                    headers = mapOf(
                        "Origin"           to origin,   // Origin olmadan {"error":"unauthorized"} donuyor
                        "Referer"          to embedUrl,
                        "User-Agent"       to USER_AGENT,
                        "Accept"           to "application/json, text/javascript, */*; q=0.01",
                        "X-Requested-With" to "XMLHttpRequest"
                    )
                ).text
            }.getOrNull()

            streamUrl = json?.let { STREAM_URL.find(it)?.groupValues?.get(1)?.replace("\\/", "/") }
        }

        val finalUrl = streamUrl
        if (finalUrl.isNullOrEmpty()) return loadExtractor(embedUrl, "${mainUrl}/", subtitleCallback, callback)

        SUBTITLE.find(embedHtml)?.groupValues?.get(1)?.let { emitSubtitles(it, subtitleCallback) }

        val origin = originOf(embedUrl) ?: mainUrl
        val type = when {
            finalUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
            finalUrl.contains(".mp4",  ignoreCase = true) -> ExtractorLinkType.VIDEO
            else -> INFER_TYPE
        }

        callback.invoke(
            newExtractorLink(source = name, name = name, url = finalUrl, type = type) {
                this.referer = embedUrl
                // CDN hem Origin hem Referer istiyor (yoksa 403)
                this.headers = mapOf(
                    "Origin"     to origin,
                    "Referer"    to embedUrl,
                    "User-Agent" to USER_AGENT
                )
                this.quality = Qualities.Unknown.value
            }
        )
        return true
    }

    // "subtitle":"[Turkce]https://...vtt,[Ingilizce]https://...vtt"
    private suspend fun emitSubtitles(raw: String, subtitleCallback: (SubtitleFile) -> Unit) {
        raw.split(",").forEach { part ->
            val p = part.trim()
            if (p.isEmpty()) return@forEach

            val lang   = p.substringAfter("[", "").substringBefore("]", "")
            val subUrl = p.replace(SUBTITLE_TAG, "").trim()
            if (subUrl.isEmpty()) return@forEach

            val full = if (subUrl.startsWith("http")) subUrl else "${mainUrl.trimEnd('/')}$subUrl"
            subtitleCallback.invoke(newSubtitleFile(lang.ifEmpty { "Unknown" }, full))
        }
    }

    // <span>Etiket</span> <div>...deger...</div> ikilisinden degeri alir
    private fun metaValue(document: Document, label: String): String {
        val span = document.select("span").firstOrNull { it.ownText().trim().equals(label, ignoreCase = true) } ?: return ""
        return span.nextElementSibling()?.text()?.trim() ?: ""
    }

    // "1s 50dk" / "50dk" / "1 saat 50 dk" -> dakika
    private fun durationMinutes(text: String): Int? {
        if (text.isBlank()) return null
        val hours   = DURATION_HOURS.find(text)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = DURATION_MINUTES.find(text)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            hours != null   -> hours * 60 + (minutes ?: 0)
            minutes != null -> minutes
            else            -> null
        }
    }

    private fun isSeriesUrl(url: String) = url.contains("/dizi/") || url.contains("/anime/")

    private fun pathOf(url: String): String {
        val noScheme = url.substringAfter("://", url)
        val path     = if (noScheme.contains("/")) noScheme.substringAfter("/") else ""
        return "/" + path.substringBefore('#').substringBefore('?').trim('/')
    }

    // diziler/filmler/animeler listeleri, kategori ve sayfa baglantilari sonuc degil
    private fun isContentUrl(url: String): Boolean {
        val path = pathOf(url)
        if (path.startsWith("/dizi/") || path.startsWith("/anime/")) return true
        val segs = path.trim('/').split('/').filter { it.isNotEmpty() }
        if (segs.size != 1) return false
        return segs[0].lowercase() !in NOT_CONTENT
    }

    private fun originOf(url: String): String? {
        val schemeSep = url.indexOf("://")
        if (schemeSep <= 0) return null
        val rest = url.substring(schemeSep + 3)
        val host = rest.substringBefore("/").substringBefore("?").substringBefore("#")
        if (host.isEmpty()) return null
        return url.substring(0, schemeSep + 3) + host
    }

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/134.0.0.0 Safari/537.36"

        private val EP_SEASON       = Regex("""(\d+)\.?\s*Sezon""")
        private val EP_EPISODE      = Regex("""(\d+)\.?\s*B[öo]l[üu]m""")
        private val SEASON_PARAM    = Regex("""[?&]sezon=(\d+)""")
        private val INLINE_FILE     = Regex("file\\s*:\\s*\"([^\"]+)\"")
        private val FETCH_PATH      = Regex("""fetch\('([^']+)'\)""")
        private val STREAM_URL      = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
        private val SUBTITLE        = Regex("subtitle\"?\\s*:\\s*\"([^\"]+)\"")
        private val SUBTITLE_TAG    = Regex("""\[[^\]]*]""")
        private val DURATION_HOURS  = Regex("""(\d+)\s*(?:saat|sa\b|s\b)""", RegexOption.IGNORE_CASE)
        private val DURATION_MINUTES = Regex("""(\d+)\s*dk""", RegexOption.IGNORE_CASE)

        private val NOT_CONTENT = setOf(
            "diziler", "filmler", "animeler", "kategori", "dizi-kategori", "platform",
            "bolum", "sayfa", "page", "iletisim", "hakkimizda", "giris", "kayit",
            "etiket", "yazar", "wp-admin", "wp-content", "gizlilik", "kosullar"
        )

        private fun getHeaders(): Map<String, String> = mapOf(
            "Accept"     to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "User-Agent" to USER_AGENT,
        )
    }
}
