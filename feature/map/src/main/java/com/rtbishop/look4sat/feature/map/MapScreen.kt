/*
 * Look4Sat. Amateur radio satellite tracker and pass predictor.
 * Copyright (C) 2019-2026 Arty Bishop and contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.rtbishop.look4sat.feature.map

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import androidx.collection.LruCache
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.core.graphics.toColorInt
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rtbishop.look4sat.core.domain.model.MapSource
import com.rtbishop.look4sat.core.domain.predict.GeoPos
import com.rtbishop.look4sat.core.domain.predict.OrbitalObject
import com.rtbishop.look4sat.core.domain.predict.OrbitalPos
import com.rtbishop.look4sat.core.domain.repository.IContainerProvider
import com.rtbishop.look4sat.core.presentation.IconCard
import com.rtbishop.look4sat.core.presentation.NextPassRow
import com.rtbishop.look4sat.core.presentation.R
import com.rtbishop.look4sat.core.presentation.TimerRow
import com.rtbishop.look4sat.core.presentation.TopBar
import com.rtbishop.look4sat.core.presentation.isVerticalLayout
import com.rtbishop.look4sat.core.presentation.layoutPadding
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File
import java.net.URLEncoder

// Overlay indices
private const val OVERLAY_TDT_LABELS = 0
private const val OVERLAY_STATION = 1
private const val OVERLAY_TRACK = 2
private const val OVERLAY_FOOTPRINT = 3
private const val OVERLAY_POSITIONS = 4
private const val OVERLAY_SUN = 5
private const val OVERLAY_MOON = 6
private const val OVERLAY_COUNT = 7

private val minLat = MapView.getTileSystem().minLatitude
private val maxLat = MapView.getTileSystem().maxLatitude
private val offlineTileSource = XYTileSource("tiles", 0, 6, 256, ".webp", emptyArray<String>())
private const val OFFLINE_MAX_ZOOM = 7.0
private const val OSM_MAX_ZOOM = 19.0
private const val TIANDITU_MAX_ZOOM = 18.0
private const val TILE_CACHE_MAX_BYTES = 1024L * 1024L * 1024L
private const val TILE_CACHE_TRIM_BYTES = 900L * 1024L * 1024L
private const val TILE_REFRESH_DELAY_MS = 250L
private const val TILE_SOURCE_OSM = "osm"
private const val TILE_USER_AGENT = "Look4Sat-CNMap"
private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeWidth = 4f
    style = Paint.Style.STROKE
    color = "#1565C0".toColorInt()
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val footprintOutlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    strokeWidth = 4f
    style = Paint.Style.STROKE
    color = "#00897B".toColorInt()
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    textSize = 36f
    style = Paint.Style.FILL
    color = "#FFE082".toColorInt()
    setShadowLayer(3f, 3f, 3f, Color.BLACK)
}
private val iconCache = LruCache<String, Drawable>(128)
private val sunIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    colorFilter =
        android.graphics.PorterDuffColorFilter("#FFE082".toColorInt(), android.graphics.PorterDuff.Mode.SRC_IN)
}
private val moonIconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    colorFilter =
        android.graphics.PorterDuffColorFilter("#E0E0E0".toColorInt(), android.graphics.PorterDuff.Mode.SRC_IN)
}

@Composable
fun MapDestination() {
    val context = LocalContext.current
    val container = (context.applicationContext as IContainerProvider).getMainContainer()
    val viewModel: MapViewModel = viewModel(factory = MapViewModel.factory(container))
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val mapView = rememberMapViewWithLifecycle()
    MapScreen(uiState, viewModel::onAction, mapView)
}

@Composable
private fun MapScreen(uiState: MapState, onAction: (MapAction) -> Unit, mapView: MapView) {
    val rotateMod = Modifier.rotate(180f)
    val timeString = uiState.mapData?.aosTime ?: "00:00:00"
    val isTimeAos = uiState.mapData?.isTimeAos ?: true
    val usesTianditu = tiandituLayers(uiState.mapSource) != null && uiState.tiandituKey.isNotBlank()
    val copyrightResId = if (usesTianditu) R.string.map_copyright_tianditu
    else R.string.map_copyright_osm

    LaunchedEffect(uiState.track) {
        val firstPos = uiState.track?.firstOrNull()?.firstOrNull() ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(firstPos.latitude, firstPos.longitude))
    }
    LaunchedEffect(uiState.mapSource, uiState.tiandituKey) {
        configureTileSources(mapView, uiState.mapSource, uiState.tiandituKey)
    }
    Column(modifier = Modifier.layoutPadding(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val isVertical = isVerticalLayout()
        if (isVertical) {
            TopBar {
                IconCard(action = { onAction(MapAction.SelectPrev) }, resId = R.drawable.ic_arrow, modifier = rotateMod)
                TimerRow(timeString = timeString, isTimeAos = isTimeAos)
                IconCard(action = { onAction(MapAction.SelectNext) }, resId = R.drawable.ic_arrow)
            }
            TopBar { NextPassRow(pass = uiState.orbitalPass, isUtc = uiState.isUtc) }
        } else {
            TopBar {
                IconCard(action = { onAction(MapAction.SelectPrev) }, resId = R.drawable.ic_arrow, modifier = rotateMod)
                TimerRow(timeString = timeString, isTimeAos = isTimeAos)
                NextPassRow(pass = uiState.orbitalPass, modifier = Modifier.weight(1f), isUtc = uiState.isUtc)
                IconCard(action = { onAction(MapAction.SelectNext) }, resId = R.drawable.ic_arrow)
            }
        }
        ElevatedCard(modifier = Modifier.weight(1f)) {
            Box(contentAlignment = Alignment.BottomCenter) {
                AndroidView({ mapView }) { view ->
                    uiState.stationPosition?.let { setStationPosition(it, view) }
                    uiState.track?.let { setSatelliteTrack(it, view) }
                    uiState.footprint?.let { setFootprint(it, view) }
                    uiState.positions?.let { setPositions(it, view) { item -> onAction(MapAction.SelectItem(item)) } }
                    setSubSolarPoint(uiState.sunLatDeg, uiState.sunLonDeg, view)
                    setMoonPosition(uiState.moonLatDeg, uiState.moonLonDeg, view)
                    view.invalidate()
                }
                uiState.mapData?.let { mapData ->
                    if (isVertical) MapDataCard(mapData, copyrightResId) else MapDataCards(mapData, copyrightResId)
                }
            }
        }
    }
}

// region Map data composables
@Composable
private fun MapDataCard(data: MapData, copyrightResId: Int) {
    val textColor = MaterialTheme.colorScheme.primary
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = stringResource(copyrightResId), fontSize = 14.sp)
        Card(colors = cardColors) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                MapDataRow(
                    stringResource(R.string.map_azimuth, data.azimuth) to textColor,
                    stringResource(R.string.map_elevation, data.elevation) to textColor
                )
                MapDataRow(
                    stringResource(R.string.map_altitude, data.altitude) to null,
                    stringResource(R.string.map_distance, data.range) to null
                )
                MapDataRow(
                    stringResource(R.string.map_latitude, data.osmPos.latitude) to textColor,
                    stringResource(R.string.map_longitude, data.osmPos.longitude) to textColor
                )
                MapDataRow(
                    stringResource(R.string.map_qth, data.qthLoc) to null,
                    stringResource(R.string.map_phase, data.phase) to null
                )
            }
        }
    }
}

@Composable
private fun MapDataRow(
    left: Pair<String, androidx.compose.ui.graphics.Color?>,
    right: Pair<String, androidx.compose.ui.graphics.Color?>
) {
    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
        if (left.second != null) Text(text = left.first, color = left.second!!)
        else Text(text = left.first)
        if (right.second != null) Text(text = right.first, color = right.second!!)
        else Text(text = right.first)
    }
}

@Composable
private fun MapDataCards(data: MapData, copyrightResId: Int) {
    val cardColors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLowest)
    val paddingMod = Modifier
        .padding(horizontal = 8.dp, vertical = 4.dp)
        .width(160.dp)
    val textColor = MaterialTheme.colorScheme.primary
    Box(modifier = Modifier.fillMaxSize()) {
        Card(colors = cardColors, modifier = Modifier.align(Alignment.TopStart)) {
            Column(horizontalAlignment = Alignment.Start, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_azimuth, data.azimuth), color = textColor)
                Text(text = stringResource(R.string.map_elevation, data.elevation))
            }
        }
        Card(colors = cardColors, modifier = Modifier.align(Alignment.TopEnd)) {
            Column(horizontalAlignment = Alignment.End, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_altitude, data.altitude), color = textColor)
                Text(text = stringResource(R.string.map_distance, data.range))
            }
        }
        Card(colors = cardColors, modifier = Modifier.align(Alignment.BottomStart)) {
            Column(horizontalAlignment = Alignment.Start, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_phase, data.phase), color = textColor)
                Text(text = stringResource(R.string.map_qth, data.qthLoc))
            }
        }
        Text(
            text = stringResource(copyrightResId),
            fontSize = 14.sp,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
        Card(colors = cardColors, modifier = Modifier.align(Alignment.BottomEnd)) {
            Column(horizontalAlignment = Alignment.End, modifier = paddingMod) {
                Text(text = stringResource(R.string.map_latitude, data.osmPos.latitude), color = textColor)
                Text(text = stringResource(R.string.map_longitude, data.osmPos.longitude))
            }
        }
    }
}
// endregion

// region Tile sources
private var configuredTileMapView: MapView? = null
private var configuredTileSource: String? = null

private fun configureTileSources(mapView: MapView, mapSource: String, tiandituKey: String) {
    val key = tiandituKey.trim()
    val layers = tiandituLayers(mapSource)
    val tileSourceKey = if (layers == null || key.isBlank()) {
        TILE_SOURCE_OSM
    } else {
        "tianditu:${layers.base}:${layers.label}:$key"
    }
    if (configuredTileMapView === mapView && configuredTileSource == tileSourceKey) {
        requestTileRefresh(mapView)
        return
    }
    configuredTileMapView = mapView
    configuredTileSource = tileSourceKey

    if (layers == null || key.isBlank()) {
        mapView.setUseDataConnection(true)
        mapView.setTileProvider(MapTileProviderBasic(mapView.context, TileSourceFactory.MAPNIK))
        mapView.maxZoomLevel = OSM_MAX_ZOOM
        mapView.overlayManager.tilesOverlay.applyTileOverlayDefaults()
        mapView.overlays[OVERLAY_TDT_LABELS] = FolderOverlay()
        requestTileRefresh(mapView)
        return
    }
    mapView.setUseDataConnection(true)
    mapView.setTileProvider(MapTileProviderBasic(mapView.context, TiandituTileSource(layer = layers.base, key = key)))
    mapView.maxZoomLevel = TIANDITU_MAX_ZOOM
    mapView.overlayManager.tilesOverlay.applyTileOverlayDefaults()
    mapView.overlays[OVERLAY_TDT_LABELS] = TilesOverlay(
        MapTileProviderBasic(mapView.context, TiandituTileSource(layer = layers.label, key = key)),
        mapView.context
    ).apply {
        applyTileOverlayDefaults()
        setUseDataConnection(true)
    }
    requestTileRefresh(mapView)
}

private data class TiandituLayers(val base: String, val label: String)

private fun tiandituLayers(mapSource: String): TiandituLayers? = when (MapSource.normalize(mapSource)) {
    MapSource.TIANDITU_VECTOR -> TiandituLayers(base = "vec", label = "cva")
    MapSource.TIANDITU_IMAGE -> TiandituLayers(base = "img", label = "cia")
    else -> null
}

private fun TilesOverlay.applyTileOverlayDefaults() {
    setColorFilter(null)
    setLoadingBackgroundColor(Color.TRANSPARENT)
    setLoadingLineColor(Color.TRANSPARENT)
}

private fun requestTileRefresh(mapView: MapView) {
    mapView.post {
        mapView.requestLayout()
        mapView.invalidate()
        mapView.postInvalidate()
    }
    mapView.postDelayed({
        mapView.invalidate()
        mapView.postInvalidate()
    }, TILE_REFRESH_DELAY_MS)
}

private fun buildTiandituWmtsUrl(
    baseUrl: String,
    layer: String,
    zoom: Int,
    row: Int,
    col: Int,
    encodedKey: String
): String = "$baseUrl?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER=$layer" +
    "&STYLE=default&TILEMATRIXSET=w&FORMAT=tiles&TILEMATRIX=$zoom&TILEROW=$row&TILECOL=$col&tk=$encodedKey"

private class TiandituTileSource(
    private val layer: String,
    private val key: String
) : OnlineTileSourceBase(
    "tianditu-wmts-https-$layer",
    1,
    18,
    256,
    ".png",
    Array(8) { index -> "https://t$index.tianditu.gov.cn/${layer}_w/wmts" },
    "Tianditu"
) {
    private val encodedKey = URLEncoder.encode(key, Charsets.UTF_8.name())

    override fun getTileURLString(pMapTileIndex: Long): String {
        val zoom = MapTileIndex.getZoom(pMapTileIndex)
        val row = MapTileIndex.getY(pMapTileIndex)
        val col = MapTileIndex.getX(pMapTileIndex)
        return buildTiandituWmtsUrl(
            baseUrl = getBaseUrl(),
            layer = layer,
            zoom = zoom,
            row = row,
            col = col,
            encodedKey = encodedKey
        )
    }
}
// endregion

// region Map overlay helpers
private fun setStationPosition(stationPos: GeoPos, mapView: MapView) {
    try {
        val overlay = mapView.overlays[OVERLAY_STATION]
        if (overlay is Marker) {
            overlay.position = GeoPoint(stationPos.latitude, stationPos.longitude)
        } else {
            mapView.overlays[OVERLAY_STATION] = Marker(mapView).apply {
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = ContextCompat.getDrawable(mapView.context, R.drawable.ic_position)
                position = GeoPoint(stationPos.latitude, stationPos.longitude)
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/** Pool of reusable Marker objects keyed by satellite name, to avoid re-creation every frame */
private val markerPool = HashMap<String, Marker>()
private var lastMapView: MapView? = null
private var lastSatelliteTrack: List<List<GeoPos>>? = null
private var satelliteTrackOverlay: FolderOverlay? = null

private fun setPositions(
    posMap: Map<OrbitalObject, GeoPos>,
    mapView: MapView,
    action: (OrbitalObject) -> Unit
) {
    try {
        // Clear caches when the MapView instance changes (e.g. config change)
        if (lastMapView !== mapView) {
            lastMapView = mapView
            markerPool.clear()
            iconCache.evictAll()
            lastSatelliteTrack = null
            satelliteTrackOverlay = null
            footprintOverlay = null
        }
        // Reuse the existing FolderOverlay — creating a new one and replacing it
        // causes osmdroid to detach shared Marker objects, making them invisible.
        val folder = mapView.overlays[OVERLAY_POSITIONS] as? FolderOverlay ?: FolderOverlay().also {
            mapView.overlays[OVERLAY_POSITIONS] = it
        }
        folder.items.clear()

        val activeNames = HashSet<String>(posMap.size)
        posMap.forEach { (satellite, geoPos) ->
            val name = satellite.data.name
            activeNames.add(name)
            val marker = markerPool.getOrPut(name) {
                Marker(mapView).apply {
                    setInfoWindow(null)
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                    icon = getCachedTextIcon(name, mapView)
                }
            }
            // Update position in-place — reuse existing GeoPoint if available
            val pos = marker.position
            if (pos != null) {
                pos.latitude = geoPos.latitude
                pos.longitude = geoPos.longitude
            } else {
                marker.position = GeoPoint(geoPos.latitude, geoPos.longitude)
            }
            marker.setOnMarkerClickListener { _, _ ->
                action(satellite)
                true
            }
            folder.add(marker)
        }
        // Evict markers for satellites no longer tracked
        val iter = markerPool.keys.iterator()
        while (iter.hasNext()) {
            if (iter.next() !in activeNames) iter.remove()
        }
    } catch (e: Exception) {
        println(e)
    }
}

private fun getCachedTextIcon(name: String, mapView: MapView): Drawable {
    iconCache[name]?.let { return it }
    val labelRect = Rect()
    textPaint.getTextBounds(name, 0, name.length, labelRect)
    val iconSize = 10f
    val width = labelRect.width() + iconSize * 2f
    val height = textPaint.textSize * 3f + iconSize * 2f
    val bitmap = createBitmap(width.toInt(), height.toInt())
    Canvas(bitmap).run {
        drawCircle(width / 2f, height / 2f, iconSize, textPaint)
        drawText(name, iconSize / 2f, height - iconSize, textPaint)
    }
    val drawable = bitmap.toDrawable(mapView.context.resources)
    iconCache.put(name, drawable)
    return drawable
}

private fun setSatelliteTrack(satTrack: List<List<GeoPos>>, mapView: MapView) {
    val cachedOverlay = satelliteTrackOverlay
    if (lastSatelliteTrack === satTrack && cachedOverlay != null && mapView.overlays[OVERLAY_TRACK] === cachedOverlay) {
        return
    }
    val trackOverlay = FolderOverlay()
    try {
        satTrack.forEach { track ->
            Polyline().apply {
                setPoints(track.map { GeoPoint(it.latitude, it.longitude) })
                outlinePaint.set(trackPaint)
                trackOverlay.add(this)
            }
        }
        lastSatelliteTrack = satTrack
        satelliteTrackOverlay = trackOverlay
        mapView.overlays[OVERLAY_TRACK] = trackOverlay
    } catch (e: Exception) {
        println(e)
    }
}

/** Reusable footprint overlay - split at the date line so wrapped maps don't drop the circle. */
private var footprintOverlay: FolderOverlay? = null

private fun setFootprint(orbitalPos: OrbitalPos, mapView: MapView) {
    try {
        val rangeCircle = orbitalPos.getRangeCircle()
        val overlay = footprintOverlay ?: FolderOverlay().also { footprintOverlay = it }
        overlay.items.clear()
        splitClosedPathOnDateLine(rangeCircle).forEach { segment ->
            if (segment.size > 1) {
                overlay.add(
                    Polyline().apply {
                        setPoints(segment.map { GeoPoint(it.latitude, it.longitude) })
                        outlinePaint.set(footprintOutlinePaint)
                    }
                )
            }
        }
        mapView.overlays[OVERLAY_FOOTPRINT] = overlay
    } catch (e: Exception) {
        println(e)
    }
}

private fun splitClosedPathOnDateLine(points: List<GeoPos>): List<List<GeoPos>> {
    if (points.isEmpty()) return emptyList()
    val segments = mutableListOf<List<GeoPos>>()
    val segment = mutableListOf<GeoPos>()
    var previous = points.first()
    segment.add(previous)
    (points.drop(1) + points.first()).forEach { current ->
        when {
            previous.longitude < -170.0 && current.longitude > 170.0 -> {
                val edgeLatitude = getDateLineLatitude(previous, current, -180.0)
                segment.add(GeoPos(edgeLatitude, -180.0))
                segments.add(segment.toList())
                segment.clear()
                segment.add(GeoPos(edgeLatitude, 180.0))
            }
            previous.longitude > 170.0 && current.longitude < -170.0 -> {
                val edgeLatitude = getDateLineLatitude(previous, current, 180.0)
                segment.add(GeoPos(edgeLatitude, 180.0))
                segments.add(segment.toList())
                segment.clear()
                segment.add(GeoPos(edgeLatitude, -180.0))
            }
        }
        segment.add(current)
        previous = current
    }
    if (segment.size > 1) segments.add(segment)
    return segments
}

private fun getDateLineLatitude(previous: GeoPos, current: GeoPos, edgeLongitude: Double): Double {
    val currentLongitude = when {
        previous.longitude < -170.0 && current.longitude > 170.0 -> current.longitude - 360.0
        previous.longitude > 170.0 && current.longitude < -170.0 -> current.longitude + 360.0
        else -> current.longitude
    }
    val fraction = (edgeLongitude - previous.longitude) / (currentLongitude - previous.longitude)
    return previous.latitude + (current.latitude - previous.latitude) * fraction
}

/** Place an ic_sun icon marker at the sub-solar point. */
private fun setSubSolarPoint(sunLatDeg: Double, sunLonDeg: Double, mapView: MapView) {
    try {
        val overlay = mapView.overlays[OVERLAY_SUN]
        val sunPos = GeoPoint(sunLatDeg, sunLonDeg)
        if (overlay is Marker) {
            overlay.position = sunPos
        } else {
            val iconSize = 48
            val bmp = createBitmap(iconSize, iconSize)
            ContextCompat.getDrawable(mapView.context, R.drawable.ic_sun)?.apply {
                setBounds(0, 0, iconSize, iconSize)
                colorFilter = sunIconPaint.colorFilter
                draw(Canvas(bmp))
            }
            mapView.overlays[OVERLAY_SUN] = Marker(mapView).apply {
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = bmp.toDrawable(mapView.context.resources)
                position = sunPos
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}

/** Place an ic_moon icon marker at the sub-lunar point. */
private fun setMoonPosition(moonLatDeg: Double, moonLonDeg: Double, mapView: MapView) {
    try {
        val overlay = mapView.overlays[OVERLAY_MOON]
        val moonPos = GeoPoint(moonLatDeg, moonLonDeg)
        if (overlay is Marker) {
            overlay.position = moonPos
        } else {
            val iconSize = 48
            val bmp = createBitmap(iconSize, iconSize)
            val c = Canvas(bmp)
            ContextCompat.getDrawable(mapView.context, R.drawable.ic_moon)?.apply {
                setBounds(0, 0, iconSize, iconSize)
                colorFilter = moonIconPaint.colorFilter
                draw(c)
            }
            mapView.overlays[OVERLAY_MOON] = Marker(mapView).apply {
                setInfoWindow(null)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                icon = bmp.toDrawable(mapView.context.resources)
                position = moonPos
            }
        }
    } catch (e: Exception) {
        println(e)
    }
}
// endregion

// region MapView lifecycle
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val isVertical = isVerticalLayout()
    val mapView = remember {
        configureOsmdroidCache(context)
        MapView(context).apply {
            setMultiTouchControls(true)
            setUseDataConnection(false)
            setTileSource(offlineTileSource)
            minZoomLevel = getMinZoom(resources.displayMetrics.heightPixels, isVertical)
            maxZoomLevel = OFFLINE_MAX_ZOOM
            controller.setCenter(GeoPoint(48.8575, 6.3514))
            controller.setZoom(minZoomLevel + 2)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            overlayManager.tilesOverlay.loadingBackgroundColor = Color.TRANSPARENT
            overlayManager.tilesOverlay.loadingLineColor = Color.TRANSPARENT
            overlayManager.tilesOverlay.setColorFilter(createColorFilter())
            setHorizontalMapRepetitionEnabled(true)
            setVerticalMapRepetitionEnabled(false)
            setScrollableAreaLimitLatitude(maxLat, minLat, 0)
            overlays.addAll(Array(OVERLAY_COUNT) { FolderOverlay() })
            addOnFirstLayoutListener { _, _, _, _, _ -> requestTileRefresh(this) }
        }
    }
    val lifecycleObserver = rememberMapViewLifecycleObserver(mapView)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        lifecycle.addObserver(lifecycleObserver)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            mapView.onResume()
            requestTileRefresh(mapView)
        }
        onDispose { lifecycle.removeObserver(lifecycleObserver) }
    }
    return mapView
}

private fun configureOsmdroidCache(context: android.content.Context) {
    val cacheRoot = File(context.filesDir, "osmdroid")
    val tileCache = File(cacheRoot, "tiles")
    Configuration.getInstance().apply {
        setUserAgentValue(TILE_USER_AGENT)
        setOsmdroidBasePath(cacheRoot)
        setOsmdroidTileCache(tileCache)
        setTileFileSystemCacheMaxBytes(TILE_CACHE_MAX_BYTES)
        setTileFileSystemCacheTrimBytes(TILE_CACHE_TRIM_BYTES)
    }
}

@Composable
private fun rememberMapViewLifecycleObserver(mapView: MapView) = remember(mapView) {
    LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                mapView.onResume()
                requestTileRefresh(mapView)
            }
            Lifecycle.Event.ON_PAUSE -> mapView.onPause()
            else -> {}
        }
    }
}

private fun createColorFilter(): ColorMatrixColorFilter {
    val grayScale = ColorMatrix().apply { setSaturation(0f) }
    val negative = ColorMatrix(
        floatArrayOf(-1f, 0f, 0f, 0f, 260f, 0f, -1f, 0f, 0f, 260f, 0f, 0f, -1f, 0f, 260f, 0f, 0f, 0f, 1f, 0f)
    )
    negative.preConcat(grayScale)
    return ColorMatrixColorFilter(negative)
}

private fun getMinZoom(screenHeight: Int, isVertical: Boolean): Double {
    if (!isVertical) return 3.5
    return MapView.getTileSystem().getLatitudeZoom(maxLat, minLat, screenHeight)
}
// endregion
