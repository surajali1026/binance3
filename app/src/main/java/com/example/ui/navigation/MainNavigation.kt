package com.example.ui.navigation

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.CandlestickChart
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.rememberCoroutineScope
import com.example.AppContainer
import com.example.data.remote.UpdateCheckState
import com.example.ui.chart.AlertsManagerSheet
import com.example.ui.chart.ChartScreen
import com.example.ui.chart.ChartViewModel
import com.example.ui.chart.CreateAlertSheet
import com.example.ui.components.UpdatePromptBanner
import com.example.ui.theme.AmberGold
import com.example.ui.theme.BorderSubtle
import com.example.ui.theme.CanvasVoid
import com.example.ui.theme.ElevatedSurface
import com.example.ui.theme.TextMuted
import com.example.ui.watchlist.WatchlistScreen
import com.example.ui.watchlist.WatchlistViewModel
import kotlinx.coroutines.launch

enum class MainTab {
    WATCHLIST,
    CHART
}

@Composable
fun MainNavigation(
    initialSymbolFromNotification: String? = null
) {
    val context = LocalContext.current
    val container = remember(context) { AppContainer.get(context) }

    val watchlistViewModel: WatchlistViewModel = viewModel(
        factory = WatchlistViewModel.factory(container)
    )
    val chartViewModel: ChartViewModel = viewModel(
        factory = ChartViewModel.factory(context, container)
    )

    val chartState by chartViewModel.uiState.collectAsStateWithLifecycle()
    val updateCheckState by container.gitHubUpdateChecker.state.collectAsStateWithLifecycle()
    val dismissedUpdateTag by container.gitHubUpdateChecker.dismissedTag.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showAlertsFromWatchlist by remember { mutableStateOf(false) }
    var showQuickAlertFromWatchlist by remember { mutableStateOf(false) }

    val triggerManualUpdateCheck: () -> Unit = {
        scope.launch {
            container.gitHubUpdateChecker.checkForUpdates(isManual = true)
        }
    }

    LaunchedEffect(Unit) {
        container.gitHubUpdateChecker.checkOnStartupIfNeeded()
    }

    var activeTab by rememberSaveable {
        mutableStateOf(
            if (!initialSymbolFromNotification.isNullOrBlank()) MainTab.CHART else MainTab.WATCHLIST
        )
    }

    LaunchedEffect(initialSymbolFromNotification) {
        if (!initialSymbolFromNotification.isNullOrBlank()) {
            chartViewModel.openSymbol(initialSymbolFromNotification)
            activeTab = MainTab.CHART
        }
    }

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(CanvasVoid)
    ) {
        val isExpandedWidth = maxWidth >= 840.dp
        val isPhoneLandscape = isLandscape && !isExpandedWidth

        if (isExpandedWidth) {
            // Tablet / Foldable / Wide Landscape Split Pane (collapses cleanly in full-screen)
            Row(modifier = Modifier.fillMaxSize()) {
                if (!chartState.isFullScreen) {
                    Box(
                        modifier = Modifier
                            .width(340.dp)
                            .fillMaxHeight()
                    ) {
                        WatchlistScreen(
                            viewModel = watchlistViewModel,
                            selectedSymbol = chartState.symbol,
                            onSelectSymbol = { symbol ->
                                chartViewModel.openSymbol(symbol)
                            },
                            onSelectSymbolWithContext = { symbol, ctxSymbols, ctxLabel ->
                                chartViewModel.openSymbol(symbol, ctxSymbols, ctxLabel)
                            },
                            onOpenAlerts = { showAlertsFromWatchlist = true },
                            applyBottomInset = true
                        )
                    }
                    VerticalDivider(color = BorderSubtle, thickness = 0.75.dp)
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                ) {
                    ChartScreen(
                        viewModel = chartViewModel,
                        showBackButton = false,
                        onBackToWatchlist = {},
                        applyBottomInset = true,
                        updateCheckState = updateCheckState,
                        onCheckForUpdates = triggerManualUpdateCheck
                    )
                }
            }
        } else {
            // Separate Watchlist & Chart Tabs on handheld screens
            BackHandler(enabled = activeTab == MainTab.CHART && !chartState.isFullScreen) {
                activeTab = MainTab.WATCHLIST
            }

            Column(modifier = Modifier.fillMaxSize()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) {
                    when (activeTab) {
                        MainTab.WATCHLIST -> {
                            WatchlistScreen(
                                viewModel = watchlistViewModel,
                                selectedSymbol = chartState.symbol,
                                onSelectSymbol = { symbol ->
                                    chartViewModel.openSymbol(symbol)
                                    activeTab = MainTab.CHART
                                },
                                onSelectSymbolWithContext = { symbol, ctxSymbols, ctxLabel ->
                                    chartViewModel.openSymbol(symbol, ctxSymbols, ctxLabel)
                                    activeTab = MainTab.CHART
                                },
                                onOpenAlerts = { showAlertsFromWatchlist = true },
                                applyBottomInset = false
                            )
                        }

                        MainTab.CHART -> {
                            ChartScreen(
                                viewModel = chartViewModel,
                                showBackButton = isPhoneLandscape,
                                onBackToWatchlist = { activeTab = MainTab.WATCHLIST },
                                applyBottomInset = false,
                                updateCheckState = updateCheckState,
                                onCheckForUpdates = triggerManualUpdateCheck
                            )
                        }
                    }
                }

                val hideBottomStrip = chartState.isFullScreen || (activeTab == MainTab.CHART && isPhoneLandscape)
                if (!hideBottomStrip) {
                    BottomTabStrip(
                        activeTab = activeTab,
                        activeSymbol = chartState.symbol,
                        onSelectTab = { activeTab = it }
                    )
                }
            }
        }

        // Dismissible GitHub Auto-Update Banner (surajali1026/binance3)
        val availableRelease = (updateCheckState as? UpdateCheckState.UpdateAvailable)?.release
        if (availableRelease != null && dismissedUpdateTag != availableRelease.tagName) {
            UpdatePromptBanner(
                release = availableRelease,
                onDismiss = { container.gitHubUpdateChecker.dismissBanner(availableRelease.tagName) },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            )
        }
    }

    if (showAlertsFromWatchlist) {
        AlertsManagerSheet(
            state = chartState,
            onCreateNewAlertClick = { showQuickAlertFromWatchlist = true },
            onToggleAlertActive = chartViewModel::toggleAlertActive,
            onDeleteAlert = chartViewModel::deleteAlert,
            onClearHistory = chartViewModel::clearAlertHistory,
            onDismiss = { showAlertsFromWatchlist = false }
        )
    }

    if (showQuickAlertFromWatchlist) {
        CreateAlertSheet(
            symbol = chartState.symbol,
            initialPrice = chartState.currentPrice ?: 0.0,
            currentPrice = chartState.currentPrice,
            precision = chartState.contract?.pricePrecision ?: 2,
            linkedDrawing = null,
            onCreate = { price, cond, linked ->
                chartViewModel.createAlert(price, cond, linked)
            },
            onDismiss = { showQuickAlertFromWatchlist = false }
        )
    }
}

@Composable
private fun BottomTabStrip(
    activeTab: MainTab,
    activeSymbol: String,
    onSelectTab: (MainTab) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ElevatedSurface)
            .windowInsetsPadding(WindowInsets.navigationBars)
    ) {
        HorizontalDivider(color = BorderSubtle, thickness = 0.75.dp)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            BottomTabItem(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = "Watchlist",
                selected = activeTab == MainTab.WATCHLIST,
                testTag = "tab_watchlist",
                onClick = { onSelectTab(MainTab.WATCHLIST) },
                modifier = Modifier.weight(1f)
            )
            BottomTabItem(
                icon = Icons.Filled.CandlestickChart,
                label = "Chart • $activeSymbol",
                selected = activeTab == MainTab.CHART,
                testTag = "tab_chart",
                onClick = { onSelectTab(MainTab.CHART) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun BottomTabItem(
    icon: ImageVector,
    label: String,
    selected: Boolean,
    testTag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tint = if (selected) AmberGold else TextMuted
    Row(
        modifier = modifier
            .fillMaxHeight()
            .clickable(onClick = onClick)
            .testTag(testTag),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = label,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = tint,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 12.sp
        )
    }
}
