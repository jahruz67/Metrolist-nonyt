/**
 * Metrolist Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.metrolist.music.utils

import android.net.ConnectivityManager
import com.metrolist.innertube.YouTube
import com.metrolist.innertube.models.response.PlayerResponse
import com.metrolist.music.constants.AudioQuality
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import kotlin.math.abs

object NonYouTubeStreamResolver {
    private const val TAG = "NonYouTubeResolver"
    private const val DEFAULT_EXPIRY_SECONDS = 30 * 60
    private const val USER_AGENT = "Metrolist-Android/1.0"

    enum class Source(
        val clientName: String,
        val displayName: String,
        val baseUrl: String,
    ) {
        QOBUZ_KENNYY(
            clientName = "QOBUZ_KENNYY",
            displayName = "Qobuz (kennyy)",
            baseUrl = "https://qobuz.kennyy.com.br/api",
        ),
        QOBUZ_SQUID(
            clientName = "QOBUZ_SQUID",
            displayName = "Qobuz (squid.wtf)",
            baseUrl = "https://qobuz.squid.wtf/api",
        ),
    }

    data class ResolvedStream(
        val source: Source,
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val sampleRate: Int?,
        val contentLength: Long,
        val expiresInSeconds: Int,
    ) {
        fun toFormat(durationMs: String?): PlayerResponse.StreamingData.Format =
            PlayerResponse.StreamingData.Format(
                itag = source.ordinal + 90_000,
                url = url,
                mimeType = mimeType,
                bitrate = bitrate,
                width = null,
                height = null,
                contentLength = contentLength,
                quality = "tiny",
                fps = null,
                qualityLabel = null,
                averageBitrate = bitrate,
                audioQuality = "AUDIO_QUALITY_HIGH",
                approxDurationMs = durationMs,
                audioSampleRate = sampleRate,
                audioChannels = 2,
                loudnessDb = null,
                lastModified = null,
                signatureCipher = null,
                cipher = null,
                audioTrack = null,
            )
    }

    @Volatile
    var enabled = false

    @Volatile
    var disabledSources: Set<String> = emptySet()

    @Volatile
    var youtubeFallback = true

    private val json = Json { ignoreUnknownKeys = true }
    private val rateLimitMutex = Mutex()
    private var lastRequestAtMs = 0L

    private val client = OkHttpClient.Builder()
        .proxy(YouTube.proxy)
        .proxyAuthenticator { _, response ->
            YouTube.proxyAuth?.let { auth ->
                response.request.newBuilder()
                    .header("Proxy-Authorization", auth)
                    .build()
            } ?: response.request
        }
        .build()

    suspend fun resolve(
        title: String?,
        artist: String?,
        durationSeconds: Int?,
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): ResolvedStream? {
        if (!enabled || title.isNullOrBlank()) return null

        val query = listOfNotNull(
            artist?.takeIf { it.isNotBlank() },
            title,
        ).joinToString(" ")

        val sources = Source.entries.filterNot { it.clientName in disabledSources }
        for (source in sources) {
            runCatching {
                search(source, query)
                    .bestMatch(title = title, artist = artist, durationSeconds = durationSeconds)
                    ?.let { track ->
                        download(source, track.id, qualityCode(audioQuality, connectivityManager))
                    }
            }.onSuccess { stream ->
                if (stream != null) return stream
            }.onFailure { error ->
                Timber.tag(TAG).w(error, "${source.displayName} failed")
            }
        }

        return null
    }

    private suspend fun search(source: Source, query: String): List<QobuzTrack> {
        rateLimit()
        val url = source.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("get-music")
            .addQueryParameter("q", query)
            .addQueryParameter("offset", "0")
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("${source.displayName} search returned ${response.code}")
            val body = response.body?.string().orEmpty()
            return json.decodeFromString<QobuzSearchResponse>(body).data?.tracks?.items.orEmpty()
        }
    }

    private suspend fun download(
        source: Source,
        trackId: Long,
        quality: Int,
    ): ResolvedStream? {
        rateLimit()
        val url = source.baseUrl.toHttpUrl().newBuilder()
            .addPathSegment("download-music")
            .addQueryParameter("track_id", trackId.toString())
            .addQueryParameter("quality", quality.toString())
            .build()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("${source.displayName} download returned ${response.code}")
            val body = response.body?.string().orEmpty()
            val data = json.decodeFromString<QobuzDownloadResponse>(body).data ?: return null
            val streamUrl = data.url ?: return null
            val contentLength = contentLength(streamUrl) ?: return null
            return ResolvedStream(
                source = source,
                url = streamUrl,
                mimeType = data.mimeType(),
                bitrate = data.bitrate?.let { if (it < 10_000) it * 1000 else it } ?: 320_000,
                sampleRate = data.samplingRate,
                contentLength = contentLength,
                expiresInSeconds = streamUrl.expirySeconds(),
            )
        }
    }

    private fun List<QobuzTrack>.bestMatch(
        title: String,
        artist: String?,
        durationSeconds: Int?,
    ): QobuzTrack? =
        asSequence()
            .filter { it.streamable != false }
            .map { track -> track to track.score(title, artist, durationSeconds) }
            .filter { (_, score) -> score >= 0.5 }
            .maxByOrNull { (_, score) -> score }
            ?.first

    private fun QobuzTrack.score(
        wantedTitle: String,
        wantedArtist: String?,
        wantedDurationSeconds: Int?,
    ): Double {
        val titleScore = tokenSimilarity(title, wantedTitle)
        val artistScore = tokenSimilarity(performer?.name, wantedArtist).takeIf { wantedArtist != null } ?: 0.5
        val durationScore = if (wantedDurationSeconds != null && duration != null) {
            (1.0 - (abs(duration - wantedDurationSeconds).coerceAtMost(30) / 30.0)).coerceIn(0.0, 1.0)
        } else {
            0.5
        }
        return titleScore * 0.55 + artistScore * 0.25 + durationScore * 0.20
    }

    private fun tokenSimilarity(left: String?, right: String?): Double {
        if (left.isNullOrBlank() || right.isNullOrBlank()) return 0.0
        val leftTokens = left.normalizedTokens()
        val rightTokens = right.normalizedTokens()
        if (leftTokens.isEmpty() || rightTokens.isEmpty()) return 0.0
        val intersection = leftTokens.intersect(rightTokens).size.toDouble()
        val union = leftTokens.union(rightTokens).size.toDouble()
        return intersection / union
    }

    private fun String.normalizedTokens(): Set<String> =
        lowercase()
            .replace(Regex("""\([^)]*\)|\[[^]]*]"""), " ")
            .replace(Regex("""[^a-z0-9]+"""), " ")
            .split(" ")
            .filter { it.length > 1 }
            .toSet()

    private fun qualityCode(
        audioQuality: AudioQuality,
        connectivityManager: ConnectivityManager,
    ): Int =
        when {
            audioQuality == AudioQuality.LOW -> 5
            audioQuality == AudioQuality.AUTO && connectivityManager.isActiveNetworkMetered -> 5
            else -> 6
        }

    private fun contentLength(url: String): Long? {
        val request = Request.Builder()
            .head()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .build()
        return client.newCall(request).execute().use { response ->
            response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
        }
    }

    private fun String.expirySeconds(): Int {
        val expiryEpochSeconds = toHttpUrlOrNull()
            ?.queryParameter("etsp")
            ?.toLongOrNull()
            ?: return DEFAULT_EXPIRY_SECONDS
        val nowSeconds = System.currentTimeMillis() / 1000
        return (expiryEpochSeconds - nowSeconds).toInt().coerceAtLeast(60)
    }

    private suspend fun rateLimit() {
        rateLimitMutex.withLock {
            val elapsed = System.currentTimeMillis() - lastRequestAtMs
            if (elapsed < 1_000) delay(1_000 - elapsed)
            lastRequestAtMs = System.currentTimeMillis()
        }
    }

    @Serializable
    private data class QobuzSearchResponse(
        val success: Boolean? = null,
        val data: QobuzSearchData? = null,
    )

    @Serializable
    private data class QobuzSearchData(
        val tracks: QobuzTracks? = null,
    )

    @Serializable
    private data class QobuzTracks(
        val items: List<QobuzTrack> = emptyList(),
    )

    @Serializable
    private data class QobuzTrack(
        val id: Long,
        val title: String? = null,
        val album: QobuzAlbum? = null,
        val performer: QobuzPerformer? = null,
        val isrc: String? = null,
        val duration: Int? = null,
        val streamable: Boolean? = null,
    )

    @Serializable
    private data class QobuzAlbum(
        val title: String? = null,
    )

    @Serializable
    private data class QobuzPerformer(
        val name: String? = null,
    )

    @Serializable
    private data class QobuzDownloadResponse(
        val success: Boolean? = null,
        val data: QobuzDownloadData? = null,
    )

    @Serializable
    private data class QobuzDownloadData(
        val url: String? = null,
        val codec: String? = null,
        val bitrate: Int? = null,
        @SerialName("sampling_rate")
        val samplingRate: Int? = null,
        @SerialName("bit_depth")
        val bitDepth: Int? = null,
    ) {
        fun mimeType(): String =
            when (codec?.lowercase()) {
                "flac" -> "audio/flac; codecs=\"flac\""
                "mp3" -> "audio/mpeg; codecs=\"mp3\""
                else -> "audio/flac; codecs=\"flac\""
            }
    }
}
