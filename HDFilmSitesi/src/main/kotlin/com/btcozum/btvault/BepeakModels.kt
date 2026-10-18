package com.btcozum.btvault

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

data class BepeakCipher(
    @JsonProperty("ct") val ct: String,
    @JsonProperty("iv") val iv: String,
    @JsonProperty("s") val s: String,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BepeakData(
    @JsonProperty("video_location") val videoLocation: String? = null,
    @JsonProperty("referer") val referer: String? = null,
    @JsonProperty("strSubtitles") val strSubtitles: List<BepeakSubtitle>? = null,
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BepeakSubtitle(
    @JsonProperty("file") val file: String? = null,
    @JsonProperty("label") val label: String? = null,
)
