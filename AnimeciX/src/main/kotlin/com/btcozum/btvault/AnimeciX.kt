package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class AnimeciX : MainAPI() {
    // shared anti-bot header required by every /secure/* endpoint
    private val E_H = "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk"
    override var mainUrl              = "https://animecix.tv"
    override var name                 = "AnimeciX"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.Anime)

    override var sequentialMainPage            = true
    override var sequentialMainPageDelay       = 200L
    override var sequentialMainPageScrollDelay = 200L

    override val mainPage = mainPageOf(
        "${mainUrl}/secure/last-episodes"                          to "Son Eklenen Bolumler",
        "${mainUrl}/secure/titles?type=series&onlyStreamable=true" to "Seriler",
        "${mainUrl}/secure/titles?type=movie&onlyStreamable=true"  to "Filmler"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return if (request.data.contains("/last-episodes")) {
            val response = app.get("${mainUrl}/secure/last-episodes?page=$page&perPage=10", headers = mapOf("x-e-h" to E_H)).parsedSafe<LastEpisodesResponse>()?.data ?: emptyList()
            val home = response.map {
                val formattedTitle = "S${it.seasonNumber}B${it.episodeNumber} - ${it.titleName}"
                newAnimeSearchResponse(formattedTitle, "${mainUrl}/secure/titles/${it.titleId}?titleId=${it.titleId}", TvType.Anime) { this.posterUrl = fixUrlNull(it.titlePoster) }
            }
            newHomePageResponse(request.name, home)
        } else {
            val response = app.get("${request.data}&page=${page}&perPage=16", headers = mapOf("x-e-h" to E_H)).parsedSafe<Category>()
            val home = response?.pagination?.data?.map { anime ->
                newAnimeSearchResponse(anime.title, "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}", TvType.Anime) { this.posterUrl = fixUrlNull(anime.poster) }
            } ?: listOf()
            newHomePageResponse(request.name, home)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val response = app.get("${mainUrl}/secure/search/${query}?limit=20").parsedSafe<Search>() ?: return listOf()
        return response.results.map { anime ->
            newAnimeSearchResponse(anime.title, "${mainUrl}/secure/titles/${anime.id}?titleId=${anime.id}", TvType.Anime) { this.posterUrl = fixUrlNull(anime.poster) }
        }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val headers = mapOf("x-e-h" to E_H, "X-Requested-With" to "fetch")
        val response = app.get(url, headers = headers).parsedSafe<TitleResponse>() ?: return null
        val episodes = mutableListOf<Episode>()
        val titleId  = url.substringAfter("?titleId=", "")

        // the API returns "title_type"/"type"/"is_series" (snake_case) - "titleType" never exists
        val isSeries = response.title.isSeries || response.title.seasons.isNotEmpty()

        if (isSeries) {
            for (sezon in response.title.seasons) {
                val sezonResponse = app.get(
                    "${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}",
                    headers = headers
                ).parsedSafe<TitleVideos>() ?: continue

                // the payload is {"videos":[...]} with name/season_num/episode_num
                for (video in sezonResponse.videos) {
                    episodes.add(newEpisode("${mainUrl}/secure/videos/${video.id}") {
                        this.name      = video.name
                        this.season    = video.seasonNumber
                        this.episode   = video.episodeNumber
                        this.posterUrl = fixUrlNull(video.thumbnail)
                    })
                }
            }
        }

        val displayTitle = listOf(response.title.name, response.title.nameEnglish, response.title.nameRomanji, response.title.title)
            .firstOrNull { !it.isNullOrBlank() }?.trim() ?: return null
        val year = response.title.year ?: response.title.releaseDate?.substringBefore("-")?.toIntOrNull()

        if (episodes.isNotEmpty()) {
            return newTvSeriesLoadResponse(displayTitle, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(response.title.poster)
                this.plot      = response.title.description
                this.year      = year
            }
        }

        return newMovieLoadResponse(displayTitle, url, TvType.Anime, url) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.plot      = response.title.description
            this.year      = year
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        // data = https://animecix.tv/secure/videos/<videoId> -> { video: { url: "https://tau-video.xyz/embed/<embedId>" } }
        val headers = mapOf("x-e-h" to E_H, "X-Requested-With" to "fetch")
        val envelope = app.get(data, headers = headers).parsedSafe<VideoEnvelope>() ?: return false
        val embedUrl = envelope.video.url ?: return false

        // some entries point straight at a file instead of the tau-video embed player
        if (!embedUrl.contains("tau-video.xyz/embed/")) {
            if (embedUrl.isBlank()) return false
            val directType = if (embedUrl.contains(".m3u8")) ExtractorLinkType.M3U8
                             else if (embedUrl.contains(".mpd")) ExtractorLinkType.DASH
                             else ExtractorLinkType.VIDEO
            callback(newExtractorLink(source = "AnimeciX", name = "AnimeciX", url = embedUrl, type = directType) {
                quality = Qualities.Unknown.value
            })
            return true
        }

        val embedId  = embedUrl.substringAfterLast("/").substringBefore("?")

        // the embed player resolves the real files through this endpoint
        val tau = app.get(
            "https://tau-video.xyz/api/video/$embedId",
            headers = mapOf("Accept" to "application/json", "Referer" to "https://tau-video.xyz/")
        ).parsedSafe<TauResponse>() ?: return false

        tau.urls.forEach { source ->
            val digits = source.label?.filter { it.isDigit() } ?: ""
            callback(newExtractorLink(source = "AnimeciX", name = source.label ?: "AnimeciX", url = source.url, type = ExtractorLinkType.VIDEO) {
                quality = digits.toIntOrNull() ?: Qualities.Unknown.value
            })
        }

        return tau.urls.isNotEmpty()
    }

    data class LastEpisodesResponse(@JsonProperty("data") val data: List<LastEpisode> = emptyList())
    data class LastEpisode(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("titleId") val titleId: Int = 0,
        @JsonProperty("titleName") val titleName: String = "",
        @JsonProperty("titlePoster") val titlePoster: String? = null,
        @JsonProperty("seasonNumber") val seasonNumber: Int = 1,
        @JsonProperty("episodeNumber") val episodeNumber: Int = 1
    )
    data class Category(@JsonProperty("pagination") val pagination: Pagination? = null)
    data class Pagination(@JsonProperty("data") val data: List<AnimeItem> = emptyList())
    data class AnimeItem(@JsonProperty("id") val id: Int = 0, @JsonProperty("title") val title: String = "", @JsonProperty("poster") val poster: String? = null)
    data class Search(@JsonProperty("results") val results: List<AnimeItem> = emptyList())
    data class TitleResponse(@JsonProperty("title") val title: TitleDetail)
    data class TitleDetail(
        // the live payload uses snake_case / "name" - the old "title"/"titleType"/"releaseDate"
        // keys never appear, which used to produce an empty series name and no episodes
        @JsonProperty("title") val title: String = "",
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("name_english") val nameEnglish: String? = null,
        @JsonProperty("name_romanji") val nameRomanji: String? = null,
        @JsonProperty("original_title") val originalTitle: String? = null,
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("titleType") val titleType: String = "",
        @JsonProperty("title_type") val titleTypeSnake: String = "",
        @JsonProperty("type") val type: String = "",
        @JsonProperty("is_series") val isSeries: Boolean = false,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("release_date") val releaseDateSnake: String? = null,
        @JsonProperty("year") val year: Int? = null,
        @JsonProperty("seasons") val seasons: List<Season> = emptyList()
    )
    data class Season(@JsonProperty("number") val number: Int = 1)
    data class TitleVideos(@JsonProperty("videos") val videos: List<VideoItem> = emptyList())
    data class VideoItem(
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("name") val name: String = "",
        @JsonProperty("title") val title: String = "",
        @JsonProperty("season_num") val seasonNumber: Int = 1,
        @JsonProperty("episode_num") val episodeNumber: Int = 1,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("url") val url: String? = null
    )
    data class VideoEnvelope(@JsonProperty("video") val video: VideoSource)
    data class TauResponse(@JsonProperty("urls") val urls: List<TauSource> = emptyList())
    data class TauSource(
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("url") val url: String = "",
        @JsonProperty("size") val size: Long? = null
    )
    data class VideoResponse(@JsonProperty("data") val data: VideoData)
    data class VideoData(
        @JsonProperty("sources") val sources: List<VideoSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<VideoSubtitle>? = null
    )
    data class VideoSource(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("label") val label: String? = null
    )
    data class VideoSubtitle(@JsonProperty("url") val url: String = "", @JsonProperty("label") val label: String? = null)
}
