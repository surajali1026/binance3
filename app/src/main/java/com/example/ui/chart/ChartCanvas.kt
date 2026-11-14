package com.example.ui.chart

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.domain.model.ChartDrawing
import com.example.domain.model.ChartPoint
import com.example.domain.model.DrawingTool
import com.example.domain.model.DrawingType
import com.example.domain.model.LineStyle
import com.example.domain.model.PriceAlert
import com.example.ui.theme.AlertAmber
import com.example.ui.theme.AmberGold
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CanvasVoid
import com.example.ui.theme.CyanInfo
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.GridHairline
import com.example.ui.theme.TabularMonoStyle
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.ToolbarSurface
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
fun ChartCanvas(
    state: ChartUiState,
    onPlotWidthChanged: (Float) -> Unit,
    onPan: (Float, Float, Float) -> Unit,
    onPinchZoom: (Float, Float, Float, Float, Float) -> Unit,
    onScalePriceAxis: (Float) -> Unit,
    onResetPriceAutoFit: () -> Unit,
    onPlotDoubleTap: () -> Unit = {},
    onDismissCrosshair: () -> Unit = {},
    onScaleTimeAxis: (Float) -> Unit,
    onCrosshairMove: (Long, Double, com.example.domain.model.Candle?) -> Unit,
    onChartTap: (ChartPoint, ChartDrawing?, PriceAlert?, Int) -> Unit,
    onMoveDrawingAnchor: (String, Int, ChartPoint) -> Unit,
    onMoveWholeDrawing: (String, Long, Double) -> Unit,
    onMoveAlertPrice: (String, Double) -> Unit,
    onPriceAxisTap: (Double) -> Unit,
    onQuickAlertPlusClick: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val candles = state.displayedCandles
    val precision = state.contract?.pricePrecision ?: 2
    val bullColor = Color(state.preferences.bullColorHex)
    val bearColor = Color(state.preferences.bearColorHex)
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    val gestureScope = rememberCoroutineScope()

    var plotWidthPx by remember { mutableFloatStateOf(900f) }
    var plotHeightPx by remember { mutableFloatStateOf(600f) }
    var flingJob by remember { mutableStateOf<Job?>(null) }
    var lastTapTimeMs by remember { mutableLongStateOf(0L) }
    var lastTapPos by remember { mutableStateOf(Offset.Zero) }

    val currentCandles by rememberUpdatedState(candles)
    val currentState by rememberUpdatedState(state)

    val mapper = remember(
        candles,
        state.activeTimeframe.durationMillis,
        plotWidthPx,
        plotHeightPx,
        state.viewport
    ) {
        ChartCoordinateMapper(
            candles = candles,
            timeframeDurationMs = state.activeTimeframe.durationMillis,
            plotWidthPx = plotWidthPx,
            plotHeightPx = plotHeightPx,
            viewport = state.viewport
        )
    }

    val priceAxisWidth = 72.dp
    val timeAxisHeight = 26.dp

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(CanvasVoid)
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            // Main Chart Plot Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .onSizeChanged { size ->
                        plotWidthPx = size.width.toFloat().coerceAtLeast(1f)
                        plotHeightPx = size.height.toFloat().coerceAtLeast(1f)
                        onPlotWidthChanged(plotWidthPx)
                    }
                    .pointerInput(Unit) {
                        val touchSlop = viewConfiguration.touchSlop
                        // Slightly deliberate long-press keeps normal chart panning responsive.
                        val longPressTimeout = min(450L, viewConfiguration.longPressTimeoutMillis)
                        val hitThresholdPx = with(density) { 24.dp.toPx() }
                        val handleThresholdPx = with(density) { 28.dp.toPx() }
                        val verticalUnlockThresholdPx = with(density) { 16.dp.toPx() }
                        val doubleTapSlopPx = with(density) { 36.dp.toPx() }

                        awaitEachGesture {
                            flingJob?.cancel()
                            val down = awaitFirstDown(requireUnconsumed = false)
                            val downPos = down.position
                            val velocityTracker = VelocityTracker()
                            velocityTracker.addPosition(down.uptimeMillis, down.position)

                            var totalMove = 0f
                            var panAccumX = 0f
                            var panAccumY = 0f
                            var unlockedVerticalThisGesture = !currentState.viewport.isAutoFit
                            var isCrosshairDrag = false
                            var isMultiTouch = false
                            var didPan = false
                            var dismissedCrosshairOnDrag = false
                            var dragDrawingHandle: Pair<String, Int>? = null
                            var dragWholeDrawingId: String? = null
                            var dragAlertId: String? = null

                            val localState = currentState
                            val localCandles = currentCandles
                            val localMapper = ChartCoordinateMapper(
                                candles = localCandles,
                                timeframeDurationMs = localState.activeTimeframe.durationMillis,
                                plotWidthPx = plotWidthPx,
                                plotHeightPx = plotHeightPx,
                                viewport = localState.viewport
                            )

                            val selectedDrawing = localState.drawings.firstOrNull {
                                it.id == localState.selectedDrawingId && !it.isLocked
                            }
                            if (selectedDrawing != null) {
                                val handleIdx = localMapper.hitTestDrawingHandle(downPos, selectedDrawing, handleThresholdPx)
                                if (handleIdx != null) {
                                    dragDrawingHandle = selectedDrawing.id to handleIdx
                                } else if (localMapper.hitTestDrawing(downPos, listOf(selectedDrawing), hitThresholdPx) != null) {
                                    dragWholeDrawingId = selectedDrawing.id
                                }
                            }
                            if (dragDrawingHandle == null && dragWholeDrawingId == null) {
                                val hitAlert = localMapper.hitTestAlert(
                                    downPos,
                                    localState.alertsForSymbol.filter { it.isActive },
                                    hitThresholdPx
                                )
                                if (hitAlert != null) {
                                    dragAlertId = hitAlert.id
                                }
                            }

                            var longPressTriggered = false
                            var pointerUp = false

                            val longPressJob = gestureScope.launch {
                                delay(longPressTimeout)
                                if (!pointerUp && totalMove < touchSlop &&
                                    !isMultiTouch &&
                                    dragDrawingHandle == null &&
                                    dragWholeDrawingId == null &&
                                    dragAlertId == null &&
                                    localState.activeTool == DrawingTool.NONE
                                ) {
                                    longPressTriggered = true
                                    isCrosshairDrag = true
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val time = localMapper.xToTime(downPos.x)
                                    val price = localMapper.yToPrice(downPos.y)
                                    val idx = localMapper.xToFloatIndex(downPos.x).roundToInt()
                                    val candle = localCandles.getOrNull(idx)
                                    onCrosshairMove(time, price, candle)
                                }
                            }

                            try {
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Main)
                                    val pressedChanges = event.changes.filter { it.pressed }
                                    val pressedCount = pressedChanges.size
                                    if (pressedCount == 0) {
                                        pointerUp = true
                                        longPressJob.cancel()
                                        break
                                    }
                                    if (pressedCount >= 2) {
                                        isMultiTouch = true
                                        longPressJob.cancel()
                                        // Use the pointer-event scale factor instead of measuring only X/Y spans.
                                        // This supports natural diagonal pinches and keeps the pinch midpoint anchored.
                                        val zoomFactor = event.calculateZoom().coerceIn(0.82f, 1.22f)
                                        val zoomX = if (zoomFactor.isFinite()) zoomFactor else 1f
                                        val zoomY = 1f

                                        val pan = event.calculatePan()
                                        val centroid = event.calculateCentroid(useCurrent = false)
                                        if (zoomX != 1f) {
                                            onPinchZoom(zoomX, zoomY, centroid.x, centroid.y, plotHeightPx)
                                        }
                                        if (pan != Offset.Zero) {
                                            val panY = if (!currentState.viewport.isAutoFit) pan.y else 0f
                                            onPan(pan.x, panY, plotHeightPx)
                                        }
                                        event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    } else if (!isMultiTouch) {
                                        val change = pressedChanges.first()
                                        val delta = change.position - change.previousPosition
                                        totalMove += delta.getDistance()
                                        velocityTracker.addPosition(change.uptimeMillis, change.position)
                                        if (totalMove > touchSlop) {
                                            longPressJob.cancel()
                                        }

                                        if (isCrosshairDrag) {
                                            val liveMapper = ChartCoordinateMapper(
                                                candles = currentCandles,
                                                timeframeDurationMs = currentState.activeTimeframe.durationMillis,
                                                plotWidthPx = plotWidthPx,
                                                plotHeightPx = plotHeightPx,
                                                viewport = currentState.viewport
                                            )
                                            val pos = change.position
                                            val time = liveMapper.xToTime(pos.x)
                                            val price = liveMapper.yToPrice(pos.y)
                                            val idx = liveMapper.xToFloatIndex(pos.x).roundToInt()
                                            onCrosshairMove(time, price, currentCandles.getOrNull(idx))
                                            change.consume()
                                        } else if (totalMove > touchSlop) {
                                            val liveMapper = ChartCoordinateMapper(
                                                candles = currentCandles,
                                                timeframeDurationMs = currentState.activeTimeframe.durationMillis,
                                                plotWidthPx = plotWidthPx,
                                                plotHeightPx = plotHeightPx,
                                                viewport = currentState.viewport
                                            )
                                            when {
                                                dragDrawingHandle != null -> {
                                                    val pt = liveMapper.screenToChartPoint(change.position)
                                                    onMoveDrawingAnchor(dragDrawingHandle.first, dragDrawingHandle.second, pt)
                                                }
                                                dragWholeDrawingId != null -> {
                                                    val pPrev = liveMapper.screenToChartPoint(change.previousPosition)
                                                    val pCurr = liveMapper.screenToChartPoint(change.position)
                                                    onMoveWholeDrawing(
                                                        dragWholeDrawingId,
                                                        pCurr.timeMillis - pPrev.timeMillis,
                                                        pCurr.price - pPrev.price
                                                    )
                                                }
                                                dragAlertId != null -> {
                                                    val newPrice = liveMapper.yToPrice(change.position.y)
                                                    onMoveAlertPrice(dragAlertId, newPrice)
                                                }
                                                else -> {
                                                    if (!dismissedCrosshairOnDrag && currentState.crosshair.isActive) {
                                                        dismissedCrosshairOnDrag = true
                                                        onDismissCrosshair()
                                                    }
                                                    didPan = true
                                                    panAccumX += abs(delta.x)
                                                    panAccumY += abs(delta.y)
                                                    if (!currentState.viewport.isAutoFit) {
                                                        unlockedVerticalThisGesture = true
                                                    } else if (!unlockedVerticalThisGesture &&
                                                        panAccumY > verticalUnlockThresholdPx &&
                                                        panAccumY > panAccumX * 1.15f
                                                    ) {
                                                        unlockedVerticalThisGesture = true
                                                    }
                                                    val dyToPass = if (unlockedVerticalThisGesture) delta.y else 0f
                                                    onPan(delta.x, dyToPass, plotHeightPx)
                                                }
                                            }
                                            change.consume()
                                        }
                                    }
                                }
                            } finally {
                                pointerUp = true
                                longPressJob.cancel()
                            }

                            if (didPan && !isMultiTouch && !isCrosshairDrag &&
                                dragDrawingHandle == null && dragWholeDrawingId == null && dragAlertId == null
                            ) {
                                val velocity = velocityTracker.calculateVelocity()
                                var vx = velocity.x.coerceIn(-4500f, 4500f)
                                var vy = if (unlockedVerticalThisGesture) velocity.y.coerceIn(-3500f, 3500f) else 0f
                                if (hypot(vx, vy) > 160f) {
                                    flingJob = gestureScope.launch {
                                        var lastFrameNanos = withFrameNanos { it }
                                        while (isActive && hypot(vx, vy) > 25f) {
                                            val frameNanos = withFrameNanos { it }
                                            val dtSec = ((frameNanos - lastFrameNanos) / 1_000_000_000f)
                                                .coerceIn(0.001f, 0.032f)
                                            lastFrameNanos = frameNanos
                                            onPan(vx * dtSec, vy * dtSec, plotHeightPx)
                                            val decay = exp(-5.5f * dtSec)
                                            vx *= decay
                                            vy *= decay
                                        }
                                    }
                                }
                            } else if (!longPressTriggered && !isMultiTouch && totalMove <= touchSlop) {
                                val tapMapper = ChartCoordinateMapper(
                                    candles = currentCandles,
                                    timeframeDurationMs = currentState.activeTimeframe.durationMillis,
                                    plotWidthPx = plotWidthPx,
                                    plotHeightPx = plotHeightPx,
                                    viewport = currentState.viewport
                                )
                                val pt = tapMapper.screenToChartPoint(downPos)
                                val hitDrawing = tapMapper.hitTestDrawing(downPos, currentState.drawings, hitThresholdPx)
                                val hitAlert = tapMapper.hitTestAlert(
                                    downPos,
                                    currentState.alertsForSymbol.filter { it.isActive },
                                    hitThresholdPx
                                )
                                val nowMs = System.currentTimeMillis()
                                val isDoubleTap = currentState.activeTool == DrawingTool.NONE &&
                                    !currentState.replayState.isPickingStart &&
                                    !currentState.crosshair.isActive &&
                                    hitDrawing == null &&
                                    hitAlert == null &&
                                    (nowMs - lastTapTimeMs) <= 270L &&
                                    (downPos - lastTapPos).getDistance() <= doubleTapSlopPx

                                if (isDoubleTap) {
                                    lastTapTimeMs = 0L
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    onPlotDoubleTap()
                                } else {
                                    lastTapTimeMs = nowMs
                                    lastTapPos = downPos
                                    val idx = tapMapper.xToFloatIndex(downPos.x).roundToInt()
                                    onChartTap(pt, hitDrawing, hitAlert, idx)
                                }
                            }
                        }
                    }
                    .testTag("chart_plot_canvas")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val width = size.width
                    val height = size.height
                    val visibleRange = mapper.visibleIndexRange()
                    val ticks = mapper.computePriceTicks(7)

                    // 1. Subtle Grid Lines
                    if (state.preferences.showGrid) {
                        for (tick in ticks) {
                            val y = mapper.priceToY(tick)
                            drawLine(
                                color = GridHairline,
                                start = Offset(0f, y),
                                end = Offset(width, y),
                                strokeWidth = 1f
                            )
                        }
                        if (!visibleRange.isEmpty()) {
                            val stepBars = max(1, (visibleRange.count() / 5))
                            var i = visibleRange.first
                            while (i <= visibleRange.last) {
                                val x = mapper.indexToX(i.toFloat())
                                drawLine(
                                    color = GridHairline,
                                    start = Offset(x, 0f),
                                    end = Offset(x, height),
                                    strokeWidth = 1f
                                )
                                i += stepBars
                            }
                        }
                    }

                    // 2. Candlesticks
                    if (!visibleRange.isEmpty()) {
                        val candleBodyWidth = (state.viewport.barSpacingPx * 0.68f).coerceIn(1.5f, 46f)
                        val wickStroke = (state.viewport.barSpacingPx * 0.1f).coerceIn(1.2f, 2.8f)

                        for (idx in visibleRange) {
                            val c = candles[idx]
                            val cx = mapper.indexToX(idx.toFloat())
                            val isBull = c.close >= c.open
                            val color = if (isBull) bullColor else bearColor

                            val yHigh = mapper.priceToY(c.high)
                            val yLow = mapper.priceToY(c.low)
                            val yOpen = mapper.priceToY(c.open)
                            val yClose = mapper.priceToY(c.close)

                            // Wick
                            drawLine(
                                color = color,
                                start = Offset(cx, yHigh),
                                end = Offset(cx, yLow),
                                strokeWidth = wickStroke
                            )

                            // Body
                            val top = min(yOpen, yClose)
                            val bodyHeight = abs(yClose - yOpen).coerceAtLeast(1.5f)
                            drawRect(
                                color = color,
                                topLeft = Offset(cx - candleBodyWidth / 2f, top),
                                size = Size(candleBodyWidth, bodyHeight)
                            )
                        }
                    }

                    // 3. Drawings (Horizontal Line, Rectangle, Trendline)
                    state.drawings.forEach { drawing ->
                        val isSelected = drawing.id == state.selectedDrawingId
                        drawChartDrawing(
                            drawing = drawing,
                            mapper = mapper,
                            width = width,
                            isSelected = isSelected
                        )
                    }

                    // Pending Drawing preview point(s)
                    if (state.pendingPoints.isNotEmpty()) {
                        val pt = state.pendingPoints.first()
                        val px = mapper.timeToX(pt.timeMillis)
                        val py = mapper.priceToY(pt.price)
                        drawCircle(color = AmberGold, radius = 7f, center = Offset(px, py))
                    }

                    // 4. Dotted Alert Lines (no unnecessary name tags on chart)
                    val dottedEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f), 0f)
                    state.alertsForSymbol.filter { it.isActive }.forEach { alert ->
                        val ay = mapper.priceToY(alert.targetPrice)
                        if (ay in -10f..(height + 10f)) {
                            val isSelected = alert.id == state.selectedAlertId
                            drawLine(
                                color = if (isSelected) AmberGold else AlertAmber.copy(alpha = 0.9f),
                                start = Offset(0f, ay),
                                end = Offset(width, ay),
                                strokeWidth = if (isSelected) 3f else 2f,
                                pathEffect = dottedEffect
                            )
                            if (isSelected) {
                                drawCircle(
                                    color = AmberGold,
                                    radius = 7f,
                                    center = Offset(28f, ay)
                                )
                            }
                        }
                    }

                    // 5. Price Range Measurement Overlay
                    state.rangeMeasurement?.let { measure ->
                        val x1 = mapper.timeToX(measure.start.timeMillis)
                        val y1 = mapper.priceToY(measure.start.price)
                        val x2 = mapper.timeToX(measure.end.timeMillis)
                        val y2 = mapper.priceToY(measure.end.price)
                        val left = min(x1, x2)
                        val top = min(y1, y2)
                        val boxW = abs(x2 - x1).coerceAtLeast(2f)
                        val boxH = abs(y2 - y1).coerceAtLeast(2f)
                        val delta = measure.end.price - measure.start.price
                        val pct = if (measure.start.price != 0.0) (delta / measure.start.price) * 100.0 else 0.0
                        val isGain = delta >= 0.0
                        val tint = if (isGain) bullColor else bearColor

                        drawRect(
                            color = tint.copy(alpha = 0.16f),
                            topLeft = Offset(left, top),
                            size = Size(boxW, boxH)
                        )
                        drawRect(
                            color = tint,
                            topLeft = Offset(left, top),
                            size = Size(boxW, boxH),
                            style = Stroke(width = 2f)
                        )
                        val labelText = "${ChartNumberFormatter.formatPrice(delta, precision)} (${ChartNumberFormatter.formatPercent(pct)}) • ${measure.barCount} bars • ${ChartNumberFormatter.formatDuration(measure.end.timeMillis - measure.start.timeMillis)}"
                        val measured = textMeasurer.measure(
                            text = labelText,
                            style = TabularMonoStyle.copy(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        val labelPad = 8f
                        val badgeW = measured.size.width + labelPad * 2
                        val badgeH = measured.size.height + 10f
                        val bx = (left + boxW / 2f - badgeW / 2f).coerceIn(4f, (width - badgeW - 4f).coerceAtLeast(4f))
                        val by = (if (isGain) top - badgeH - 6f else top + boxH + 6f)
                            .coerceIn(4f, (height - badgeH - 4f).coerceAtLeast(4f))
                        drawRoundRect(
                            color = tint.copy(alpha = 0.92f),
                            topLeft = Offset(bx, by),
                            size = Size(badgeW, badgeH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8f, 8f)
                        )
                        drawText(
                            textLayoutResult = measured,
                            topLeft = Offset(bx + labelPad, by + 5f)
                        )
                    }

                    // 6. Current Price Line
                    val currentPrice = state.currentPrice
                    val lastCandle = candles.lastOrNull()
                    if (currentPrice != null) {
                        val py = mapper.priceToY(currentPrice)
                        val isBull = if (lastCandle != null) lastCandle.close >= lastCandle.open else true
                        val lineColor = if (isBull) bullColor else bearColor
                        drawLine(
                            color = lineColor.copy(alpha = 0.85f),
                            start = Offset(0f, py),
                            end = Offset(width, py),
                            strokeWidth = 1.6f,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        )
                    }

                    // 6b. Quick-Alert Tapped Price Guide Line
                    state.axisQuickAlertPrice?.let { qPrice ->
                        val qy = mapper.priceToY(qPrice)
                        if (qy in 0f..height) {
                            drawLine(
                                color = AmberGold.copy(alpha = 0.65f),
                                start = Offset(0f, qy),
                                end = Offset(width, qy),
                                strokeWidth = 1.25f,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 5f), 0f)
                            )
                        }
                    }

                    // 7. Crosshair Lines
                    if (state.crosshair.isActive) {
                        val cx = mapper.timeToX(state.crosshair.timeMillis)
                        val cy = mapper.priceToY(state.crosshair.price)
                        val crossDash = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f)
                        drawLine(
                            color = TextMuted.copy(alpha = 0.75f),
                            start = Offset(cx, 0f),
                            end = Offset(cx, height),
                            strokeWidth = 1.4f,
                            pathEffect = crossDash
                        )
                        drawLine(
                            color = TextMuted.copy(alpha = 0.75f),
                            start = Offset(0f, cy),
                            end = Offset(width, cy),
                            strokeWidth = 1.4f,
                            pathEffect = crossDash
                        )
                    }
                }

                // TradingView-style Floating "+" Button at tapped price level (44dp touch target, 28dp badge)
                val quickPrice = state.axisQuickAlertPrice
                if (quickPrice != null) {
                    val quickY = mapper.priceToY(quickPrice)
                    val touchBoxPx = with(density) { 44.dp.toPx() }
                    val clampedY = (quickY - touchBoxPx / 2f)
                        .coerceIn(0f, (plotHeightPx - touchBoxPx).coerceAtLeast(0f))
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset { IntOffset(x = 0, y = clampedY.roundToInt()) }
                            .size(44.dp)
                            .clickable { onQuickAlertPlusClick(quickPrice) }
                            .testTag("floating_plus_alert_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(AmberGold),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Add,
                                contentDescription = "Add alert at selected price",
                                tint = CanvasVoid,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }

            // Right Price Axis Strip (Independent density-normalized vertical zoom + double-tap auto-fit + tapped price badge)
            Box(
                modifier = Modifier
                    .width(priceAxisWidth)
                    .fillMaxHeight()
                    .clipToBounds()
                    .background(CanvasVoid)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures { _, dragAmount ->
                            onScalePriceAxis(dragAmount / density.density)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onDoubleTap = {
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                onResetPriceAutoFit()
                            },
                            onTap = { offset ->
                                val tappedPrice = mapper.yToPrice(offset.y)
                                onPriceAxisTap(tappedPrice)
                            }
                        )
                    }
                    .testTag("chart_price_axis")
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    drawLine(
                        color = BorderSubtle,
                        start = Offset(0f, 0f),
                        end = Offset(0f, size.height),
                        strokeWidth = 1f
                    )

                    val ticks = mapper.computePriceTicks(7)
                    val tickStyle = TabularMonoStyle.copy(color = TextMuted, fontSize = 10.sp)
                    for (tick in ticks) {
                        val y = mapper.priceToY(tick)
                        if (y in 12f..(size.height - 16f)) {
                            val label = ChartNumberFormatter.formatPrice(tick, precision)
                            val measured = textMeasurer.measure(label, tickStyle)
                            drawText(
                                textLayoutResult = measured,
                                topLeft = Offset(6f, y - measured.size.height / 2f)
                            )
                        }
                    }

                    // Active Alert Price Pills on Axis (subtle amber tag, no text name)
                    state.alertsForSymbol.filter { it.isActive }.forEach { alert ->
                        val ay = mapper.priceToY(alert.targetPrice)
                        if (ay in 8f..(size.height - 8f)) {
                            val label = ChartNumberFormatter.formatPrice(alert.targetPrice, precision)
                            val m = textMeasurer.measure(
                                label,
                                TabularMonoStyle.copy(color = CanvasVoid, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            )
                            val badgeH = m.size.height + 6f
                            drawRoundRect(
                                color = AlertAmber,
                                topLeft = Offset(2f, ay - badgeH / 2f),
                                size = Size(size.width - 4f, badgeH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawText(m, topLeft = Offset(6f, ay - m.size.height / 2f))
                        }
                    }

                    // Current Price Badge + TradingView-style Bar Countdown directly below it
                    val currentPrice = state.currentPrice
                    val lastCandle = candles.lastOrNull()
                    if (currentPrice != null) {
                        val py = mapper.priceToY(currentPrice).coerceIn(14f, (size.height - 38f).coerceAtLeast(14f))
                        val isBull = if (lastCandle != null) lastCandle.close >= lastCandle.open else true
                        val badgeColor = if (isBull) bullColor else bearColor
                        val priceLabel = ChartNumberFormatter.formatPrice(currentPrice, precision)
                        val mPrice = textMeasurer.measure(
                            priceLabel,
                            TabularMonoStyle.copy(
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        val priceBoxH = mPrice.size.height + 6f
                        val showCountdown = state.preferences.showCountdown && !state.replayState.isActive
                        val cdText = if (showCountdown) ChartNumberFormatter.formatCountdown(state.countdownMillis) else ""
                        val mCd = if (showCountdown) {
                            textMeasurer.measure(
                                cdText,
                                TabularMonoStyle.copy(
                                    color = Color.White,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.SemiBold
                                )
                            )
                        } else null
                        val cdBoxH = if (mCd != null) mCd.size.height + 5f else 0f

                        drawRoundRect(
                            color = badgeColor,
                            topLeft = Offset(2f, py - priceBoxH / 2f),
                            size = Size(size.width - 4f, priceBoxH),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                        )
                        drawText(
                            textLayoutResult = mPrice,
                            topLeft = Offset(6f, py - priceBoxH / 2f + 3f)
                        )
                        if (mCd != null) {
                            val cdTop = py + priceBoxH / 2f + 1f
                            drawRoundRect(
                                color = badgeColor.copy(alpha = 0.72f),
                                topLeft = Offset(2f, cdTop),
                                size = Size(size.width - 4f, cdBoxH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(3f, 3f)
                            )
                            drawText(
                                textLayoutResult = mCd,
                                topLeft = Offset(6f, cdTop + 2f)
                            )
                        }
                    }

                    // Selected Quick-Alert Price Pill on Axis
                    state.axisQuickAlertPrice?.let { qPrice ->
                        val qy = mapper.priceToY(qPrice)
                            .coerceIn(12f, (size.height - 20f).coerceAtLeast(12f))
                        val label = ChartNumberFormatter.formatPrice(qPrice, precision)
                        val mQuick = textMeasurer.measure(
                            label,
                            TabularMonoStyle.copy(
                                color = CanvasVoid,
                                fontSize = 9.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        )
                        val bh = mQuick.size.height + 7f
                        drawRoundRect(
                            color = AmberGold,
                            topLeft = Offset(2f, qy - bh / 2f),
                            size = Size(size.width - 4f, bh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                        )
                        drawText(mQuick, topLeft = Offset(6f, qy - mQuick.size.height / 2f))
                    }

                    // Crosshair Price Badge
                    if (state.crosshair.isActive) {
                        val cy = mapper.priceToY(state.crosshair.price)
                            .coerceIn(12f, (size.height - 20f).coerceAtLeast(12f))
                        val label = ChartNumberFormatter.formatPrice(state.crosshair.price, precision)
                        val mCross = textMeasurer.measure(
                            label,
                            TabularMonoStyle.copy(
                                color = TextPrimary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                        val bh = mCross.size.height + 8f
                        drawRoundRect(
                            color = ToolbarSurface,
                            topLeft = Offset(2f, cy - bh / 2f),
                            size = Size(size.width - 4f, bh),
                            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                        )
                        drawText(mCross, topLeft = Offset(6f, cy - mCross.size.height / 2f))
                    }
                }
            }
        }

        // Bottom Time Axis Strip (Independent horizontal time-scale zoom)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(timeAxisHeight)
                .background(CanvasVoid)
        ) {
            Canvas(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clipToBounds()
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures { _, dragAmount ->
                            onScaleTimeAxis(dragAmount / density.density)
                        }
                    }
                    .testTag("chart_time_axis")
            ) {
                drawLine(
                    color = BorderSubtle,
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 1f
                )
                val visibleRange = mapper.visibleIndexRange()
                if (!visibleRange.isEmpty()) {
                    val stepBars = max(1, visibleRange.count() / 5)
                    val timeStyle = TabularMonoStyle.copy(color = TextMuted, fontSize = 10.sp)
                    var i = visibleRange.first
                    while (i <= visibleRange.last) {
                        val candle = candles[i]
                        val x = mapper.indexToX(i.toFloat())
                        val label = ChartNumberFormatter.formatAxisTime(
                            candle.openTime,
                            state.activeTimeframe.durationMillis
                        )
                        val m = textMeasurer.measure(label, timeStyle)
                        val tx = (x - m.size.width / 2f).coerceIn(4f, (size.width - m.size.width - 4f).coerceAtLeast(4f))
                        drawText(m, topLeft = Offset(tx, 6f))
                        i += stepBars
                    }
                }

                if (state.crosshair.isActive) {
                    val cx = mapper.timeToX(state.crosshair.timeMillis)
                    val label = ChartNumberFormatter.formatCrosshairTime(state.crosshair.timeMillis)
                    val m = textMeasurer.measure(
                        label,
                        TabularMonoStyle.copy(color = TextPrimary, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
                    )
                    val bw = m.size.width + 12f
                    val bx = (cx - bw / 2f).coerceIn(2f, (size.width - bw - 2f).coerceAtLeast(2f))
                    drawRoundRect(
                        color = ToolbarSurface,
                        topLeft = Offset(bx, 2f),
                        size = Size(bw, size.height - 4f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                    )
                    drawText(m, topLeft = Offset(bx + 6f, 5f))
                }
            }

            // Corner junction under Price Axis
            Box(
                modifier = Modifier
                    .width(priceAxisWidth)
                    .fillMaxHeight()
                    .background(CanvasVoid)
            )
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawChartDrawing(
    drawing: ChartDrawing,
    mapper: ChartCoordinateMapper,
    width: Float,
    isSelected: Boolean
) {
    val color = Color(drawing.colorHex)
    val pathEffect = when (drawing.lineStyle) {
        LineStyle.SOLID -> null
        LineStyle.DASHED -> PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f)
        LineStyle.DOTTED -> PathEffect.dashPathEffect(floatArrayOf(5f, 7f), 0f)
    }
    val strokeW = (drawing.strokeWidth * 1.4f).coerceIn(1.5f, 10f)

    when (drawing.type) {
        DrawingType.HORIZONTAL_LINE -> {
            val pt = drawing.points.firstOrNull() ?: return
            val y = mapper.priceToY(pt.price)
            drawLine(
                color = color,
                start = Offset(0f, y),
                end = Offset(width, y),
                strokeWidth = strokeW,
                pathEffect = pathEffect
            )
            if (isSelected) {
                drawCircle(color = color, radius = 8f, center = Offset(width * 0.5f, y))
                drawCircle(color = CanvasVoid, radius = 4f, center = Offset(width * 0.5f, y))
            }
        }
        DrawingType.TRENDLINE -> {
            if (drawing.points.size < 2) return
            val p1 = Offset(mapper.timeToX(drawing.points[0].timeMillis), mapper.priceToY(drawing.points[0].price))
            val p2 = Offset(mapper.timeToX(drawing.points[1].timeMillis), mapper.priceToY(drawing.points[1].price))
            drawLine(
                color = color,
                start = p1,
                end = p2,
                strokeWidth = strokeW,
                pathEffect = pathEffect
            )
            if (isSelected) {
                listOf(p1, p2).forEach { anchor ->
                    drawCircle(color = color, radius = 9f, center = anchor)
                    drawCircle(color = CanvasVoid, radius = 4.5f, center = anchor)
                }
            }
        }
        DrawingType.RECTANGLE -> {
            if (drawing.points.size < 2) return
            val x1 = mapper.timeToX(drawing.points[0].timeMillis)
            val y1 = mapper.priceToY(drawing.points[0].price)
            val x2 = mapper.timeToX(drawing.points[1].timeMillis)
            val y2 = mapper.priceToY(drawing.points[1].price)
            val left = min(x1, x2)
            val top = min(y1, y2)
            val rectSize = Size(abs(x2 - x1).coerceAtLeast(2f), abs(y2 - y1).coerceAtLeast(2f))
            drawRect(
                color = color.copy(alpha = 0.14f),
                topLeft = Offset(left, top),
                size = rectSize
            )
            drawRect(
                color = color,
                topLeft = Offset(left, top),
                size = rectSize,
                style = Stroke(width = strokeW, pathEffect = pathEffect)
            )
            if (isSelected) {
                listOf(Offset(x1, y1), Offset(x2, y2)).forEach { anchor ->
                    drawCircle(color = color, radius = 9f, center = anchor)
                    drawCircle(color = CanvasVoid, radius = 4.5f, center = anchor)
                }
            }
        }
    }
}
