package com.example.stayer.pathnet.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.stayer.pathnet.data.StayerMapnikTileSource
import com.example.stayer.pathnet.diagnostics.PathNetLogger
import com.example.stayer.pathnet.model.GeoPoint
import com.example.stayer.pathnet.model.PathEdge
import com.example.stayer.pathnet.model.PathEditorMode
import com.example.stayer.pathnet.model.RouteMapUiState
import org.osmdroid.events.MapAdapter
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint as OsmGeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/**
 * View-обертка над osmdroid MapView.
 * Compose wrapper around osmdroid MapView.
 */
@Composable
fun RouteMapView(
    modifier: Modifier,
    state: RouteMapUiState,
    onMapTap: (GeoPoint) -> Unit,
    onAddControlPoint: (GeoPoint) -> Unit,
    onMoveControlPoint: (String, Int, GeoPoint) -> Unit,
    onViewportChanged: (Double, Double, Double, Double) -> Unit,
) {
    AndroidView(
        modifier = modifier,
        factory = { context -> createMapView(context, onViewportChanged) },
        update = { mapView ->
            renderMapState(
                mapView = mapView,
                state = state,
                onMapTap = onMapTap,
                onAddControlPoint = onAddControlPoint,
                onMoveControlPoint = onMoveControlPoint,
            )
            fitGraphIfRequested(mapView, state)
        },
        onRelease = { mapView ->
            PathNetLogger.info("MapView released")
            mapView.onDetach()
        },
    )
}

/**
 * Создает и конфигурирует MapView.
 * Creates and configures a MapView instance.
 */
private fun createMapView(
    context: Context,
    onViewportChanged: (Double, Double, Double, Double) -> Unit,
): MapView {
    PathNetLogger.info("MapView created")
    val mapView = MapView(context)
    mapView.setTileSource(StayerMapnikTileSource.instance)
    mapView.setMultiTouchControls(true)
    mapView.controller.setZoom(16.5)
    mapView.controller.setCenter(OsmGeoPoint(46.301, 30.654))
    mapView.addMapListener(object : MapAdapter() {
        override fun onScroll(event: ScrollEvent?): Boolean {
            notifyViewport(mapView, onViewportChanged)
            return super.onScroll(event)
        }

        override fun onZoom(event: ZoomEvent?): Boolean {
            notifyViewport(mapView, onViewportChanged)
            return super.onZoom(event)
        }
    })
    notifyViewport(mapView, onViewportChanged)
    return mapView
}

/**
 * Перерисовывает overlay-слои на карте.
 * Rebuilds dynamic map overlays from UI state.
 */
private fun renderMapState(
    mapView: MapView,
    state: RouteMapUiState,
    onMapTap: (GeoPoint) -> Unit,
    onAddControlPoint: (GeoPoint) -> Unit,
    onMoveControlPoint: (String, Int, GeoPoint) -> Unit,
) {
    mapView.overlays.clear()

    state.importedGraph.ways.forEach { way ->
        val points = way.nodeIds.mapNotNull(state.importedGraph.nodes::get)
        if (points.size >= 2) {
            mapView.overlays.add(
                Polyline(mapView).apply {
                    setPoints(points.map { OsmGeoPoint(it.lat, it.lon) })
                    outlinePaint.color = android.graphics.Color.argb(110, 120, 120, 120)
                    outlinePaint.strokeWidth = 4f
                    setInfoWindow(null)
                    setOnClickListener { _, _, _ -> false }
                },
            )
        }
    }

    state.graph.edges.values.forEach { edge ->
        mapView.overlays.add(createUserEdgePolyline(mapView, edge, state.selectedEdgeId == edge.id))
    }

    mapView.overlays.add(
        MapEventsOverlay(
            object : MapEventsReceiver {
                override fun singleTapConfirmedHelper(p: OsmGeoPoint): Boolean {
                    PathNetLogger.debug("Map single tap: lat=${p.latitude}, lon=${p.longitude}")
                    onMapTap(GeoPoint(p.latitude, p.longitude))
                    return true
                }

                override fun longPressHelper(p: OsmGeoPoint): Boolean {
                    if (state.mode == PathEditorMode.BEND && state.selectedEdgeId != null) {
                        PathNetLogger.debug(
                            "Map long press for bend: edge=${state.selectedEdgeId}, lat=${p.latitude}, lon=${p.longitude}",
                        )
                        onAddControlPoint(GeoPoint(p.latitude, p.longitude))
                        return true
                    }
                    return false
                }
            },
        ),
    )

    if (state.mode == PathEditorMode.BEND) {
        state.selectedEdgeId?.let { selectedId ->
            state.graph.edges[selectedId]?.let { edge ->
                addBendMarkers(mapView, edge, onAddControlPoint, onMoveControlPoint)
            }
        }
    }

    mapView.overlays.add(CopyrightOverlay(mapView.context))
    mapView.invalidate()
}

/**
 * Подгоняет карту под всю текущую сеть по запросу.
 * Fits the map to the full graph when requested.
 */
private fun fitGraphIfRequested(
    mapView: MapView,
    state: RouteMapUiState,
) {
    val tagKey = "fit_request_version"
    val previousVersion = mapView.getTag(tagKey.hashCode()) as? Long ?: -1L
    if (previousVersion == state.fitGraphRequestVersion) return
    mapView.setTag(tagKey.hashCode(), state.fitGraphRequestVersion)

    val allPoints = state.graph.edges.values.flatMap { it.geometry }
    if (allPoints.isEmpty()) return

    val lats = allPoints.map { it.lat }
    val lons = allPoints.map { it.lon }
    val box = BoundingBox(
        lats.max(),
        lons.max(),
        lats.min(),
        lons.min(),
    )
    PathNetLogger.info("Map fit graph: points=${allPoints.size}")
    mapView.zoomToBoundingBox(box, true, 96)
}

/**
 * Создает polyline пользовательского сегмента.
 * Creates a polyline overlay for a user edge.
 */
private fun createUserEdgePolyline(
    mapView: MapView,
    edge: PathEdge,
    selected: Boolean,
): Polyline {
    return Polyline(mapView).apply {
        setPoints(edge.geometry.map { OsmGeoPoint(it.lat, it.lon) })
        outlinePaint.color = if (selected) {
            android.graphics.Color.rgb(0, 128, 255)
        } else {
            android.graphics.Color.rgb(41, 98, 255)
        }
        outlinePaint.strokeWidth = if (selected) 12f else 8f
        setInfoWindow(null)
        setOnClickListener { _, _, _ -> false }
    }
}

/**
 * Добавляет draggable-маркеры для изгиба сегмента.
 * Adds draggable markers for manual edge bending.
 */
private fun addBendMarkers(
    mapView: MapView,
    edge: PathEdge,
    onAddControlPoint: (GeoPoint) -> Unit,
    onMoveControlPoint: (String, Int, GeoPoint) -> Unit,
) {
    if (edge.geometry.size == 2) {
        val midpoint = GeoPoint(
            lat = (edge.geometry[0].lat + edge.geometry[1].lat) / 2.0,
            lon = (edge.geometry[0].lon + edge.geometry[1].lon) / 2.0,
        )
        mapView.overlays.add(
            createHandleMarker(
                mapView = mapView,
                point = midpoint,
                onDragEnd = { moved ->
                    onAddControlPoint(moved)
                },
            ),
        )
        return
    }

    for (index in 1 until edge.geometry.lastIndex) {
        val point = edge.geometry[index]
        mapView.overlays.add(
            createHandleMarker(
                mapView = mapView,
                point = point,
                onDragEnd = { moved ->
                    onMoveControlPoint(edge.id, index, moved)
                },
            ),
        )
    }
}

/**
 * Создает нейтральный маркер-точку для изгиба без стандартного bubble UI.
 * Creates a neutral bend handle marker without the default bubble UI.
 */
private fun createHandleMarker(
    mapView: MapView,
    point: GeoPoint,
    onDragEnd: (GeoPoint) -> Unit,
): Marker {
    val size = 36
    val icon = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(android.graphics.Color.WHITE)
        setStroke(4, android.graphics.Color.rgb(0, 128, 255))
        setSize(size, size)
        setBounds(0, 0, size, size)
    }
    return Marker(mapView).apply {
        position = OsmGeoPoint(point.lat, point.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        this.icon = icon
        title = null
        subDescription = null
        snippet = null
        infoWindow = null
        isDraggable = true
        setOnMarkerDragListener(object : Marker.OnMarkerDragListener {
            override fun onMarkerDrag(marker: Marker?) = Unit
            override fun onMarkerDragStart(marker: Marker?) {
                PathNetLogger.debug("Bend marker drag start")
                mapView.setMultiTouchControls(false)
            }
            override fun onMarkerDragEnd(marker: Marker?) {
                mapView.setMultiTouchControls(true)
                marker ?: return
                PathNetLogger.info(
                    "Bend marker drag end: lat=${marker.position.latitude}, lon=${marker.position.longitude}",
                )
                onDragEnd(GeoPoint(marker.position.latitude, marker.position.longitude))
            }
        })
        setOnMarkerClickListener { _, _ -> true }
    }
}

/**
 * Передает текущий bbox карты наружу.
 * Emits the current map viewport bounding box.
 */
private fun notifyViewport(
    mapView: MapView,
    onViewportChanged: (Double, Double, Double, Double) -> Unit,
) {
    val box: BoundingBox = mapView.boundingBox ?: return
    PathNetLogger.debug(
        "Map viewport changed: ${PathNetLogger.bounds(box.latSouth, box.lonWest, box.latNorth, box.lonEast)}",
    )
    onViewportChanged(
        box.latSouth,
        box.lonWest,
        box.latNorth,
        box.lonEast,
    )
}
