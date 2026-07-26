package com.example.stayer.pathnet.data

import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource

/**
 * MAPNIK без normalized User-Agent: osmdroid иначе шлёт package/version вместо Stayer/...
 * MAPNIK without normalized User-Agent: osmdroid otherwise sends package/version instead of Stayer/...
 */
object StayerMapnikTileSource {
  val instance = XYTileSource(
    "StayerMapnik",
    0,
    19,
    256,
    ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors",
    TileSourcePolicy(
      2,
      TileSourcePolicy.FLAG_NO_BULK or
        TileSourcePolicy.FLAG_NO_PREVENTIVE or
        TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL,
    ),
  )
}
