@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
package com.btcozum.btvault

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import java.io.InputStream

/**
 * Kapsamli dunya spor kanallari.
 *
 * Eski vavoo listesi bayatlamisti (play ID'leri 404). Bu surum iptv-org
 * spor kategorisini kullanir ve kanallari ulke koduna gore gruplar.
 */
class vavooSpor : MainAPI() {
    override var mainUrl              = "https://iptv-org.github.io/iptv/categories/sports.m3u"
    override var name                 = "vavooSpor"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = true
    override val hasDownloadSupport   = false
    override val supportedTypes       = setOf(TvType.Live)

    private val playlistUrls = listOf(
        mainUrl,
        "https://raw.githubusercontent.com/iptv-org/iptv/master/streams/tr.m3u"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val kanallar = fetchPlaylist()

        val listeler = kanallar.items.groupBy { grupAdi(it) }.map { group ->
            val title = group.key
            val show  = group.value.map { kanal ->
                val streamurl   = kanal.url.toString()
                val channelname = kanal.title.toString()
                val posterurl   = kanal.attributes["tvg-logo"].orEmpty()
                val chGroup     = grupAdi(kanal)
                val nation      = kanal.attributes["tvg-country"].orEmpty()

                newLiveSearchResponse(
                    channelname,
                    LoadData(streamurl, channelname, posterurl, chGroup, nation).toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = posterurl
                    this.lang = nation.ifBlank { "tr" }
                }
            }

            HomePageList(title, show, isHorizontalImages = true)
        }

        return newHomePageResponse(listeler, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val kanallar = fetchPlaylist()

        return kanallar.items.filter { it.title.toString().contains(query, ignoreCase = true) }.map { kanal ->
            val streamurl   = kanal.url.toString()
            val channelname = kanal.title.toString()
            val posterurl   = kanal.attributes["tvg-logo"].orEmpty()
            val chGroup     = grupAdi(kanal)
            val nation      = kanal.attributes["tvg-country"].orEmpty()

            newLiveSearchResponse(
                channelname,
                LoadData(streamurl, channelname, posterurl, chGroup, nation).toJson(),
                type = TvType.Live
            ) {
                this.posterUrl = posterurl
                this.lang = nation.ifBlank { "tr" }
            }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse {
        val loadData = fetchDataFromUrlOrJson(url)
        val nation: String = "== ${loadData.group} | ${loadData.nation} =="

        val kanallar        = fetchPlaylist()
        val recommendations = mutableListOf<LiveSearchResponse>()

        for (kanal in kanallar.items) {
            if (grupAdi(kanal) == loadData.group) {
                val rcStreamUrl   = kanal.url.toString()
                val rcChannelName = kanal.title.toString()
                if (rcChannelName == loadData.title) continue

                val rcPosterUrl   = kanal.attributes["tvg-logo"].orEmpty()
                val rcChGroup     = grupAdi(kanal)
                val rcNation      = kanal.attributes["tvg-country"].orEmpty()

                recommendations.add(newLiveSearchResponse(
                    rcChannelName,
                    LoadData(rcStreamUrl, rcChannelName, rcPosterUrl, rcChGroup, rcNation).toJson(),
                    type = TvType.Live
                ) {
                    this.posterUrl = rcPosterUrl
                    this.lang = rcNation.ifBlank { "tr" }
                })
            }
        }

        return newLiveStreamLoadResponse(loadData.title, loadData.url, url) {
            this.posterUrl       = loadData.poster
            this.plot            = nation
            this.tags            = listOf(loadData.group, loadData.nation)
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val loadData = fetchDataFromUrlOrJson(data)
        Log.d("IPTV", "loadData -> $loadData")

        val kanallar = fetchPlaylist()
        val kanal    = kanallar.items.firstOrNull { it.url == loadData.url }
        Log.d("IPTV", "kanal -> $kanal")

        callback.invoke(
            newExtractorLink(
                source = this.name,
                name   = this.name,
                url    = loadData.url,
                ExtractorLinkType.M3U8
            ) {
                this.headers = kanal?.headers ?: emptyMap()
                this.referer = kanal?.headers?.get("referrer") ?: ""
                this.quality = Qualities.Unknown.value
            }
        )

        return true
    }

    data class LoadData(val url: String, val title: String, val poster: String, val group: String, val nation: String)

    /**
     * iptv-org spor kategorisinde tum kayitlar "Sports" grubundadir.
     * Bu yuzden gercek gruplama icin tvg-id sonundaki ulke kodu kullanilir
     * (ornek: "ASpor.tr@SD" -> "TR").
     */
    private fun grupAdi(item: PlaylistItem): String {
        val group = item.attributes["group-title"].orEmpty()
        if (group.isNotBlank() && !group.contains("Sport", ignoreCase = true)) return group

        val id   = item.attributes["tvg-id"].orEmpty()
        val code = id.substringAfterLast('.', "").substringBefore('@').uppercase()
        return if (code.length == 2) code else "Diger"
    }

    private suspend fun fetchPlaylist(): Playlist {
        var lastError: Exception? = null
        for ((index, playlistUrl) in playlistUrls.withIndex()) {
            try {
                val playlist = IptvPlaylistParser().parseM3U(app.get(playlistUrl).text)
                val items = if (index == 0) {
                    playlist.items
                } else {
                    playlist.items.filter {
                        it.attributes["group-title"].orEmpty().contains("Sport", ignoreCase = true)
                    }
                }
                if (items.isNotEmpty()) return Playlist(items)
            } catch (e: Exception) {
                lastError = e
                Log.w("IPTV", "Playlist alinamadi: $playlistUrl -> ${e.message}")
            }
        }
        throw lastError ?: IllegalStateException("Hicbir spor playlist adresi yuklenemedi")
    }

    private suspend fun fetchDataFromUrlOrJson(data: String): LoadData {
        if (data.startsWith("{")) {
            return parseJson<LoadData>(data)
        } else {
            val kanallar = fetchPlaylist()
            val kanal    = kanallar.items.first { it.url == data }

            val streamurl   = kanal.url.toString()
            val channelname = kanal.title.toString()
            val posterurl   = kanal.attributes["tvg-logo"].orEmpty()
            val chGroup     = grupAdi(kanal)
            val nation      = kanal.attributes["tvg-country"].orEmpty()

            return LoadData(streamurl, channelname, posterurl, chGroup, nation)
        }
    }
}

data class Playlist(
    val items: List<PlaylistItem> = emptyList()
)

data class PlaylistItem(
    val title: String?                  = null,
    val attributes: Map<String, String> = emptyMap(),
    val headers: Map<String, String>    = emptyMap(),
    val url: String?                    = null,
    val userAgent: String?              = null
)

class IptvPlaylistParser {

    /**
     * Parse M3U8 string into [Playlist]
     *
     * @param content M3U8 content string.
     * @throws PlaylistParserException if an error occurs.
     */
    fun parseM3U(content: String): Playlist {
        return parseM3U(content.byteInputStream())
    }

    /**
     * Parse M3U8 content [InputStream] into [Playlist]
     *
     * @param input Stream of input data.
     * @throws PlaylistParserException if an error occurs.
     */
    @Throws(PlaylistParserException::class)
    fun parseM3U(input: InputStream): Playlist {
        val reader = input.bufferedReader()

        if (!reader.readLine().isExtendedM3u()) {
            throw PlaylistParserException.InvalidHeader()
        }

        val playlistItems: MutableList<PlaylistItem> = mutableListOf()
        var currentIndex = 0

        var line: String? = reader.readLine()

        while (line != null) {
            if (line.isNotEmpty()) {
                if (line.startsWith(EXT_INF)) {
                    val title      = line.getTitle()
                    val attributes = line.getAttributes()

                    playlistItems.add(PlaylistItem(title, attributes))
                } else if (line.startsWith(EXT_VLC_OPT)) {
                    val item      = playlistItems[currentIndex]
                    val userAgent = item.userAgent ?: line.getTagValue("http-user-agent")
                    val referrer  = line.getTagValue("http-referrer")

                    val headers = mutableMapOf<String, String>()

                    if (userAgent != null) {
                        headers["user-agent"] = userAgent
                    }

                    if (referrer != null) {
                        headers["referrer"] = referrer
                    }

                    playlistItems[currentIndex] = item.copy(
                        userAgent = userAgent,
                        headers   = headers
                    )
                } else {
                    if (!line.startsWith("#")) {
                        val item       = playlistItems[currentIndex]
                        val url        = line.getUrl()
                        val userAgent  = line.getUrlParameter("user-agent")
                        val referrer   = line.getUrlParameter("referer")
                        val urlHeaders = if (referrer != null) { item.headers + mapOf("referrer" to referrer) } else item.headers

                        playlistItems[currentIndex] = item.copy(
                            url       = url,
                            headers   = item.headers + urlHeaders,
                            userAgent = userAgent ?: item.userAgent
                        )
                        currentIndex++
                    }
                }
            }

            line = reader.readLine()
        }
        return Playlist(playlistItems)
    }

    /** Replace "" (quotes) from given string. */
    private fun String.replaceQuotesAndTrim(): String {
        return replace("\"", "").trim()
    }

    /** Check if given content is valid M3U8 playlist. */
    private fun String.isExtendedM3u(): Boolean = startsWith(EXT_M3U)

    /**
     * Get title of media.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     *
     * Result: Title
     */
    private fun String.getTitle(): String? {
        return split(",").lastOrNull()?.replaceQuotesAndTrim()
    }

    /**
     * Get media url.
     *
     * Example:-
     *
     * Input:
     * ```
     * https://example.com/sample.m3u8|user-agent="Custom"
     * ```
     *
     * Result: https://example.com/sample.m3u8
     */
    private fun String.getUrl(): String? {
        return split("|").firstOrNull()?.replaceQuotesAndTrim()
    }

    /**
     * Get url parameter with key.
     *
     * Example:-
     *
     * Input:
     * ```
     * http://192.54.104.122:8080/d/abcdef/video.mp4|User-Agent=Mozilla&Referer=CustomReferrer
     * ```
     *
     * If given key is `user-agent`, then
     *
     * Result: Mozilla
     */
    private fun String.getUrlParameter(key: String): String? {
        val urlRegex     = Regex("^(.*)\\|", RegexOption.IGNORE_CASE)
        val keyRegex     = Regex("$key=(\\w[^&]*)", RegexOption.IGNORE_CASE)
        val paramsString = replace(urlRegex, "").replaceQuotesAndTrim()

        return keyRegex.find(paramsString)?.groups?.get(1)?.value
    }

    /**
     * Get attributes from `#EXTINF` tag as Map<String, String>.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTINF:-1 tvg-id="1234" group-title="Kids" tvg-logo="url/to/logo", Title
     * ```
     *
     * Result will be equivalent to kotlin map:
     * ```Kotlin
     * mapOf(
     *   "tvg-id" to "1234",
     *   "group-title" to "Kids",
     *   "tvg-logo" to "url/to/logo"
     * )
     * ```
     */
    private fun String.getAttributes(): Map<String, String> {
        val extInfRegex      = Regex("(#EXTINF:.?[0-9]+)", RegexOption.IGNORE_CASE)
        val attributesString = replace(extInfRegex, "").replaceQuotesAndTrim().split(",").first()

        return attributesString
            .split(Regex("\\s"))
            .mapNotNull {
                val pair = it.split("=")
                if (pair.size == 2) pair.first() to pair.last().replaceQuotesAndTrim() else null
            }
            .toMap()
    }

    /**
     * Get value from a tag.
     *
     * Example:-
     *
     * Input:
     * ```
     * #EXTVLCOPT:http-referrer=http://example.com/
     * ```
     *
     * Result: http://example.com/
     */
    private fun String.getTagValue(key: String): String? {
        val keyRegex = Regex("$key=(.*)", RegexOption.IGNORE_CASE)

        return keyRegex.find(this)?.groups?.get(1)?.value?.replaceQuotesAndTrim()
    }

    companion object {
        const val EXT_M3U     = "#EXTM3U"
        const val EXT_INF     = "#EXTINF"
        const val EXT_VLC_OPT = "#EXTVLCOPT"
    }
}

/** Exception thrown when an error occurs while parsing playlist. */
sealed class PlaylistParserException(message: String) : Exception(message) {

    /** Exception thrown if given file content is not valid. */
    class InvalidHeader : PlaylistParserException("Invalid file header. Header doesn't start with #EXTM3U")
}
