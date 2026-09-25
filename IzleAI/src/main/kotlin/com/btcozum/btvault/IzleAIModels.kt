@file:Suppress("DEPRECATION", "DEPRECATION_ERROR")
// ! Bu arac @keyiflerolsun tarafindan | @KekikAkademi icin yazilmistir.
// selcukflix.com (720PizleAI) - Next.js gecisinden sonraki sifreli JSON sozlesmesi.

package com.btcozum.btvault

import com.fasterxml.jackson.annotation.JsonProperty

/** POST /api/bg/ uclari yanit zarfi: { "response": "<AES-256-CBC ile sifrelenmis base64>" } */
data class IaiEnvelope(
    @JsonProperty("response") val response: String? = null
)

/** Sifre cozulmus liste / arama sonucu. */
data class IaiList(
    @JsonProperty("state")  val state: Boolean?          = null,
    @JsonProperty("result") val result: List<IaiItem>?   = null
)

/**
 * Hem /api/bg/findMovies (original_title, poster_url, release_year)
 * hem de /api/bg/searchContent (object_name, object_poster_url, object_release_year)
 * sonuclarini karsilar.
 */
data class IaiItem(
    @JsonProperty("used_slug")           val slug: String?          = null,
    @JsonProperty("used_title")          val usedTitle: String?     = null,
    @JsonProperty("used_type")           val type: String?          = null,
    @JsonProperty("original_title")      val originalTitle: String? = null,
    @JsonProperty("object_name")         val objectName: String?    = null,
    @JsonProperty("poster_url")          val poster: String?        = null,
    @JsonProperty("object_poster_url")   val objectPoster: String?  = null,
    @JsonProperty("release_year")        val releaseYear: Int?      = null,
    @JsonProperty("object_release_year") val objectYear: Int?       = null
)

/** __NEXT_DATA__.props.pageProps.secureData -> contentItem */
data class IaiContentItem(
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("culture_title")  val cultureTitle: String?  = null,
    @JsonProperty("release_year")   val year: Int?             = null,
    @JsonProperty("total_minutes")  val minutes: Int?          = null,
    @JsonProperty("imdb_point")     val imdb: Double?          = null,
    @JsonProperty("trailer")        val trailer: String?       = null,
    @JsonProperty("poster_url")     val poster: String?        = null,
    @JsonProperty("description")    val description: String?   = null,
    @JsonProperty("categories")     val categories: String?    = null
)

/** RelatedResults.getMovieCastsById.result[] */
data class IaiCast(
    @JsonProperty("name")       val name: String?  = null,
    @JsonProperty("cast_image") val image: String? = null
)

/** RelatedResults.getMovie(Source|PartSources)ById.result[] */
data class IaiSource(
    @JsonProperty("source_name")    val name: String?    = null,
    @JsonProperty("source_content") val content: String?  = null,
    @JsonProperty("quality_name")   val quality: String?  = null
)
