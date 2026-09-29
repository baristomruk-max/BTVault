package com.btcozum.btvault

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

import com.lagradost.cloudstream3.network.CloudflareKiller
import okhttp3.Interceptor
import okhttp3.Response
import org.jsoup.Jsoup


class SezonlukDizi : MainAPI() {
    override var mainUrl              = "https://sezonlukdizi.cc"
    override var name                 = "SezonlukDizi"
    override val hasMainPage          = true
    override var lang                 = "tr"
    override val hasQuickSearch       = false
    override var supportedTypes       = setOf(TvType.TvSeries)

    // the search call (diziler.asp?adi=...) sits behind a Cloudflare challenge
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

    override val mainPage = mainPageOf(
        "${mainUrl}/diziler.asp?siralama_tipi=id&s="          to "Son Eklenenler",
        "${mainUrl}/diziler.asp?siralama_tipi=id&tur=mini&s=" to "Mini Diziler",
        "${mainUrl}/diziler.asp?kat=2&s="                     to "Yerli Diziler",
        "${mainUrl}/diziler.asp?kat=1&s="                     to "Yabanci Diziler",
        "${mainUrl}/diziler.asp?kat=3&s="                     to "Asya Dizileri",
        "${mainUrl}/diziler.asp?kat=4&s="                     to "Animasyonlar",
        "${mainUrl}/diziler.asp?kat=5&s="                     to "Animeler",
        "${mainUrl}/diziler.asp?kat=6&s="                     to "Belgeseller"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}${page}").document
        val home     = document.select("div.afis a[href*='/diziler/']").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title     = this.selectFirst("div.description")?.text()?.trim()
            ?: this.attr("title").trim().takeIf { it.isNotEmpty() }
            ?: return null
        val href      = fixUrlNull(this.attr("href")) ?: return null
        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
    }

    // "Dizi Adi (2025)" -> "Dizi Adi" (SearchResponse yil alanina sahip degil)
    private val yilSonu = Regex("""\s*\((?:19|20)\d{2}\)\s*$""")

    override suspend fun search(query: String): List<SearchResponse> {
        // /ajax/arama.asp (POST q=) -> JSON; bu uc Cloudflare'siz calisiyor.
        // Eski diziler.asp?adi= sayfasi datacenter IP'ye 403 verdigi icin yalnizca yedek.
        val arama = app.post(
            "${mainUrl}/ajax/arama.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("q" to query)
        ).parsedSafe<Arama>()

        val sonuclar = arama?.results?.values
            ?.flatMap { it.results ?: emptyList() }
            ?.filter { it.url.contains("/diziler/") }
            ?.mapNotNull { sonuc ->
                val ad   = sonuc.title.replace(yilSonu, "").trim().ifEmpty { return@mapNotNull null }
                val href = fixUrlNull(sonuc.url) ?: return@mapNotNull null
                newTvSeriesSearchResponse(ad, href, TvType.TvSeries) {
                    this.posterUrl = fixUrlNull(sonuc.image)
                }
            } ?: emptyList()
        if (sonuclar.isNotEmpty()) return sonuclar

        val document = app.get(
            "${mainUrl}/diziler.asp?adi=${java.net.URLEncoder.encode(query, "UTF-8")}",
            interceptor = interceptor
        ).document
        return document.select("div.afis a[href*='/diziler/']").mapNotNull { it.toSearchResult() }
    }

    override suspend fun quickSearch(query: String): List<SearchResponse> = search(query)

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title       = document.selectFirst("div.header")?.text()?.trim()?.takeIf { it.isNotEmpty() }
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: document.selectFirst("[property='og:title']")?.attr("content")?.trim()
            ?: return null
        val poster      = fixUrlNull(
            document.selectFirst("div.image img")?.attr("data-src")
                ?: document.selectFirst("div.image img")?.attr("src")
                ?: document.selectFirst("[property='og:image']")?.attr("content")
        ) ?: return null
        val year        = document.selectFirst("div.extra span")?.text()?.trim()?.split("-")?.first()?.toIntOrNull()
            ?: Regex("""\b(19|20)\d{2}\b""").find(document.select("div.extra.content").text())?.value?.toIntOrNull()
        val description = document.selectFirst("span#tartismayorum-konu")?.text()?.trim()
        val tags        = document.select("div.labels a[href*='tur']").mapNotNull { it.text().trim() }
        val duration    = document.selectXpath("//span[contains(text(), 'Dk.')]").text().trim().substringBefore(" Dk.").toIntOrNull()
        val endpoint    = url.split("/").last()
        val actorsReq   = app.get("${mainUrl}/oyuncular/${endpoint}").document
        val actors      = actorsReq.select("div.doubling div.ui").mapNotNull {
            val name = it.selectFirst("div.header")?.text()?.trim() ?: return@mapNotNull null
            Actor(name, fixUrlNull(it.selectFirst("img")?.attr("src")))
        }
        val episodesReq = app.get("${mainUrl}/bolumler/${endpoint}").document
        val episodes    = mutableListOf<Episode>()
        for (sezon in episodesReq.select("table.unstackable")) {
            for (bolum in sezon.select("tbody tr")) {
                val epHref = fixUrlNull(bolum.selectFirst("td:nth-of-type(4) a")?.attr("href")
                    ?: bolum.selectFirst("td:nth-of-type(3) a")?.attr("href")) ?: continue
                val epName = bolum.selectFirst("td:nth-of-type(4) a")?.text()?.trim() ?: continue
                val epEpisode = Regex("""(\d+)""").find(
                    bolum.selectFirst("td:nth-of-type(3)")?.text() ?: ""
                )?.groupValues?.get(1)?.toIntOrNull()
                val epSeason = Regex("""(\d+)""").find(
                    bolum.selectFirst("td:nth-of-type(2)")?.text() ?: ""
                )?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(newEpisode(epHref) { this.name = epName; this.season = epSeason; this.episode = epEpisode })
            }
        }
        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster; this.year = year; this.plot = description; this.tags = tags; this.duration = duration; this.actors = actors.map { ActorData(it) }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val document = app.get(data).document
        val bid      = document.selectFirst("div#dilsec")?.attr("data-id")?.trim().orEmpty()
        if (bid.isEmpty()) return false

        // dil=1 -> altyazi, dil=0 -> dublaj; ikisi de ayni alternatif listesini verir
        alternatifleriEkle(bid, "1", "AltYazi", subtitleCallback, callback)
        alternatifleriEkle(bid, "0", "Dublaj", subtitleCallback, callback)
        return true
    }

    // /ajax/dataAlternatif22.asp (bid + dil) -> [{id, baslik, kalite}]
    // /ajax/dataEmbed22.asp       (id)       -> <iframe src="...">
    // (eski .asp uclari 404'e dustu, "22" ekiyle yenilendi)
    private suspend fun alternatifleriEkle(
        bid: String, dil: String, etiket: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val alternatifler = app.post(
            "${mainUrl}/ajax/dataAlternatif22.asp",
            headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
            data = mapOf("bid" to bid, "dil" to dil)
        ).parsedSafe<Kaynak>()?.takeIf { it.status == "success" }?.data ?: return

        for (veri in alternatifler) {
            val kaynakAdi = "$etiket - ${veri.baslik}"
            val src = app.post(
                "${mainUrl}/ajax/dataEmbed22.asp",
                headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                data = mapOf("id" to "${veri.id}")
            ).document.selectFirst("iframe")?.attr("src")?.trim().orEmpty()

            // bazi alternatifler (Pixel) reCAPTCHA kapisi dondurur, atla
            if (src.isEmpty() || src.contains("reCAPTCHA", ignoreCase = true)) continue
            val iframe = when {
                src.startsWith("//") -> "https:$src"
                src.startsWith("http") -> src
                else -> fixUrlNull(src) ?: continue
            }

            loadExtractor(iframe, "${mainUrl}/", subtitleCallback) { link ->
                callback.invoke(ExtractorLink(source = kaynakAdi, name = kaynakAdi, url = link.url, referer = link.referer, quality = link.quality, headers = link.headers, extractorData = link.extractorData, type = link.type))
            }
        }
    }

    data class Kaynak(val status: String = "", val data: List<KaynakData>? = null)
    data class KaynakData(val id: Int = 0, val baslik: String = "", val kalite: Int = 0)

    // /ajax/arama.asp cevabi: {status, results:{<kategori>:{name, results:[{title,url,image}]}}}
    data class Arama(val status: String = "", val results: Map<String, AramaGrup>? = null)
    data class AramaGrup(val name: String = "", val results: List<AramaSonuc>? = null)
    data class AramaSonuc(val title: String = "", val description: String = "", val url: String = "", val image: String = "")
    data class AspData(val alternatif: String, val embed: String)
}
