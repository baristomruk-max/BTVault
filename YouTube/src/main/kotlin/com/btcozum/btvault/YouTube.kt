@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! https://github.com/recloudstream/extensions/blob/master/InvidiousProvider/src/main/kotlin/recloudstream/InvidiousProvider.kt

package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.StringUtils.encodeUri

class YouTube : MainAPI() {
    override var mainUrl              = "https://invidious.f5.si"
    override var name                 = "YouTube"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val supportedTypes       = setOf(TvType.Others)

    // Invidious örnekleri API'yi zaman zaman kapatabiliyor (inv.nadeko.net gibi),
    // bu yüzden istekleri sırayla deneyerek çalışan instance'ı buluyoruz.
    private val instances = listOf(
        "invidious.f5.si",
        "invidious.materialio.us",
        "inv.nadeko.net",
        "invidious.site",
        "yewtu.be",
    )

    private fun isJson(body: String) = body.startsWith("[") || body.startsWith("{")

    private suspend fun apiText(path: String): String? {
        for (host in instances) {
            try {
                val res  = app.get("https://$host$path")
                val body = res.text
                if (res.code == 200 && isJson(body)) return body
            } catch (_: Exception) { }
        }
        return null
    }

    private suspend fun dashManifest(videoId: String): String? {
        val path = "/api/manifest/dash/id/$videoId"
        for (host in instances) {
            try {
                val res = app.get("https://$host$path")
                if (res.code == 200 && res.text.startsWith("<")) return "https://$host$path"
            } catch (_: Exception) { }
        }
        return null
    }

    fun thumb(videoId: String, size: String) = "https://i.ytimg.com/vi/$videoId/$size.jpg"

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val region = lang.uppercase()

        suspend fun section(name: String, type: String): HomePageList {
            val items = tryParseJson<List<SearchEntry>>(
                apiText("/api/v1/trending?region=$region&type=$type&fields=videoId,title") ?: return HomePageList(name, emptyList(), true)
            )
            return HomePageList(name, items?.map { it.toSearchResponse(this) } ?: emptyList(), true)
        }

        return newHomePageResponse(
            listOf(
                section("Trend", "news"),
                section("Müzik", "music"),
                section("Film", "movies"),
                section("Oyun", "gaming"),
            ),
            false
        )
    }

    // this function gets called when you search for something
    override suspend fun search(query: String): List<SearchResponse> {
        val body = apiText(
            "/api/v1/search?q=${query.encodeUri()}&region=${lang.uppercase()}&page=1&type=video&fields=videoId,title"
        ) ?: return emptyList()

        return tryParseJson<List<SearchEntry>>(body)?.map { it.toSearchResponse(this) } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val videoId = Regex("watch\\?v=([a-zA-Z0-9_-]+)").find(url)?.groupValues?.get(1) ?: return null
        val body    = apiText(
            "/api/v1/videos/$videoId?region=${lang.uppercase()}&fields=videoId,title,description,recommendedVideos,author,authorThumbnails,formatStreams"
        ) ?: return null

        return tryParseJson<VideoEntry>(body)?.toLoadResponse(this)
    }

    private data class SearchEntry(val title: String, val videoId: String) {
        fun toSearchResponse(provider: YouTube): SearchResponse {
            return provider.newMovieSearchResponse(
                title,
                "${provider.mainUrl}/watch?v=${videoId}",
                TvType.Others
            ) {
                this.posterUrl = provider.thumb(videoId, "mqdefault")
            }
        }
    }

    private data class VideoEntry(
        val title: String,
        val description: String,
        val videoId: String,
        val recommendedVideos: List<SearchEntry>,
        val author: String,
        val authorThumbnails: List<Thumbnail>
    ) {
        suspend fun toLoadResponse(provider: YouTube): LoadResponse {
            return provider.newMovieLoadResponse(
                title,
                "${provider.mainUrl}/watch?v=${videoId}",
                TvType.Others,
                videoId
            ) {
                plot            = description
                posterUrl       = provider.thumb(videoId, "hqdefault")
                recommendations = recommendedVideos.map { it.toSearchResponse(provider) }
                actors          = listOf(ActorData(Actor(
                    author,
                    if (authorThumbnails.isNotEmpty()) authorThumbnails.last().url else ""
                )))
            }
        }
    }

    private data class Thumbnail(val url: String)

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        loadExtractor("https://www.youtube.com/watch?v=$data", subtitleCallback, callback)

        val manifest = dashManifest(data)
        if (manifest != null) {
            callback(
                ExtractorLink(
                    "YouTube",
                    "YouTube",
                    manifest,
                    "",
                    Qualities.Unknown.value,
                    false,
                    mapOf(),
                    null,
                    true
                )
            )
        }
        return true
    }

}
