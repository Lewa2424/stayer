package com.example.stayer.pathnet.data

import android.content.Context
import com.example.stayer.pathnet.diagnostics.PathNetLogger
import org.osmdroid.config.Configuration
import org.osmdroid.config.IConfigurationProvider

/**
 * Инициализирует osmdroid по правилам OpenStreetMap tile usage policy.
 * Initializes osmdroid according to the OpenStreetMap tile usage policy.
 *
 * OSM блокирует запросы с generic/пустым User-Agent и без кэша тайлов.
 * OSM blocks requests with a generic/empty User-Agent and without tile caching.
 */
object OsmdroidInitializer {
    private const val PREFS_NAME = "osmdroid"
    private const val CONFIG_VERSION_KEY = "stayer.osm_config_version"
    private const val CONFIG_VERSION = 2
    private const val PROJECT_URL = "https://github.com/Lewa2424/stayer"
    private const val CONTACT_URL = "$PROJECT_URL/issues"
    private const val MEMORY_TILE_COUNT = 128
    private const val DOWNLOAD_QUEUE_SIZE = 20
    private const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024
    private const val DISK_CACHE_TRIM_BYTES = 192L * 1024 * 1024

    @Volatile
    var userAgent: String = "Stayer/1.0 (+$PROJECT_URL; contact: $CONTACT_URL)"
        private set

    private var initialized = false

    /**
     * Настраивает кэш и User-Agent до создания MapView.
     * Configures cache and User-Agent before any MapView is created.
     */
    fun init(context: Context) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            val appContext = context.applicationContext
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            userAgent = buildUserAgent(appContext)
            val config = Configuration.getInstance()
            config.load(appContext, prefs)
            purgeBlockedTileCacheIfNeeded(appContext, prefs, config)
            config.userAgentValue = userAgent
            config.cacheMapTileCount = MEMORY_TILE_COUNT.toShort()
            config.tileFileSystemCacheMaxBytes = DISK_CACHE_MAX_BYTES
            config.tileFileSystemCacheTrimBytes = DISK_CACHE_TRIM_BYTES
            config.tileDownloadThreads = 2
            config.tileDownloadMaxQueueSize = DOWNLOAD_QUEUE_SIZE.toShort()
            config.save(appContext, prefs)
            PathNetLogger.info("Osmdroid initialized: userAgent=$userAgent")
            initialized = true
        }
    }

    private fun buildUserAgent(context: Context): String {
        val version = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
        return "Stayer/$version (+$PROJECT_URL; contact: $CONTACT_URL)"
    }

    /**
     * Удаляет кэш тайлов после смены политики UA, чтобы не показывать сохранённые 403.
     * Clears tile cache after UA policy changes so cached 403 tiles are not reused.
     */
    private fun purgeBlockedTileCacheIfNeeded(
        context: Context,
        prefs: android.content.SharedPreferences,
        config: IConfigurationProvider,
    ) {
        val storedVersion = prefs.getInt(CONFIG_VERSION_KEY, 0)
        if (storedVersion >= CONFIG_VERSION) return

        val tileCacheDir = config.getOsmdroidTileCache(context)
        if (tileCacheDir.exists()) {
            tileCacheDir.deleteRecursively()
            tileCacheDir.mkdirs()
            PathNetLogger.warn("Osmdroid tile cache purged after config upgrade")
        }
        prefs.edit().putInt(CONFIG_VERSION_KEY, CONFIG_VERSION).apply()
    }
}
