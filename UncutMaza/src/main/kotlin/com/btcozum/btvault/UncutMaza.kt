@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! https://codeberg.org/coxju/cs-ext-coxju/src/branch/master/UncutMaza/src/main/kotlin/com/coxju/UncutMaza.kt

package com.btcozum.btvault

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup

class UncutMaza : MainAPI() {
    private val cloudflareKiller by lazy { CloudflareKiller() }
    private val interceptor      by lazy { CloudflareInterceptor(cloudflareKiller) }

    class CloudflareInterceptor(private val cloudflareKiller: CloudflareKiller) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response {
            val request  = chain.request()
            val response = chain.proceed(request)
            val doc      = Jsoup.parse(response.peekBody(1024 * 1024).string())
            if (doc.text().contains("Just a moment...")) {
                return cloudflareKiller.intercept(chain)
            }
            return response
        }
    }

    override var mainUrl              = "https://uncutmaza.cc"
    override var name                 = "UncutMaza"
    override val hasMainPage          = true
    override var lang                 = "hi"
    override val hasQuickSearch       = false
    override val supportedTypes       = setOf(TvType.NSFW)
    override val vpnStatus            = VPNStatus.MightBeNeeded

    override val mainPage = mainPageOf(
            "${mainUrl}/page/"                           to "Home",
            "${mainUrl}/category/niks-indian-porn/page/" to "Niks Indian"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page, interceptor = interceptor).document
        val home     = document.select("div.videos-list > article.post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list    = HomePageList(
                name               = request.name,
                list               = home,
                isHorizontalImages = true
            ),
            hasNext = true
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = fixTitle(this.select("a").attr("title"))
        val href      = fixUrlNull(this.select("a").attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.select("a > div.post-thumbnail>div.post-thumbnail-container>img").attr("data-src"))

        return newMovieSearchResponse(title, href, TvType.NSFW) { this.posterUrl = posterUrl }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchResponse = mutableListOf<SearchResponse>()

        for (i in 1..5) {
            val document = app.get("${mainUrl}/page/$i?s=$query", interceptor = interceptor).document

            val results = document.select("article.post").mapNotNull { it.toSearchResult() }

            if (!searchResponse.containsAll(results)) {
                searchResponse.addAll(results)
            } else {
                break
            }

            if (results.isEmpty()) break
        }

        return searchResponse
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = interceptor).document

        val title       = document.selectFirst("meta[property=og:title]")?.attr("content")?.trim().toString()
        val poster      = fixUrlNull(document.selectFirst("meta[property=og:image]")?.attr("content").toString())
        val description = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        return newMovieLoadResponse(title, url, TvType.NSFW, url) {
            this.posterUrl = poster
            this.plot      = description
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data, interceptor = interceptor).document

        document.select("div.video-player").map { res ->
            callback.invoke(
                ExtractorLink(
                    source  = this.name,
                    name    = this.name,
                    url     = fixUrl(res.selectFirst("meta[itemprop=contentURL]")?.attr("content")?.trim().toString()),
                    referer = data,
                    quality = Qualities.Unknown.value,
                    type    = INFER_TYPE
                )
            )
        }

        return true
    }
}