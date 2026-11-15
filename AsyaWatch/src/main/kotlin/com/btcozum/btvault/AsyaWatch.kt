package com.btcozum.btvault

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class AsyaWatch : MainAPI() {
    override var mainUrl = "https://asyawatch.com"
    override var name = "AsyaWatch"
    override val hasMainPage = true
    override var lang = "tr"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.TvSeries)

    override val mainPage = mainPageOf(
        mainUrl to "Ana Sayfa"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val items = document.select("a.sc.pcard, a[href*=\"/dizi/\"]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
        return newHomePageResponse(request.name, items)
    }

    private fun org.jsoup.nodes.Element.toSearchResult(): SearchResponse? {
        val href = fixUrlNull(this.attr("href")) ?: return null
        if (!href.contains("/dizi/")) return null
        val title = this.selectFirst("b")?.text() ?: this.text().trim().takeIf { it.isNotBlank() } ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("${mainUrl}/ara?q=${query}").document
        return document.select("a.sc.pcard, a[href*=\"/dizi/\"]").mapNotNull { it.toSearchResult() }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val ldJson = document.selectFirst("script[type=\"application/ld+json\"]")?.data() ?: ""
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val ld = try { mapper.readValue(ldJson, Map::class.java) as Map<String, Any> } catch (_: Exception) { emptyMap() }
        val title = ld["name"]?.toString() ?: document.selectFirst("title")?.text()?.substringBefore(" izle") ?: return null
        val description = ld["description"]?.toString()
        val posterUrl = fixUrlNull(ld["image"]?.toString())
        val episodes = mutableListOf<Episode>()
        val seasons = ld["containsSeason"] as? List<*> ?: emptyList()
        seasons.forEach { season ->
            val sm = season as? Map<*, *> ?: return@forEach
            val seasonNum = sm["seasonNumber"]?.toString()?.toIntOrNull() ?: 1
            val eps = sm["episode"] as? List<*> ?: emptyList()
            eps.forEach { ep ->
                val em = ep as? Map<*, *> ?: return@forEach
                val epName = em["name"]?.toString() ?: return@forEach
                val epUrl = fixUrlNull(em["url"]?.toString()) ?: return@forEach
                val epNum = em["episodeNumber"]?.toString()?.toIntOrNull()
                episodes.add(newEpisode(epUrl) {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                })
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = posterUrl
            this.plot = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val html = app.get(data).text
        // RSC payload'dan video src çıkar
        val srcMatch = Regex(""""src"\s*:\s*"(https?://[^"]+)"""").find(html)
        val videoUrl = srcMatch?.groupValues?.get(1) ?: return false
        callback.invoke(newExtractorLink(
            source = name,
            name = name,
            url = videoUrl,
            ExtractorLinkType.VIDEO
        ) {
            this.referer = data
        })
        return true
    }
}
