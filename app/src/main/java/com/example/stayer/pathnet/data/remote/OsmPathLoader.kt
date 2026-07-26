package com.example.stayer.pathnet.data.remote

import android.content.Context
import com.example.stayer.pathnet.data.OsmdroidInitializer
import com.example.stayer.pathnet.diagnostics.PathNetLogger
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.ImportedPathGraph
import com.example.stayer.pathnet.model.ImportedWay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.max
import kotlin.math.min

/**
 * Загружает видимые пешеходные пути из OpenStreetMap через Overpass API.
 * Loads visible walkable paths from OpenStreetMap via Overpass API.
 */
class OsmPathLoader(context: Context) {
    private val statusEndpoint = "https://overpass-api.de/api/status"
    private val interpreterEndpoint = "https://overpass-api.de/api/interpreter"

    private val dispatcher = Dispatcher().apply {
        maxRequests = 1
        maxRequestsPerHost = 1
    }

    private val client = OkHttpClient.Builder()
        .dispatcher(dispatcher)
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val formContentType = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
    private val pathHighwayPattern = "^(footway|path|pedestrian|living_street|residential|service|unclassified)$"
    private val allowedHighwayTypes = setOf(
        "footway",
        "path",
        "pedestrian",
        "living_street",
        "residential",
        "service",
        "unclassified",
    )
    private val maxDirectLatSpan = 0.0030
    private val maxDirectLonSpan = 0.0040
    private val maxSplitDepth = 3
    private val partDelayMs = 500L
    private val defaultRateLimitCooldownMs = 45_000L
    private val defaultForbiddenCooldownMs = 15 * 60_000L
    private val maxServerCooldownMs = 24 * 60 * 60_000L
    private val cooldownStore = OverpassCooldownStore(context)

    @Volatile
    private var rateLimitUntilMillis: Long = cooldownStore.loadUntilMillis()

    /**
     * Результат загрузки тропинок с возможным частичным успехом.
     * Path loading result with optional partial-success warning.
     */
    data class PathLoadResult(
        val graph: ImportedPathGraph,
        val warningMessage: String? = null,
    )

    /**
     * Входные границы для диагностического теста.
     * Input bounds for the diagnostic test.
     */
    data class ViewportCheckBounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    ) {
        fun asLogString(): String {
            return PathNetLogger.bounds(minLat, minLon, maxLat, maxLon)
        }
    }

    /**
     * Запрашивает граф видимой области.
     * Requests an imported graph for the visible viewport.
     */
    suspend fun loadVisiblePaths(
        minLat: Double,
        minLon: Double,
        maxLat: Double,
        maxLon: Double,
    ): PathLoadResult = withContext(Dispatchers.IO) {
        val bounds = Bounds(minLat, minLon, maxLat, maxLon)
        val failures = mutableListOf<LoadFailure>()
        ensureNotCoolingDown()
        PathNetLogger.info("OSM loadVisiblePaths: bounds=${bounds.asLogString()}")

        val graph = try {
            loadBounds(
                bounds = bounds,
                depth = 0,
                failures = failures,
                allowPartial = true,
            )
        } catch (error: Exception) {
            if (failures.isNotEmpty()) {
                failures += LoadFailure(bounds = bounds, error = error)
            }
            throw IOException(describeFailure(error), error)
        }

        if (graph.ways.isEmpty() && graph.nodes.isEmpty() && failures.isNotEmpty()) {
            val lastFailure = failures.last().error
            throw IOException(describeFailure(lastFailure), lastFailure)
        }

        PathLoadResult(
            graph = graph,
            warningMessage = buildWarningMessage(failures),
        )
    }

    /**
     * Проверяет доступность Overpass тем же HTTP-стеком, что и рабочая загрузка.
     * Checks Overpass reachability using the same HTTP stack as the main loader.
     */
    suspend fun diagnoseOverpass(bounds: ViewportCheckBounds?): String = withContext(Dispatchers.IO) {
        val startedAt = System.currentTimeMillis()
        PathNetLogger.info("OSM diagnose start: bounds=${bounds?.asLogString() ?: "none"}")

        val statusRequest = Request.Builder()
            .url(statusEndpoint)
            .header("Accept", "text/plain")
            .header("User-Agent", OsmdroidInitializer.userAgent)
            .get()
            .build()

        val statusResponse = executeText(statusRequest)
        val statusLine = statusResponse.body
            .lineSequence()
            .firstOrNull()
            ?.take(120)
            ?: "empty status body"

        val queryBounds = bounds?.let(::toDiagnosticBounds) ?: defaultDiagnosticBounds
        val queryJson = request(buildOverpassRequest(queryBounds))
        val parsed = parseResponse(queryJson)
        val elapsed = System.currentTimeMillis() - startedAt

        val result = buildString {
            append("Overpass OK")
            append(": status=").append(statusResponse.code)
            append(", queryWays=").append(parsed.ways.size)
            append(", queryNodes=").append(parsed.nodes.size)
            append(", ").append(statusLine)
            append(", ").append(elapsed).append(" мс")
        }
        PathNetLogger.info("OSM diagnose success: $result")
        result
    }

    /**
     * Загружает область напрямую или рекурсивно делит её на меньшие части.
     * Loads one bounds directly or recursively splits it into smaller requests.
     */
    private suspend fun loadBounds(
        bounds: Bounds,
        depth: Int,
        failures: MutableList<LoadFailure>,
        allowPartial: Boolean,
    ): ImportedPathGraph {
        ensureNotCoolingDown()

        val shouldSplitBeforeRequest = depth < maxSplitDepth &&
            (bounds.latSpan > maxDirectLatSpan || bounds.lonSpan > maxDirectLonSpan)
        if (shouldSplitBeforeRequest) {
            PathNetLogger.debug("OSM split before request: depth=$depth, bounds=${bounds.asLogString()}")
            return loadSplitBounds(bounds, depth, failures, allowPartial)
        }

        return try {
            PathNetLogger.debug("OSM request: depth=$depth, bounds=${bounds.asLogString()}")
            parseResponse(request(buildOverpassRequest(bounds)))
        } catch (error: Exception) {
            if (depth < maxSplitDepth && shouldRetryWithSplit(error)) {
                PathNetLogger.warn(
                    "OSM retry with split: depth=$depth, bounds=${bounds.asLogString()}, reason=${error.message}",
                )
                loadSplitBounds(bounds, depth, failures, allowPartial)
            } else {
                PathNetLogger.error(
                    "OSM request failed: depth=$depth, bounds=${bounds.asLogString()}",
                    error,
                )
                throw error
            }
        }
    }

    /**
     * Делит bbox и загружает части последовательно, чтобы не плодить параллельные таймауты.
     * Splits a bbox and loads parts sequentially to avoid stacked timeouts.
     */
    private suspend fun loadSplitBounds(
        bounds: Bounds,
        depth: Int,
        failures: MutableList<LoadFailure>,
        allowPartial: Boolean,
    ): ImportedPathGraph {
        var result = ImportedPathGraph()
        var firstError: Exception? = null

        splitBounds(bounds).forEachIndexed { index, part ->
            if (index > 0) {
                delay(partDelayMs)
            }
            try {
                result = mergeGraphs(
                    result,
                    loadBounds(
                        bounds = part,
                        depth = depth + 1,
                        failures = failures,
                        allowPartial = allowPartial,
                    ),
                )
            } catch (error: Exception) {
                failures += LoadFailure(bounds = part, error = error)
                if (firstError == null) {
                    firstError = error
                }
                PathNetLogger.warn(
                    "OSM subrequest failed: depth=${depth + 1}, bounds=${part.asLogString()}, reason=${error.message}",
                )
                if (!allowPartial || error is OverpassRateLimitException && result.ways.isEmpty()) {
                    throw error
                }
            }
        }

        if (result.ways.isEmpty() && firstError != null) {
            throw firstError ?: IOException("Failed to load OSM paths")
        }
        return result
    }

    /**
     * Собирает Overpass query для одного bbox.
     * Builds an Overpass query for a single bbox.
     */
    private fun buildQuery(bounds: Bounds): String {
        return buildString {
            append("[out:json][timeout:15];(")
            append("way[\"highway\"~\"")
            append(pathHighwayPattern)
            append("\"](")
            append(bounds.minLat).append(',')
                .append(bounds.minLon).append(',')
                .append(bounds.maxLat).append(',')
                .append(bounds.maxLon)
            append(");")
            append(");out body;>;out skel qt;")
        }
    }

    /**
     * Готовит POST-запрос к Overpass interpreter.
     * Builds a POST request to the Overpass interpreter.
     */
    private fun buildOverpassRequest(bounds: Bounds): Request {
        val payload = "data=" + URLEncoder.encode(buildQuery(bounds), StandardCharsets.UTF_8.name())
        return Request.Builder()
            .url(interpreterEndpoint)
            .header("Accept", "application/json")
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .header("User-Agent", OsmdroidInitializer.userAgent)
            .post(payload.toRequestBody(formContentType))
            .build()
    }

    /**
     * Выполняет POST-запрос к Overpass endpoint с поддержкой реальной отмены.
     * Performs a POST request against an Overpass endpoint with real cancellation support.
     */
    private suspend fun request(request: Request): JSONObject = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)

        continuation.invokeOnCancellation {
            PathNetLogger.warn("OSM call cancelled: url=${request.url}")
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                response.use { currentResponse ->
                    try {
                        val body = currentResponse.body?.string().orEmpty()
                        if (!currentResponse.isSuccessful) {
                            throw httpFailure(currentResponse.code, body, currentResponse.header("Retry-After"))
                        }
                        val trimmedBody = body.trimStart()
                        if (!trimmedBody.startsWith("{")) {
                            throw describeUnexpectedBody(body)
                        }
                        PathNetLogger.debug(
                            "OSM raw response received: chars=${body.length}, code=${currentResponse.code}",
                        )
                        if (!continuation.isCancelled) {
                            continuation.resume(JSONObject(body))
                        }
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        })
    }

    /**
     * Выполняет текстовый HTTP-запрос с поддержкой отмены.
     * Performs a text HTTP request with cancellation support.
     */
    private suspend fun executeText(request: Request): TextResponse = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)

        continuation.invokeOnCancellation {
            PathNetLogger.warn("OSM text call cancelled: url=${request.url}")
            call.cancel()
        }

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (!continuation.isCancelled) {
                    continuation.resumeWithException(e)
                }
            }

            override fun onResponse(
                call: Call,
                response: Response,
            ) {
                response.use { currentResponse ->
                    try {
                        val body = currentResponse.body?.string().orEmpty()
                        if (!currentResponse.isSuccessful) {
                            throw httpFailure(currentResponse.code, body, currentResponse.header("Retry-After"))
                        }
                        if (!continuation.isCancelled) {
                            continuation.resume(TextResponse(currentResponse.code, body))
                        }
                    } catch (error: Exception) {
                        if (!continuation.isCancelled) {
                            continuation.resumeWithException(error)
                        }
                    }
                }
            }
        })
    }

    /**
     * Разбивает bbox на 4 квадранта для более лёгких запросов.
     * Splits a bbox into four smaller quadrants.
     */
    private fun splitBounds(bounds: Bounds): List<Bounds> {
        val midLat = (bounds.minLat + bounds.maxLat) / 2.0
        val midLon = (bounds.minLon + bounds.maxLon) / 2.0
        return listOf(
            Bounds(bounds.minLat, bounds.minLon, midLat, midLon),
            Bounds(bounds.minLat, midLon, midLat, bounds.maxLon),
            Bounds(midLat, bounds.minLon, bounds.maxLat, midLon),
            Bounds(midLat, midLon, bounds.maxLat, bounds.maxLon),
        )
    }

    /**
     * Объединяет несколько фрагментов временного графа без дублей.
     * Merges imported graph fragments without duplicates.
     */
    private fun mergeGraphs(
        left: ImportedPathGraph,
        right: ImportedPathGraph,
    ): ImportedPathGraph {
        return ImportedPathGraph(
            nodes = left.nodes + right.nodes,
            ways = (left.ways + right.ways).distinctBy { it.id },
        )
    }

    /**
     * Определяет ошибки, при которых имеет смысл уменьшить bbox и повторить загрузку.
     * Detects failures where splitting the request is worth trying.
     */
    private fun shouldRetryWithSplit(error: Exception): Boolean {
        return error !is OverpassRateLimitException &&
            error !is OverpassAccessDeniedException &&
            error !is OverpassServerBusyException &&
            isRetryable(error)
    }

    private fun isRetryable(error: Exception): Boolean {
        val message = error.message.orEmpty().lowercase()
        return message.contains("timeout") ||
            message.contains("timed out") ||
            message.contains("504") ||
            message.contains("502") ||
            message.contains("server is probably too busy") ||
            message.contains("dispatcher")
    }

    /**
     * Формирует понятное описание сбоя загрузки.
     * Builds a user-facing description for load failures.
     */
    private fun describeFailure(error: Exception?): String {
        return when (error) {
            is OverpassRateLimitException -> {
                val waitSeconds = max(1L, (error.retryAfterMillis + 999L) / 1000L)
                "Overpass временно ограничил запросы, подождите ${waitSeconds} с"
            }

            is OverpassAccessDeniedException -> {
                val waitMinutes = max(1L, (error.retryAfterMillis + 59_999L) / 60_000L)
                "Overpass отклонил запросы, повтор будет доступен через $waitMinutes мин"
            }

            is OverpassServerBusyException -> {
                "сервер перегружен: попробуйте ещё раз или уменьшите область"
            }

            null -> "ошибка загрузки"
            else -> {
                val message = error.message.orEmpty().lowercase()
                when {
                    message.contains("canceled") || message.contains("cancelled") -> "загрузка отменена"
                    message.contains("timeout") || message.contains("timed out") -> "timeout: попробуйте обновить ещё раз"
                    message.isNotBlank() -> error.message ?: "ошибка загрузки"
                    else -> "ошибка загрузки"
                }
            }
        }
    }

    /**
     * Формирует предупреждение при частичном успехе.
     * Builds a warning for partial-success loads.
     */
    private fun buildWarningMessage(failures: List<LoadFailure>): String? {
        if (failures.isEmpty()) return null
        val hasRateLimit = failures.any { it.error is OverpassRateLimitException }
        val hasAccessDenied = failures.any { it.error is OverpassAccessDeniedException }
        val hasServerBusy = failures.any { it.error is OverpassServerBusyException }
        return when {
            hasAccessDenied -> {
                val maxWaitMs = failures.mapNotNull { (it.error as? OverpassAccessDeniedException)?.retryAfterMillis }
                    .maxOrNull() ?: defaultForbiddenCooldownMs
                val waitMinutes = max(1L, (maxWaitMs + 59_999L) / 60_000L)
                "Часть области пропущена: Overpass отклонил запросы, подождите $waitMinutes мин"
            }

            hasRateLimit -> {
                val maxWaitMs = failures.mapNotNull { (it.error as? OverpassRateLimitException)?.retryAfterMillis }
                    .maxOrNull() ?: defaultRateLimitCooldownMs
                val waitSeconds = max(1L, (maxWaitMs + 999L) / 1000L)
                "Часть области пропущена: Overpass ограничил запросы, подождите ${waitSeconds} с"
            }

            hasServerBusy -> "Часть области пропущена: сервер Overpass перегружен"
            else -> "Часть области не загрузилась, но доступные тропинки сохранены"
        }
    }

    /**
     * Разбирает ответ Overpass во временный граф.
     * Parses an Overpass response into a temporary graph.
     */
    private fun parseResponse(json: JSONObject): ImportedPathGraph {
        val elements = json.optJSONArray("elements") ?: JSONArray()
        val nodes = linkedMapOf<String, GeoPoint>()
        val ways = mutableListOf<ImportedWay>()

        for (index in 0 until elements.length()) {
            val item = elements.getJSONObject(index)
            when (item.optString("type")) {
                "node" -> {
                    val id = item.optLong("id").toString()
                    if (item.has("lat") && item.has("lon")) {
                        nodes[id] = GeoPoint(
                            lat = item.getDouble("lat"),
                            lon = item.getDouble("lon"),
                        )
                    }
                }

                "way" -> {
                    val tags = item.optJSONObject("tags") ?: JSONObject()
                    val highwayType = tags.optString("highway")
                    if (highwayType !in allowedHighwayTypes) continue
                    val nodeIds = mutableListOf<String>()
                    val nodeArray = item.optJSONArray("nodes") ?: JSONArray()
                    for (nodeIndex in 0 until nodeArray.length()) {
                        nodeIds += nodeArray.getLong(nodeIndex).toString()
                    }
                    if (nodeIds.size >= 2) {
                        ways += ImportedWay(
                            id = item.optLong("id").toString(),
                            highwayType = highwayType,
                            nodeIds = nodeIds,
                        )
                    }
                }
            }
        }

        val result = ImportedPathGraph(nodes = nodes, ways = ways)
        PathNetLogger.info("OSM parse success: ways=${result.ways.size}, nodes=${result.nodes.size}")
        return result
    }

    private fun httpFailure(
        code: Int,
        body: String,
        retryAfterHeader: String?,
    ): IOException {
        return when (code) {
            403 -> {
                val retryAfterMs = parseRetryAfterMillis(retryAfterHeader, defaultForbiddenCooldownMs)
                registerRateLimit(retryAfterMs)
                OverpassAccessDeniedException(retryAfterMs, body)
            }

            429 -> {
                val retryAfterMs = parseRetryAfterMillis(retryAfterHeader, defaultRateLimitCooldownMs)
                registerRateLimit(retryAfterMs)
                OverpassRateLimitException(retryAfterMs, body)
            }

            502, 503, 504 -> OverpassServerBusyException(code, body)
            else -> IOException("Overpass HTTP $code")
        }
    }

    /**
     * Разбирает Retry-After как секунды или HTTP-дату и ограничивает небезопасные значения.
     * Parses Retry-After as seconds or an HTTP date and clamps unsafe values.
     */
    private fun parseRetryAfterMillis(header: String?, fallbackMillis: Long): Long {
        val normalized = header?.trim().orEmpty()
        val seconds = normalized.toLongOrNull()
        if (seconds != null) {
            val maxSeconds = maxServerCooldownMs / 1000L
            return seconds.coerceIn(1L, maxSeconds) * 1000L
        }

        val dateDelay = try {
            ZonedDateTime.parse(normalized, DateTimeFormatter.RFC_1123_DATE_TIME)
                .toInstant()
                .toEpochMilli() - System.currentTimeMillis()
        } catch (_: Exception) {
            null
        }
        return (dateDelay ?: fallbackMillis).coerceIn(1_000L, maxServerCooldownMs)
    }

    private fun describeUnexpectedBody(body: String): IOException {
        val normalized = body.lowercase()
        return when {
            normalized.contains("rate limit") || normalized.contains("too many requests") -> {
                registerRateLimit(defaultRateLimitCooldownMs)
                OverpassRateLimitException(defaultRateLimitCooldownMs, body)
            }

            normalized.contains("too busy") || normalized.contains("dispatcher") || normalized.contains("timeout") -> {
                OverpassServerBusyException(504, body)
            }

            else -> IOException("Overpass returned non-JSON response")
        }
    }

    private fun registerRateLimit(retryAfterMillis: Long) {
        rateLimitUntilMillis = max(rateLimitUntilMillis, System.currentTimeMillis() + retryAfterMillis)
        cooldownStore.extendUntilMillis(rateLimitUntilMillis)
        PathNetLogger.warn("OSM rate limit registered for ${retryAfterMillis} ms")
    }

    private fun ensureNotCoolingDown() {
        val remaining = rateLimitUntilMillis - System.currentTimeMillis()
        if (remaining > 0) {
            throw OverpassRateLimitException(remaining, "cooldown")
        }
    }

    /**
     * Границы запрашиваемой области карты.
     * Requested map bounds.
     */
    private data class Bounds(
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    ) {
        val latSpan: Double
            get() = maxLat - minLat

        val lonSpan: Double
            get() = maxLon - minLon

        fun asLogString(): String {
            return PathNetLogger.bounds(minLat, minLon, maxLat, maxLon)
        }
    }

    private data class TextResponse(
        val code: Int,
        val body: String,
    )

    private data class LoadFailure(
        val bounds: Bounds,
        val error: Exception,
    )

    private class OverpassRateLimitException(
        val retryAfterMillis: Long,
        responseBody: String,
    ) : IOException("Overpass HTTP 429: ${responseBody.take(120)}")

    private class OverpassAccessDeniedException(
        val retryAfterMillis: Long,
        responseBody: String,
    ) : IOException("Overpass HTTP 403: ${responseBody.take(120)}")

    private class OverpassServerBusyException(
        code: Int,
        responseBody: String,
    ) : IOException("Overpass HTTP $code: ${responseBody.take(120)}")

    private val defaultDiagnosticBounds = Bounds(
        minLat = 46.295540,
        minLon = 30.660934,
        maxLat = 46.297747,
        maxLon = 30.662574,
    )

    private fun toDiagnosticBounds(bounds: ViewportCheckBounds): Bounds {
        val centerLat = (bounds.minLat + bounds.maxLat) / 2.0
        val centerLon = (bounds.minLon + bounds.maxLon) / 2.0
        val latHalfSpan = max(0.00035, min((bounds.maxLat - bounds.minLat) / 4.0, 0.001))
        val lonHalfSpan = max(0.00035, min((bounds.maxLon - bounds.minLon) / 4.0, 0.001))
        return Bounds(
            minLat = centerLat - latHalfSpan,
            minLon = centerLon - lonHalfSpan,
            maxLat = centerLat + latHalfSpan,
            maxLon = centerLon + lonHalfSpan,
        )
    }
}
