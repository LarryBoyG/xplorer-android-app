package com.example.xirolite

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.mapsforge.core.graphics.Cap
import org.mapsforge.core.graphics.Style
import org.mapsforge.core.model.LatLong
import org.mapsforge.core.model.MapPosition
import org.mapsforge.map.android.graphics.AndroidGraphicFactory
import org.mapsforge.map.android.util.AndroidUtil
import org.mapsforge.map.android.view.MapView
import org.mapsforge.map.datastore.MapDataStore
import org.mapsforge.map.layer.Layer
import org.mapsforge.map.layer.cache.TileCache
import org.mapsforge.map.layer.overlay.Marker
import org.mapsforge.map.layer.overlay.Polyline
import org.mapsforge.map.layer.renderer.TileRendererLayer
import org.mapsforge.map.model.DisplayModel
import org.mapsforge.map.reader.MapFile
import org.mapsforge.map.rendertheme.internal.MapsforgeThemes
import java.io.File
import kotlin.math.max
import kotlin.math.min

private val DRONE_MARKER_FILL = 0xFF75E52E.toInt()
private val DRONE_MARKER_STROKE = 0xFF11230B.toInt()
private val PHONE_MARKER_FILL = 0xFFFFFFFF.toInt()
private val PHONE_MARKER_STROKE = 0xFF1B1F27.toInt()
private val HOME_MARKER_FILL = 0xFFFFE066.toInt()
private val HOME_MARKER_STROKE = 0xFF1B1F27.toInt()

@Composable
fun OfflineFlightMapSurface(
    modifier: Modifier,
    activeMap: OfflineMapFile?,
    dronePosition: FlightCoordinate?,
    phonePosition: FlightCoordinate?,
    homePosition: FlightCoordinate?,
    distanceLabel: String,
    elevationLabel: String,
    activeMapLabel: String,
    expanded: Boolean,
    onSwapRequested: () -> Unit,
    tapToSwapEnabled: Boolean,
    onRequestLocationPermission: () -> Unit,
    phoneLocationPermissionGranted: Boolean,
    flightPath: List<FlightCoordinate> = emptyList(),
    expandedHintText: String? = if (expanded) "Tap preview to return to Live" else null,
    showLocationPermissionPrompt: Boolean = true
) {
    val context = LocalContext.current
    val mapHost = remember(context) { OfflineFlightMapHost(context.applicationContext) }

    DisposableEffect(Unit) {
        onDispose {
            mapHost.destroy()
        }
    }

    Box(
        modifier = modifier
            .background(Color(0xD9131820), RoundedCornerShape(24.dp))
    ) {
        if (activeMap != null) {
            AndroidView(
                factory = { mapHost.attachToParent() },
                modifier = Modifier.fillMaxSize(),
                update = {
                    mapHost.bind(
                        mapFile = activeMap.file,
                        dronePosition = dronePosition,
                        phonePosition = phonePosition,
                        homePosition = homePosition,
                        flightPath = flightPath,
                        expanded = expanded
                    )
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1C2129)),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Map,
                        contentDescription = null,
                        tint = XiroDesignTokens.TextMuted,
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "No offline map",
                        color = XiroDesignTokens.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = "Import one in Settings",
                        color = XiroDesignTokens.TextMuted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        if (tapToSwapEnabled) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onSwapRequested)
            )
        }

        if (expandedHintText != null) {
            XiroPillSurface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(10.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Text(
                    text = expandedHintText,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = XiroDesignTokens.TextPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        if (dronePosition != null || phonePosition != null || homePosition != null) {
            MapLegendPill(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(
                        start = 10.dp,
                        top = if (expandedHintText != null) 58.dp else 10.dp
                    ),
                expanded = expanded,
                showDrone = dronePosition != null,
                showPhone = phonePosition != null,
                showHome = homePosition != null
            )
        }

        if (expanded) {
            XiroPillSurface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(10.dp),
                shape = RoundedCornerShape(16.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                    Text(
                        text = activeMapLabel,
                        color = XiroDesignTokens.TextPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        text = OfflineMapManager.MAP_ATTRIBUTION,
                        color = XiroDesignTokens.TextMuted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
        } else {
            XiroPillSurface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
                shape = RoundedCornerShape(12.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Text(
                    text = "\u00A9 OSM",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                    color = XiroDesignTokens.TextPrimary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        XiroPillSurface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            shape = RoundedCornerShape(18.dp),
            containerColor = XiroDesignTokens.SurfaceOverlay
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = if (expanded) "Distance $distanceLabel" else distanceLabel,
                    color = XiroDesignTokens.TextPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    text = if (expanded) "Elevation $elevationLabel" else elevationLabel,
                    color = XiroDesignTokens.TextSecondary,
                    style = MaterialTheme.typography.labelSmall
                )
            }
        }

        if (showLocationPermissionPrompt && !phoneLocationPermissionGranted) {
            XiroPillSurface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(10.dp)
                    .clickable(onClick = onRequestLocationPermission),
                shape = RoundedCornerShape(18.dp),
                containerColor = XiroDesignTokens.SurfaceOverlay
            ) {
                Text(
                    text = "Enable phone GPS",
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                    color = XiroDesignTokens.TextPrimary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

private class OfflineFlightMapHost(
    context: Context
) {
    private val appContext = context
    private val root = FrameLayout(context)
    private val mapView = MapView(context)
    private var activeMapPath: String? = null
    private var tileCache: TileCache? = null
    private var mapDataStore: MapDataStore? = null
    private var rendererLayer: TileRendererLayer? = null
    private var overlayLayers: MutableList<Layer> = mutableListOf()
    private var lastCenterKey: String? = null

    init {
        AndroidGraphicFactory.createInstance(appContext)
        mapView.setBuiltInZoomControls(false)
        mapView.mapScaleBar.isVisible = false
        mapView.isClickable = true
        root.addView(
            mapView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )
    }

    fun attachToParent(): FrameLayout = root

    fun bind(
        mapFile: File,
        dronePosition: FlightCoordinate?,
        phonePosition: FlightCoordinate?,
        homePosition: FlightCoordinate?,
        flightPath: List<FlightCoordinate>,
        expanded: Boolean
    ) {
        if (activeMapPath != mapFile.absolutePath) {
            activeMapPath = mapFile.absolutePath
            loadMap(mapFile)
            lastCenterKey = null
        }
        updateOverlays(
            dronePosition = dronePosition,
            phonePosition = phonePosition,
            homePosition = homePosition,
            flightPath = flightPath
        )
        centerMap(
            dronePosition = dronePosition,
            phonePosition = phonePosition,
            homePosition = homePosition,
            flightPath = flightPath,
            expanded = expanded
        )
    }

    fun destroy() {
        overlayLayers.forEach { layer -> mapView.layerManager.layers.remove(layer) }
        overlayLayers.clear()
        rendererLayer?.let { mapView.layerManager.layers.remove(it) }
        rendererLayer = null
        tileCache?.destroy()
        tileCache = null
        mapDataStore?.close()
        mapDataStore = null
        mapView.destroyAll()
    }

    private fun loadMap(mapFile: File) {
        overlayLayers.forEach { layer -> mapView.layerManager.layers.remove(layer) }
        overlayLayers.clear()
        rendererLayer?.let { mapView.layerManager.layers.remove(it) }
        tileCache?.destroy()
        mapDataStore?.close()

        val displayModel: DisplayModel = mapView.model.displayModel
        tileCache = AndroidUtil.createTileCache(
            appContext,
            "xiro_offline_map",
            displayModel.tileSize,
            1f,
            mapView.model.frameBufferModel.overdrawFactor
        )
        mapDataStore = MapFile(mapFile)
        rendererLayer = TileRendererLayer(
            tileCache,
            mapDataStore,
            mapView.model.mapViewPosition,
            AndroidGraphicFactory.INSTANCE
        ).apply {
            setXmlRenderTheme(MapsforgeThemes.DEFAULT)
        }
        mapView.layerManager.layers.add(rendererLayer)
    }

    private fun updateOverlays(
        dronePosition: FlightCoordinate?,
        phonePosition: FlightCoordinate?,
        homePosition: FlightCoordinate?,
        flightPath: List<FlightCoordinate>
    ) {
        overlayLayers.forEach { layer -> mapView.layerManager.layers.remove(layer) }
        overlayLayers.clear()

        overlayLayers.addAll(buildPathLayers(flightPath))
        phonePosition?.let {
            overlayLayers.add(createMarker(it, PHONE_MARKER_FILL, PHONE_MARKER_STROKE))
        }
        homePosition?.let {
            overlayLayers.add(createMarker(it, HOME_MARKER_FILL, HOME_MARKER_STROKE))
        }
        dronePosition?.let {
            overlayLayers.add(createMarker(it, DRONE_MARKER_FILL, DRONE_MARKER_STROKE))
        }

        overlayLayers.forEach { layer ->
            mapView.layerManager.layers.add(layer)
        }
    }

    private fun centerMap(
        dronePosition: FlightCoordinate?,
        phonePosition: FlightCoordinate?,
        homePosition: FlightCoordinate?,
        flightPath: List<FlightCoordinate>,
        expanded: Boolean
    ) {
        val points = buildList {
            addAll(flightPath)
            phonePosition?.let(::add)
            dronePosition?.let(::add)
            homePosition?.let(::add)
        }
        if (points.isEmpty()) return
        val centerLat = points.map { it.latitude }.average()
        val centerLon = points.map { it.longitude }.average()
        val zoom = chooseZoom(points, expanded)
        val centerKey = "${"%.6f".format(centerLat)}:${"%.6f".format(centerLon)}:$zoom"
        if (centerKey == lastCenterKey) return
        lastCenterKey = centerKey
        mapView.model.mapViewPosition.mapPosition = MapPosition(LatLong(centerLat, centerLon), zoom.toByte())
    }

    private fun chooseZoom(points: List<FlightCoordinate>, expanded: Boolean): Int {
        if (points.size <= 1) return if (expanded) 16 else 15
        val latSpan = maxOf(points.maxOf { it.latitude } - points.minOf { it.latitude }, 0.0001)
        val lonSpan = maxOf(points.maxOf { it.longitude } - points.minOf { it.longitude }, 0.0001)
        val span = max(latSpan, lonSpan)
        val base = when {
            span > 0.2 -> 9
            span > 0.08 -> 10
            span > 0.03 -> 11
            span > 0.015 -> 12
            span > 0.006 -> 13
            span > 0.0025 -> 14
            span > 0.001 -> 15
            else -> 16
        }
        return if (expanded) base else min(base + 1, 17)
    }

    private fun createMarker(
        coordinate: FlightCoordinate,
        fillColor: Int,
        strokeColor: Int
    ): Marker {
        val markerBitmap = buildMarkerBitmap(fillColor, strokeColor)
        return Marker(
            LatLong(coordinate.latitude, coordinate.longitude),
            markerBitmap,
            0,
            -markerBitmap.height / 2
        )
    }

    private fun buildPathLayers(flightPath: List<FlightCoordinate>): List<Layer> {
        val latLongs = flightPath
            .distinctBy { coordinate ->
                "${"%.6f".format(coordinate.latitude)}:${"%.6f".format(coordinate.longitude)}"
            }
            .map { coordinate -> LatLong(coordinate.latitude, coordinate.longitude) }
        if (latLongs.size < 2) return emptyList()

        val shadowPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(0xCC111820.toInt())
            setStyle(Style.STROKE)
            setStrokeCap(Cap.ROUND)
            setStrokeWidth(8f)
        }
        val accentPaint = AndroidGraphicFactory.INSTANCE.createPaint().apply {
            setColor(0xFF75E52E.toInt())
            setStyle(Style.STROKE)
            setStrokeCap(Cap.ROUND)
            setStrokeWidth(4.5f)
        }

        val shadowPath = Polyline(shadowPaint, AndroidGraphicFactory.INSTANCE).apply {
            setStrokeIncrease(1.0)
            setPoints(latLongs)
        }
        val accentPath = Polyline(accentPaint, AndroidGraphicFactory.INSTANCE).apply {
            setStrokeIncrease(1.0)
            setPoints(latLongs)
        }
        return listOf(shadowPath, accentPath)
    }

    private fun buildMarkerBitmap(fillColor: Int, strokeColor: Int): org.mapsforge.core.graphics.Bitmap {
        val sizePx = 34
        val androidBitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(androidBitmap)
        val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = strokeColor
            style = Paint.Style.FILL
        }
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor
            style = Paint.Style.FILL
        }
        val ring = RectF(1f, 1f, sizePx - 1f, sizePx - 1f)
        canvas.drawOval(ring, strokePaint)
        canvas.drawOval(RectF(5f, 5f, sizePx - 5f, sizePx - 5f), fillPaint)
        return AndroidGraphicFactory.convertToBitmap(BitmapDrawable(appContext.resources, androidBitmap))
    }
}

@Composable
private fun MapLegendPill(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    showDrone: Boolean,
    showPhone: Boolean,
    showHome: Boolean
) {
    val items = buildList {
        if (showDrone) add(Triple("Drone", DRONE_MARKER_FILL, DRONE_MARKER_STROKE))
        if (showPhone) add(Triple("You", PHONE_MARKER_FILL, PHONE_MARKER_STROKE))
        if (showHome) add(Triple("Home", HOME_MARKER_FILL, HOME_MARKER_STROKE))
    }
    if (items.isEmpty()) return

    XiroPillSurface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        containerColor = XiroDesignTokens.SurfaceOverlay
    ) {
        if (expanded) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                items.forEach { (label, fill, stroke) ->
                    MapLegendItem(label = label, fillColor = fill, strokeColor = stroke, compact = false)
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                items.forEach { (label, fill, stroke) ->
                    MapLegendItem(label = label.take(1), fillColor = fill, strokeColor = stroke, compact = true)
                }
            }
        }
    }
}

@Composable
private fun MapLegendItem(
    label: String,
    fillColor: Int,
    strokeColor: Int,
    compact: Boolean
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(if (compact) 9.dp else 10.dp)
                .background(Color(strokeColor), RoundedCornerShape(50))
                .padding(2.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(fillColor), RoundedCornerShape(50))
            )
        }
        Text(
            text = label,
            color = XiroDesignTokens.TextPrimary,
            style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.bodySmall
        )
    }
}
