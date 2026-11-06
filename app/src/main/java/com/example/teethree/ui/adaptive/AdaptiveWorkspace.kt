package com.example.teethree.ui.adaptive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi
import androidx.compose.material3.adaptive.WindowAdaptiveInfo
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.layout.AnimatedPane
import androidx.compose.material3.adaptive.layout.HingePolicy
import androidx.compose.material3.adaptive.layout.ListDetailPaneScaffoldRole
import androidx.compose.material3.adaptive.layout.PaneAdaptedValue
import androidx.compose.material3.adaptive.layout.ThreePaneScaffoldDestinationItem
import androidx.compose.material3.adaptive.layout.calculatePaneScaffoldDirective
import androidx.compose.material3.adaptive.navigation.BackNavigationBehavior
import androidx.compose.material3.adaptive.navigation.NavigableListDetailPaneScaffold
import androidx.compose.material3.adaptive.navigation.rememberListDetailPaneScaffoldNavigator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.window.core.layout.WindowSizeClass
import androidx.window.core.layout.computeWindowSizeClass
import kotlin.math.min
import kotlin.math.roundToInt
import com.example.teethree.ui.Backdrop
import com.example.teethree.ui.DockHost
import com.example.teethree.ui.LocalBackdrop
import com.example.teethree.ui.backdropSource
import com.example.teethree.ui.LocalDockHost
import kotlinx.coroutines.launch

@Composable
internal fun FoldSafeContent(
    adaptiveInfo: WindowAdaptiveInfo = currentWindowAdaptiveInfoV2(),
    avoidVerticalHinges: Boolean = false,
    content: @Composable (WindowAdaptiveInfo) -> Unit,
) {
    val density = LocalDensity.current
    val windowSize = LocalWindowInfo.current.containerSize
    var viewport by
        remember(windowSize) {
            mutableStateOf(Rect(0f, 0f, windowSize.width.toFloat(), windowSize.height.toFloat()))
        }
    val horizontalHinges =
        adaptiveInfo.windowPosture.hingeList
            .filter {
                !it.isVertical && (it.isSeparating || it.isOccluding || !it.isFlat)
            }
            .map { it.bounds }
    val verticalHinges =
        if (avoidVerticalHinges)
            adaptiveInfo.windowPosture.hingeList
                .filter {
                    it.isVertical && (it.isSeparating || it.isOccluding)
                }
                .map { it.bounds }
        else emptyList()
    val hasHinges = horizontalHinges.isNotEmpty() || verticalHinges.isNotEmpty()
    val imeHeight = WindowInsets.ime.getBottom(density)
    val region =
        foldSafeBounds(
            viewport,
            horizontalHinges,
            verticalHinges,
            if (imeHeight > 0) windowSize.height - imeHeight.toFloat() else null,
        )
    Box(Modifier.fillMaxSize().onGloballyPositioned { viewport = it.boundsInWindow() }) {
        val width = with(density) { region.width.toDp() }
        val height = with(density) { region.height.toDp() }
        val safeInfo =
            if (!hasHinges) adaptiveInfo
            else
                WindowAdaptiveInfo(
                    WindowSizeClass.BREAKPOINTS_V2.computeWindowSizeClass(
                        width.value,
                        height.value,
                    ),
                    adaptiveInfo.windowPosture,
                )
        val regionModifier =
            if (!hasHinges) Modifier.fillMaxSize()
            else
                Modifier.absoluteOffset {
                        IntOffset(
                            (region.left - viewport.left).roundToInt(),
                            (region.top - viewport.top).roundToInt(),
                        )
                    }
                    .size(width, height)
                    .clipToBounds()
                    .consumeWindowInsets(WindowInsets.ime)
                    .then(
                        if (region.bottom < viewport.bottom)
                            Modifier.consumeWindowInsets(WindowInsets.navigationBars)
                        else Modifier
                    )
                    .then(
                        if (region.top > viewport.top)
                            Modifier.consumeWindowInsets(WindowInsets.statusBars)
                        else Modifier
                    )
        Box(regionModifier.testTag("fold-safe-content")) { content(safeInfo) }
    }
}

internal fun foldSafeBounds(
    viewport: Rect,
    horizontalHinges: List<Rect>,
    verticalHinges: List<Rect>,
    keyboardTop: Float?,
): Rect {
    if (horizontalHinges.isEmpty() && verticalHinges.isEmpty()) return viewport
    var regions = listOf(viewport)
    horizontalHinges
        .sortedBy { it.top }
        .forEach { hinge ->
            regions = regions.flatMap { region ->
                if (
                    hinge.right <= region.left ||
                        hinge.left >= region.right ||
                        hinge.top >= region.bottom ||
                        hinge.bottom <= region.top
                ) {
                    listOf(region)
                } else
                    buildList {
                        if (hinge.top > region.top) add(region.copy(bottom = hinge.top))
                        if (hinge.bottom < region.bottom) add(region.copy(top = hinge.bottom))
                    }
            }
        }
    verticalHinges
        .sortedBy { it.left }
        .forEach { hinge ->
            regions = regions.flatMap { region ->
                if (
                    hinge.right <= region.left ||
                        hinge.left >= region.right ||
                        hinge.top >= region.bottom ||
                        hinge.bottom <= region.top
                ) {
                    listOf(region)
                } else
                    buildList {
                        if (hinge.left > region.left) add(region.copy(right = hinge.left))
                        if (hinge.right < region.right) add(region.copy(left = hinge.right))
                    }
            }
        }
    val visible = regions.map {
        it.copy(bottom = min(it.bottom, keyboardTop ?: it.bottom).coerceAtLeast(it.top))
    }
    return visible.maxByOrNull { it.width * it.height } ?: viewport.copy(bottom = viewport.top)
}

@OptIn(ExperimentalMaterial3AdaptiveApi::class)
@Composable
internal fun ConversationPaneScaffold(
    selectedThreadId: String?,
    adaptiveInfo: WindowAdaptiveInfo,
    modifier: Modifier = Modifier,
    selectionReady: Boolean = true,
    listPane: @Composable (openDetail: (String) -> Unit) -> Unit,
    detailPane: @Composable (showList: () -> Unit) -> Unit,
) {
    val navigator =
        rememberListDetailPaneScaffoldNavigator<String>(
            scaffoldDirective =
                calculatePaneScaffoldDirective(adaptiveInfo, HingePolicy.AvoidSeparating),
            isDestinationHistoryAware = false,
            initialDestinationHistory =
                buildList {
                    add(ThreePaneScaffoldDestinationItem(ListDetailPaneScaffoldRole.List, null))
                    if (selectedThreadId != null)
                        add(
                            ThreePaneScaffoldDestinationItem(
                                ListDetailPaneScaffoldRole.Detail,
                                selectedThreadId,
                            )
                        )
                },
        )
    val scope = rememberCoroutineScope()
    var observedThreadId by rememberSaveable { mutableStateOf(selectedThreadId) }
    // Adaptive 1.3 updates the directive without updating the pane transition state.
    LaunchedEffect(navigator.scaffoldDirective) {
        navigator.seekBack(BackNavigationBehavior.PopUntilCurrentDestinationChange, 0f)
    }
    LaunchedEffect(selectedThreadId, selectionReady) {
        if (!selectionReady) return@LaunchedEffect
        if (observedThreadId != selectedThreadId) {
            observedThreadId = selectedThreadId
            if (selectedThreadId == null) {
                if (navigator.currentDestination?.pane != ListDetailPaneScaffoldRole.List) {
                    if (
                        navigator.canNavigateBack(
                            BackNavigationBehavior.PopUntilCurrentDestinationChange
                        )
                    ) {
                        navigator.navigateBack(
                            BackNavigationBehavior.PopUntilCurrentDestinationChange
                        )
                    } else navigator.navigateTo(ListDetailPaneScaffoldRole.List)
                }
            } else if (
                navigator.currentDestination?.pane != ListDetailPaneScaffoldRole.Detail ||
                    navigator.currentDestination?.contentKey != selectedThreadId
            ) {
                navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, selectedThreadId)
            }
        }
    }
    // One pane at a time: the screens share a single floating dock that stays in place while
    // they slide, instead of each pane carrying its own.
    val singlePane = navigator.scaffoldDirective.maxHorizontalPartitions == 1
    val dockHost = remember { DockHost() }
    val backdrop = remember { Backdrop() }
    Box(modifier.fillMaxSize()) {
    // The panes are what the glass dock blurs; nothing inside them may blur them in turn.
    CompositionLocalProvider(
        LocalDockHost provides if (singlePane) dockHost else null,
        LocalBackdrop provides null,
    ) {
    NavigableListDetailPaneScaffold(
        navigator = navigator,
        modifier =
            if (singlePane) Modifier.fillMaxSize().backdropSource(backdrop)
            else Modifier.fillMaxSize(),
        defaultBackBehavior = BackNavigationBehavior.PopUntilCurrentDestinationChange,
        listPane = {
            AnimatedPane(Modifier.preferredWidth(320.dp).testTag("workspace-list-pane")) {
                listPane { threadId ->
                    scope.launch {
                        if (
                            navigator.currentDestination?.pane !=
                                ListDetailPaneScaffoldRole.Detail ||
                                navigator.currentDestination?.contentKey != threadId
                        ) {
                            navigator.navigateTo(ListDetailPaneScaffoldRole.Detail, threadId)
                        }
                    }
                }
            }
        },
        detailPane = {
            val framed =
                navigator.scaffoldValue[ListDetailPaneScaffoldRole.List] ==
                    PaneAdaptedValue.Expanded &&
                    navigator.scaffoldValue[ListDetailPaneScaffoldRole.Detail] ==
                        PaneAdaptedValue.Expanded
            AnimatedPane(
                Modifier.testTag("workspace-detail-pane")
                    .then(
                        if (framed) Modifier.padding(8.dp).clip(RoundedCornerShape(24.dp))
                        else Modifier
                    )
            ) {
                detailPane {
                    scope.launch {
                        if (navigator.currentDestination?.pane != ListDetailPaneScaffoldRole.List) {
                            navigator.navigateBack(
                                BackNavigationBehavior.PopUntilCurrentDestinationChange
                            )
                        }
                    }
                }
            }
        },
    )
    }
    if (singlePane)
        CompositionLocalProvider(LocalBackdrop provides backdrop) {
        dockHost.Overlay(
            if (navigator.currentDestination?.pane == ListDetailPaneScaffoldRole.Detail)
                DockSlots.Detail
            else DockSlots.List,
            Modifier.align(Alignment.BottomCenter),
        )
        }
    }
}

internal object DockSlots {
    const val List = "list"
    const val Detail = "detail"
}
