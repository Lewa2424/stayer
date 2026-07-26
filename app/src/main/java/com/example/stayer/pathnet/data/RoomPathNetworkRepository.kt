package com.example.stayer.pathnet.data

import com.example.stayer.pathnet.data.local.ImportedPathNodeEntity
import com.example.stayer.pathnet.data.local.ImportedWayEntity
import com.example.stayer.pathnet.data.local.LoadedAreaEntity
import com.example.stayer.pathnet.data.local.PathEdgeEntity
import com.example.stayer.pathnet.data.local.PathNetDao
import com.example.stayer.pathnet.data.local.PathNodeEntity
import com.example.stayer.pathnet.data.local.StoredGeoPoint
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.ImportedPathGraph
import com.example.stayer.pathnet.model.ImportedWay
import com.example.stayer.pathnet.model.LoadedArea
import com.example.stayer.pathnet.model.PathEdge
import com.example.stayer.pathnet.model.PathEdgeSource
import com.example.stayer.pathnet.model.PathGraph
import com.example.stayer.pathnet.model.PathNode

/**
 * Room-реализация репозитория сети.
 * Room-backed repository for the path network.
 */
class RoomPathNetworkRepository(
    private val dao: PathNetDao,
) : PathNetworkRepository {
    /**
     * Загружает сохраненный граф из Room.
     * Loads the stored graph from Room.
     */
    override suspend fun loadGraph(): PathGraph {
        val nodes = dao.getNodes().associate { entity ->
            entity.id to PathNode(
                id = entity.id,
                point = GeoPoint(entity.lat, entity.lon),
            )
        }
        val edges = dao.getEdges().associate { entity ->
            entity.id to PathEdge(
                id = entity.id,
                startNodeId = entity.startNodeId,
                endNodeId = entity.endNodeId,
                geometry = entity.geometry.map { GeoPoint(it.lat, it.lon) },
                lengthMeters = entity.lengthMeters,
                source = PathEdgeSource.valueOf(entity.source),
            )
        }
        return PathGraph(nodes = nodes, edges = edges)
    }

    /**
     * Загружает сохраненные области.
     * Loads stored viewport metadata.
     */
    override suspend fun loadLoadedAreas(): List<LoadedArea> {
        return dao.getLoadedAreas().map { entity ->
            LoadedArea(
                id = entity.id,
                minLat = entity.minLat,
                minLon = entity.minLon,
                maxLat = entity.maxLat,
                maxLon = entity.maxLon,
                loadedAt = entity.loadedAt,
            )
        }
    }

    /**
     * Загружает сохраненный кэш импортированных тропинок.
     * Loads the persisted imported-path cache.
     */
    override suspend fun loadImportedGraph(): ImportedPathGraph {
        val nodes = dao.getImportedPathNodes().associate { entity ->
            entity.id to GeoPoint(entity.lat, entity.lon)
        }
        val ways = dao.getImportedPathWays().map { entity ->
            ImportedWay(
                id = entity.id,
                highwayType = entity.highwayType,
                nodeIds = entity.nodeIds,
            )
        }
        return ImportedPathGraph(nodes = nodes, ways = ways)
    }

    /**
     * Полностью сохраняет граф в базу.
     * Replaces the graph persisted in the database.
     */
    override suspend fun saveGraph(graph: PathGraph) {
        dao.replaceGraph(
            nodes = graph.nodes.values.map { node ->
                PathNodeEntity(
                    id = node.id,
                    lat = node.point.lat,
                    lon = node.point.lon,
                )
            },
            edges = graph.edges.values.map { edge ->
                PathEdgeEntity(
                    id = edge.id,
                    startNodeId = edge.startNodeId,
                    endNodeId = edge.endNodeId,
                    geometry = edge.geometry.map { StoredGeoPoint(it.lat, it.lon) },
                    lengthMeters = edge.lengthMeters,
                    source = edge.source.name,
                )
            },
        )
    }

    /**
     * Сохраняет кэш импортированных тропинок.
     * Persists the imported-path cache.
     */
    override suspend fun saveImportedGraph(graph: ImportedPathGraph) {
        dao.replaceImportedGraph(
            nodes = graph.nodes.map { (id, point) ->
                ImportedPathNodeEntity(
                    id = id,
                    lat = point.lat,
                    lon = point.lon,
                )
            },
            ways = graph.ways.map { way ->
                ImportedWayEntity(
                    id = way.id,
                    highwayType = way.highwayType,
                    nodeIds = way.nodeIds,
                )
            },
        )
    }

    /**
     * Сохраняет список областей.
     * Persists the loaded-area list.
     */
    override suspend fun saveLoadedAreas(areas: List<LoadedArea>) {
        dao.clearLoadedAreas()
        dao.insertLoadedAreas(
            areas.map { area ->
                LoadedAreaEntity(
                    id = area.id,
                    minLat = area.minLat,
                    minLon = area.minLon,
                    maxLat = area.maxLat,
                    maxLon = area.maxLon,
                    loadedAt = area.loadedAt,
                )
            },
        )
    }
}
