@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu arac @keyiflerolsun tarafindan | @KekikAkademi icin yazilmistir.
// selcukflix.com (720PizleAI): site tamamen Next.js'e tasinan her turlu veriyi
// AES-256-CBC ile sifreleyip sayfaya gomuyor. Burada ayni zincir cevriliyor:
//   arama  -> POST /api/bg/searchContent?searchterm=...
//   liste  -> POST /api/bg/findMovies?currentPage=N&...
//   detay  -> <script id="__NEXT_DATA__"> props.pageProps.secureData
//   oynatici -> RelatedResults.getMoviePartSourcesById_* -> pichive.online
//               openPlayer('...') -> source2.php -> master.m3u8

package com.btcozum.btvault

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class IzleAI : MainAPI() {
    override var mainUrl              = "https://selcukflix.com"
    override var name                 = "720PizleAI"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val supportedTypes       = setOf(TvType.Movie)

    // ! CloudFlare bypass
    override var sequentialMainPage              = true
    override var sequentialMainPageDelay         = 50L
    override var sequentialMainPageScrollDelay   = 50L

    // Liste sayfalari /api/bg/findMovies ile donuyor; "sonraki" dugmesi de ayni
    // ucnu kullandigi icin currentPage ile sinirsiz sayfalama calisiyor.
    override val mainPage = mainPageOf(
        "${mainUrl}/filmler?orderType=date_desc"                                to "En Yeni Filmler",
        "${mainUrl}/filmler?orderType=imdb_desc"                                to "IMDb Sirali",
        "${mainUrl}/filmler?imdbPointMin=6.9"                                   to "IMDb 6.9+",
        "${mainUrl}/filmler?releaseYearStart=2026&releaseYearEnd=2026"          to "2026 Filmleri",
        "${mainUrl}/filmler?releaseYearStart=2025&releaseYearEnd=2025"          to "2025 Filmleri",
        "${mainUrl}/filmler?releaseYearStart=2024&releaseYearEnd=2024"          to "2024 Filmleri",
        "${mainUrl}/filmler?releaseYearStart=2020&releaseYearEnd=2020"          to "2020 Filmleri",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val params = linkedMapOf(
            "releaseYearStart"  to "-1",
            "releaseYearEnd"    to "-1",
            "imdbPointMin"      to "-1",
            "imdbPointMax"      to "-1",
            "categoryIdsComma"  to "",
            "countryIdsComma"   to "",
            "orderType"         to "date_desc",
            "languageId"        to "-1",
            "currentPage"       to page.toString(),
            "currentPageCount"  to "24",
            "queryStr"          to "",
            "categorySlugsComma" to "",
            "countryCodesComma" to ""
        )

        for (pair in request.data.substringAfter("?", "").split("&")) {
            if ('=' !in pair) continue
            val key = pair.substringBefore("=")
            if (key in params) params[key] = pair.substringAfter("=")
        }

        val query = params.entries.joinToString("&") { "${enc(it.key)}=${enc(it.value)}" }
        val list  = iaiList("/api/bg/findMovies", query) ?: return null

        return newHomePageResponse(request.name, list.result.orEmpty().mapNotNull { it.toSearchResponse() })
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val list = iaiList("/api/bg/searchContent", "searchterm=${enc(query)}")
            ?: throw ErrorLoadingException("Arama sonucu alinamadi")

        if (list.state != true) throw ErrorLoadingException("Arama sonucu donmedi")

        return list.result.orEmpty()
            .filter { it.type.isNullOrBlank() || it.type.equals("Movies", true) }
            .mapNotNull { it.toSearchResponse() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val resp     = app.get(url)
        val html     = resp.text
        val document = resp.document
        val root     = iaiSecure(html)

        val ci = root?.get("contentItem")
            ?.takeIf { !it.isMissingNode && !it.isNull }
            ?.let { runCatching { iaiMapper.treeToValue(it, IaiContentItem::class.java) }.getOrNull() }

        val related = root?.get("RelatedResults")?.takeIf { !it.isMissingNode && !it.isNull }

        val title = ci?.originalTitle.orText()
            ?: ci?.cultureTitle.orText()
            ?: document.selectFirst("h1")?.text().orText()
            ?: return null

        val poster = iaiImage(ci?.poster)
            ?: document.selectFirst("meta[property=og:image]")?.attr("content").orText()

        val plot = ci?.description.orText()
            ?: document.selectFirst("meta[name=description]")?.attr("content").orText()

        val tags = ci?.categories.orText()
            ?.split(",")
            ?.mapNotNull { it.trim().takeIf { s -> s.isNotEmpty() } }
            ?.takeIf { it.isNotEmpty() }

        val rating = ci?.imdb?.takeIf { it > 0.0 }?.toString()?.toRatingInt()
        val duration = ci?.minutes
        val trailer = ci?.trailer.orText()
            ?: related?.get("getContentTrailers")?.get("result")?.path("raw_url")?.asText(null).orText()

        val actors = related?.get("getMovieCastsById")?.get("result")
            ?.takeIf { it.isArray }
            ?.mapNotNull { node ->
                val actorName = node.path("name").asText(null).orText() ?: return@mapNotNull null
                Actor(actorName, iaiImage(node.path("cast_image").asText(null)))
            }
            ?: emptyList()

        return newMovieLoadResponse(title, url, TvType.Movie, url) {
            this.posterUrl = poster
            this.year      = ci?.year
            this.plot      = plot
            this.tags      = tags
            this.rating    = rating
            this.duration  = duration

            if (trailer != null) addTrailer(trailer)
            if (actors.isNotEmpty()) addActors(actors)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val html  = app.get(data).text
        val root  = iaiSecure(html) ?: return false
        val nodes = root.get("RelatedResults")?.takeIf { !it.isMissingNode && !it.isNull } ?: return false

        // hem film kaynaklari hem bolum bazli kaynaklari topla
        val sources = linkedMapOf<String, String?>()
        for (key in nodes.fieldNames().asSequence().toList()) {
            if (key != "getMovieSourcesById" && !key.startsWith("getMoviePartSourcesById_")) continue

            val result = nodes.get(key)?.get("result")?.takeIf { it.isArray } ?: continue
            for (node in result) {
                val content = node.path("source_content").asText(null) ?: continue
                val quality = node.path("quality_name").asText(null)

                for (match in IAI_SRC.findAll(content)) {
                    var link = match.groupValues[1].trim()
                    if (link.startsWith("//")) link = "https:$link"
                    if (!link.startsWith("http")) continue
                    if (link !in sources) sources[link] = quality
                }
            }
        }

        if (sources.isEmpty()) return false

        var found = false
        for ((link, quality) in sources) {
            found = resolveSource(link, data, quality, subtitleCallback, callback) || found
        }
        return found
    }

    /** Tek bir oynatici kaynagini cozup callback ile iletir. */
    private suspend fun resolveSource(
        link: String,
        referer: String,
        quality: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (!link.contains("pichive")) {
            return loadExtractor(link, referer, subtitleCallback, callback)
        }

        val page = app.get(link, referer = referer).text
        val playList = IAI_PLAYLIST.find(page)?.groupValues?.get(1) ?: return false

        // kaynaklar iki farkli adreste barisiyor (pichive.online / four.pichive.online),
        // source2.php ve m3u8 her zaman iframe'in kendi origin'inden istenmeli
        val host = link.substringAfter("://", "").substringBefore("/")
        if (host.isEmpty()) return false
        val origin = "https://$host"

        val json = app.get("$origin/source2.php?v=${enc(playList)}", referer = link).text
        val playlist = runCatching { iaiMapper.readTree(json) }.getOrNull()
            ?.path("playlist")?.takeIf { it.isArray } ?: return false

        var found = false
        for (entry in playlist) {
            val file = entry.path("sources").takeIf { it.isArray }
                ?.get(0)?.path("file")?.asText(null) ?: continue
            if (file.isBlank()) continue

            val hls = file.replace("m.php", "master.m3u8")
            callback(
                newExtractorLink(
                    source = "PiChive",
                    name   = quality?.takeIf { it.isNotBlank() }?.let { "PiChive $it" } ?: "PiChive",
                    url    = hls,
                    type   = ExtractorLinkType.M3U8
                ) {
                    this.quality = iaiQuality(quality)
                    // referans basligi yoksa kaynak 404 donuyor
                    this.headers = mapOf("Referer" to "$origin/")
                }
            )
            found = true
        }
        return found
    }

    // ---------------------------------------------------------------- helpers

    private fun IaiItem.toSearchResponse(): SearchResponse? {
        val slug  = slug.orText() ?: return null
        val title = objectName.orText() ?: originalTitle.orText() ?: usedTitle.orText() ?: return null
        val url   = if (slug.startsWith("http")) slug else "$mainUrl/${slug.trimStart('/')}"

        return newMovieSearchResponse(title, url, TvType.Movie) {
            this.posterUrl = iaiImage(objectPoster ?: poster)
            this.year      = objectYear ?: releaseYear
        }
    }

    /** /api/bg/ uclari sifreli "response" dondurur; cozulmus halini JsonNode olarak verir. */
    private suspend fun iaiList(path: String, query: String): IaiList? {
        val url  = "$mainUrl$path" + if (query.isEmpty()) "" else "?$query"
        val text = app.post(
            url,
            headers = mapOf(
                "Accept"           to "application/json, text/javascript, */*; q=0.01",
                "X-Requested-With" to "XMLHttpRequest"
            ),
            referer = "$mainUrl/"
        ).text

        val plain = runCatching { iaiMapper.readValue(text, IaiEnvelope::class.java) }
            .getOrNull()?.response
            ?.let { iaiDecrypt(it) }
            ?: return null

        return runCatching { iaiMapper.readValue(plain, IaiList::class.java) }.getOrNull()
    }

    /** Detay sayfasindaki __NEXT_DATA__ -> secureData -> sifre cozulmus JSON. */
    private fun iaiSecure(html: String): JsonNode? {
        val start = html.indexOf(NEXT_DATA_TAG)
        if (start < 0) return null

        val bodyStart = start + NEXT_DATA_TAG.length
        val bodyEnd   = html.indexOf("</script>", bodyStart)
        if (bodyEnd < 0) return null

        return runCatching {
            val next    = iaiMapper.readTree(html.substring(bodyStart, bodyEnd))
            val secured = next.path("props").path("pageProps").path("secureData")
            if (secured.isMissingNode || secured.isNull) return null

            val plain = iaiDecrypt(secured.asText()) ?: return null
            iaiMapper.readTree(plain)
        }.getOrNull()
    }

    /**
     * Sitenin kendi uyguladigi sema (module 379):
     *   key = base64(sha256(IAI_SEED)).substring(0, 32)   (utf8 baytlari)
     *   iv  = 16 sıfir bayti
     *   AES-256-CBC / PKCS7
     */
    private fun iaiDecrypt(payload: String): String? = runCatching {
        val hash   = MessageDigest.getInstance("SHA-256").digest(IAI_SEED.toByteArray(Charsets.UTF_8))
        val key    = android.util.Base64.encodeToString(hash, android.util.Base64.NO_WRAP).substring(0, 32)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(
            Cipher.DECRYPT_MODE,
            SecretKeySpec(key.toByteArray(Charsets.UTF_8), "AES"),
            IvParameterSpec(ByteArray(16))
        )
        val bytes = android.util.Base64.decode(payload, android.util.Base64.DEFAULT)
        String(cipher.doFinal(bytes), Charsets.UTF_8)
    }.getOrNull()

    /** cdn.ampproject / eski dosya alanlarini guncel gorsel adreslerine cevirir. */
    private fun iaiImage(raw: String?): String? {
        val value = raw.orText() ?: return null

        val cleaned = value
            .replace("images-macellan-online.cdn.ampproject.org/i/s/", "")
            .replace("file.dizilla.club", "file.macellan.online")
            .replace("images.dizilla.club", "images.macellan.online")
            .replace("images.dizimia4.com", "images.macellan.online")
            .replace("file.dizimia4.com", "file.macellan.online")

        return when {
            cleaned.startsWith("//")  -> "https:$cleaned"
            cleaned.startsWith("http") -> cleaned
            else -> "https://${cleaned.trimStart('/')}"
        }
    }

    private fun iaiQuality(quality: String?): Int {
        val value = quality?.filter { it.isDigit() }?.toIntOrNull() ?: return Qualities.Unknown.value
        return if (value in 1..10000) value else Qualities.Unknown.value
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun String?.orText(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        private const val IAI_SEED    = "!!22xx!!90!!"
        private const val NEXT_DATA_TAG = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"

        private val iaiMapper = jacksonObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)

        private val IAI_SRC     = Regex("""src\s*=\s*["']([^"']+)["']""")
        private val IAI_PLAYLIST = Regex("""openPlayer\('([^']+)'\s*,""")
    }
}
