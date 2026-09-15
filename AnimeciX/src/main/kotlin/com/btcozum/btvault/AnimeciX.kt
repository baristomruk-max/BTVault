package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.annotation.JsonProperty

class AnimeciX : MainAPI() {
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
            val response = app.get("${mainUrl}/secure/last-episodes?page=$page&perPage=10", headers = mapOf("x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk")).parsedSafe<LastEpisodesResponse>()?.data ?: emptyList()
            val home = response.map {
                val formattedTitle = "S${it.seasonNumber}B${it.episodeNumber} - ${it.titleName}"
                newAnimeSearchResponse(formattedTitle, "${mainUrl}/secure/titles/${it.titleId}?titleId=${it.titleId}", TvType.Anime) { this.posterUrl = fixUrlNull(it.titlePoster) }
            }
            newHomePageResponse(request.name, home)
        } else {
            val response = app.get("${request.data}&page=${page}&perPage=16", headers = mapOf("x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk")).parsedSafe<Category>()
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
        val response = app.get(url, headers = mapOf("x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk")).parsedSafe<Title>() ?: return null
        val episodes = mutableListOf<Episode>()
        val titleId  = url.substringAfter("?titleId=")

        if (response.title.titleType == "anime") {
            for (sezon in response.title.seasons) {
                val sezonResponse = app.get("${mainUrl}/secure/related-videos?episode=1&season=${sezon.number}&videoId=0&titleId=${titleId}").parsedSafe<TitleVideos>() ?: return null
                for (video in sezonResponse.data) {
                    episodes.add(newEpisode("${mainUrl}/secure/video/${video.id}?videoId=${video.id}&titleId=${titleId}") {
                        this.name = "${video.title}"
                        this.season = sezon.number
                        this.episode = video.episodeNumber
                    })
                }
            }
        }

        return newTvSeriesLoadResponse(response.title.title, url, TvType.Anime, episodes) {
            this.posterUrl = fixUrlNull(response.title.poster)
            this.plot      = response.title.description
            this.year      = response.title.releaseDate?.substringBefore("-")?.toIntOrNull()
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val response = app.get(data, headers = mapOf("x-e-h" to "7Y2ozlO+QysR5w9Q6Tupmtvl9jJp7ThFH8SB+Lo7NvZjgjqRSqOgcT2v4ISM9sP10LmnlYI8WQ==.xrlyOBFS5BHjQ2Lk")).parsedSafe<VideoResponse>() ?: return false

        response.data.sources?.forEach { source ->
            val type = if (source.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH
            callback(newExtractorLink(source = "AnimeciX", name = source.label ?: "AnimeciX", url = source.url, type = type) { quality = Qualities.Unknown.value })
        }

        response.data.subtitles?.forEach { sub ->
            subtitleCallback(SubtitleFile(lang = sub.label ?: "Turkce", url = sub.url))
        }

        return true
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
        @JsonProperty("id") val id: Int = 0,
        @JsonProperty("title") val title: String = "",
        @JsonProperty("titleType") val titleType: String = "",
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("releaseDate") val releaseDate: String? = null,
        @JsonProperty("seasons") val seasons: List<Season> = emptyList()
    )
    data class Season(@JsonProperty("number") val number: Int = 1)
    data class TitleVideos(@JsonProperty("data") val data: List<VideoItem> = emptyList())
    data class VideoItem(@JsonProperty("id") val id: Int = 0, @JsonProperty("title") val title: String = "", @JsonProperty("episodeNumber") val episodeNumber: Int = 1)
    data class VideoResponse(@JsonProperty("data") val data: VideoData)
    data class VideoData(
        @JsonProperty("sources") val sources: List<VideoSource>? = null,
        @JsonProperty("subtitles") val subtitles: List<VideoSubtitle>? = null
    )
    data class VideoSource(@JsonProperty("url") val url: String = "", @JsonProperty("label") val label: String? = null)
    data class VideoSubtitle(@JsonProperty("url") val url: String = "", @JsonProperty("label") val label: String? = null)
}
