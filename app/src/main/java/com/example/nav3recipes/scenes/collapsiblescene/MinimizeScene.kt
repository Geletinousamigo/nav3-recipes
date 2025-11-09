package com.example.nav3recipes.scenes.collapsiblescene


import android.util.Log
import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SceneStrategyScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch


@Composable
fun <T : Any> rememberMinimizeSceneStrategy(
    minimizeLayoutState: MinimizeLayoutState = rememberMinimizeLayoutState(),
    minimizeType: MinimizeSceneType = MinimizeSceneType.Bottom({})
//    backNavigationBehavior: BackNavigationBehavior =
//        BackNavigationBehavior.PopUntilScaffoldValueChange,
//    directive: PaneScaffoldDirective = calculatePaneScaffoldDirective(currentWindowAdaptiveInfo()),
): SceneStrategy<T> {
    return remember(minimizeLayoutState, minimizeType) {
        MinimizeSceneStrategy(
            minimizeLayoutState = minimizeLayoutState,
            minimizeType = minimizeType
        )
    }
}


/**
 * A Scene which renders:
 *  - the "first" entry as the main content, and
 *  - the "second" entry inside your MinimizeLayout as the minimizable player.
 *
 * Behavior mirrors the TwoPaneScene shape from the sample — but instead of two side-by-side panes,
 * the second entry is the minimizable overlay.
 */
class MinimizeScene<T : Any>(
    override val key: Any,
    val sceneEntries: List<NavEntry<T>>,
    val getPaneMetadata: (NavEntry<T>) -> MinimizePaneMetadata?,
    val minimizeLayoutState: MinimizeLayoutState,
    val type: MinimizeSceneType,
    val onBack: () -> Unit,
//    override val previousEntries: List<NavEntry<T>>
) : Scene<T>
{
    override val previousEntries: List<NavEntry<T>>
        get() = onBackResult.previousEntries

    class OnBackResult<T : Any>(
        val previousState: MinimizeState?,
        val previousEntries: List<NavEntry<T>>,
    )

    val onBackResult: OnBackResult<T> = calculateOnBackResult()

    private fun calculateOnBackResult(): OnBackResult<T> {
        return when (minimizeLayoutState.currentValue) {
            MinimizeState.Expanded -> OnBackResult(
                previousState = MinimizeState.Minimized,
                previousEntries = sceneEntries.dropLast(1)
            )

            MinimizeState.Minimized -> OnBackResult(
                previousState = null, // pop out of this scene
                previousEntries = sceneEntries.dropLast(1)
            )
        }
    }

    override val entries: List<NavEntry<T>>
        get() = sceneEntries


    override val content: @Composable () -> Unit = {
        val scaffoldValue = minimizeLayoutState.currentValue
        logRecompose("MinimizeScene.content")
        // keep a stable snapshot of entries so popping doesn't create a blank frame

        val previousValue = if (minimizeLayoutState.currentValue == MinimizeState.Expanded) {
            MinimizeState.Minimized
        } else {
            null
        }
        Log.d("MinimizeScene", ": previousValue=$previousValue")
        val scope = rememberCoroutineScope()
        PredictiveBackHandler(enabled = previousValue != null) { progress ->
            try {

                Log.d(
                    "MinimizeScene",
                    "PredictiveBackHandler started. previousValue=$previousValue"
                )
                progress.collect { backEvent ->
                    val fraction = backProgressToStateProgress(
                        progress = backEvent.progress,
                        currentValue = minimizeLayoutState.currentValue
                    )
                    Log.d(
                        "MinimizeScene",
                        "PredictiveBackHandler progress: backEvent.progress=${backEvent.progress}, calculated fraction=$fraction"
                    )
                    minimizeLayoutState.seekTo(
                        fraction = fraction,
                        target = previousValue!!,
                        predictiveBack = true
                    )
                }
                Log.d("MinimizeScene", "PredictiveBackHandler completed, calling onBack.")
                repeat(entries.size - onBackResult.previousEntries.size) { onBack() }
            } catch (_: CancellationException) {
                Log.d(
                    "MinimizeScene",
                    "PredictiveBackHandler cancelled, animating back to $scaffoldValue"
                )
                scope.launch {
                    minimizeLayoutState.animateTo(scaffoldValue)
                }
            }
        }
        logRecompose("MinimizeScene.typeSwitch")
        Log.d(
            "MinimizeScene",
            "MinimizeScene.typeSwitch: previousValue=$previousValue, scaffoldValue=$scaffoldValue"
        )
        Log.d("MinimizeScene", "MinimizeScene.typeSwitch: currentContent=$type")
        when (val currentContent = type) {
            is MinimizeSceneType.Bottom -> BottomMinimizeContent(
                minimizeLayoutState = minimizeLayoutState,
                detailPlaceholder = { currentContent.detailPlaceholder() }
            )

            is MinimizeSceneType.Floating -> {
                FloatingMinimizeContent(
                    minimizeLayoutState = minimizeLayoutState,
                    detailPlaceholder = { currentContent.detailPlaceholder() }
                )
            }
        }
    }

    private fun backProgressToStateProgress(
        progress: Float,
        currentValue: MinimizeState
    ): Float {
        // Keep Material3’s predictive back easing for smooth feel
        val eased = CubicBezierEasing(0.1f, 0.1f, 0f, 1f).transform(progress)

        // For a two-state layout, it’s always a 1:1 mapping
        return when (currentValue) {
            MinimizeState.Expanded -> eased // collapsing towards Minimized
            MinimizeState.Minimized -> 0f   // no further back state
        }
    }

    @Composable
    private fun BottomMinimizeContent(
        minimizeLayoutState: MinimizeLayoutState,
        detailPlaceholder: @Composable () -> Unit,
    ) {
        val lastMainContent = entries.findLast { getPaneMetadata(it) is MainMetadata }
        val lastMinimizableContent = entries.findLast { getPaneMetadata(it) is MinimizableMetadata }

        logRecompose("BottomMinimizeContent")

        val minimizableContent = remember {
            movableContentOf<Modifier> { dragModifier ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(dragModifier)
                ) {
                    lastMinimizableContent?.Content() ?: detailPlaceholder()
                }
            }
        }

        val mainContent = remember {
            movableContentOf<Dp> { bottomPaddingDp ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPaddingDp)
                ) {
                    lastMainContent?.Content()
                }
            }
        }

        // ✅ Show plain content when only one entry
        if (entries.size == 1) {
            lastMainContent?.Content() ?: lastMinimizableContent?.Content() ?: detailPlaceholder()
        } else {
            BottomMinimizeLayout(
                modifier = Modifier.fillMaxSize(),
                minimizeLayoutState = minimizeLayoutState,
                minimizableContent = minimizableContent,
                content = mainContent
            )
        }
    }


    @Composable
    private fun FloatingMinimizeContent(
        minimizeLayoutState: MinimizeLayoutState,
        detailPlaceholder: @Composable () -> Unit,
    ) {
        val lastMainContent = entries.findLast { getPaneMetadata(it) is MainMetadata }
        val lastMinimizableContent =
            entries.findLast { getPaneMetadata(it) is MinimizePaneMetadata }

        val minimizableContent = remember {
            movableContentOf<Modifier> { dragModifier ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(dragModifier)
                ) {
                    lastMinimizableContent?.Content() ?: detailPlaceholder()
                }
            }
        }

        val mainContent = remember {
            movableContentOf<Dp> { bottomPaddingDp ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = bottomPaddingDp)
                ) {
                    lastMainContent?.Content()
                }
            }
        }

        // ✅ Skip minimized layout when only one item
        if (entries.size == 1) {
            lastMainContent?.Content() ?: lastMinimizableContent?.Content() ?: detailPlaceholder()
        } else {
            FloatingMinimizeLayout(
                modifier = Modifier.fillMaxSize(),
                minimizeLayoutState = minimizeLayoutState,
                minimizableContent = minimizableContent,
                content = mainContent
            )
        }
    }

}

class FloatingMinimizeScene<T : Any>(
    override val key: Any,
    override val previousEntries: List<NavEntry<T>>,
    private val firstEntry: NavEntry<T>,
    private val secondEntry: NavEntry<T>,
    private val minimizeLayoutState: MinimizeLayoutState
) : Scene<T> {

    override val entries: List<NavEntry<T>> = listOf(firstEntry, secondEntry)

    override val content: @Composable () -> Unit = {
        FloatingMinimizeLayout(
            modifier = Modifier.fillMaxSize(),
            minimizeLayoutState = minimizeLayoutState,
            minimizableContent = { dragModifier ->
                // This is the "player" part (B screen)
                Box(
                    Modifier
                        .fillMaxSize()
                        .then(dragModifier)
                ) {
                    secondEntry.Content()
                }
            }
        ) { bottomPaddingDp ->
            // This is the "main" screen (A screen)
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPaddingDp) // note: Dp, not PaddingValues
            ) {
                firstEntry.Content()
            }
        }
    }
}

//enum class MinimizeSceneType {
//    Bottom,
//    Floating;
//    fun isFloating() = this == Floating
//    fun isBottom() = this == Bottom
//}

sealed interface MinimizeSceneType {
    class Bottom(val detailPlaceholder: @Composable () -> Unit) : MinimizeSceneType
    class Floating(val detailPlaceholder: @Composable () -> Unit) : MinimizeSceneType

    fun isFloating() = this is Floating
    fun isBottom() = this is Bottom
}

data class SinglePaneScene<T : Any>(
    override val key: Any,
    val entry: NavEntry<T>,
    override val previousEntries: List<NavEntry<T>>,
) : Scene<T> {
    override val entries: List<NavEntry<T>> = listOf(entry)

    override val content: @Composable () -> Unit = { entry.Content() }
}

/**
 * SceneStrategy that returns a MinimizeVideoScene when:
 *   - there are at least two entries on the back stack, and
 *   - the top entry (last) is marked as minimizable.
 *
 * This mirrors TwoPaneSceneStrategy but uses the two-most-recent entries to render:
 * first = previous entry, second = top (player) entry.
 */
class MinimizeSceneStrategy<T : Any>(
    private val minimizeLayoutState: MinimizeLayoutState,
    private val minimizeType: MinimizeSceneType,
) : SceneStrategy<T> {

    override fun SceneStrategyScope<T>.calculateScene(entries: List<NavEntry<T>>): Scene<T>? {
        if (entries.isEmpty()) return null
        val lastPaneMetadata = getPaneMetadata(entries.last()) ?: return null
        val sceneKey = lastPaneMetadata.sceneKey
        Log.d ("MinimizeSceneStrategy", "calculateScene: sceneKey = $sceneKey")
        Log.d("MinimizeSceneStrategy", "calculateScene: minimizeLayoutState = ${minimizeLayoutState.currentValue}")
        // Case 1: Multiple entries (normal minimize mode)
        Log.d("MinimizeSceneStrategy", "calculateScene: is entries.size > 2=${entries.size >= 2}")
        if (entries.size >= 2) {
            val firstMeta = getPaneMetadata(entries[entries.size - 2])
            val secondMeta =
                getPaneMetadata(entries.last())
            if (firstMeta is MainMetadata && secondMeta is MinimizableMetadata) {
                val first = entries[entries.size - 2]
                val second = entries.last()
                return MinimizeScene(
                    key = "sceneKey",
                    sceneEntries = listOf(first, second),
                    getPaneMetadata = ::getPaneMetadata,
                    minimizeLayoutState = minimizeLayoutState,
                    type = minimizeType,
                    onBack = onBack
                )
            }
        } // Case 2: Single entry — show it directly (avoid null)
        Log.d("MinimizeSceneStrategy", "calculateScene: entries=${entries}")
        Log.d("MinimizeSceneStrategy", "calculateScene: I am at case 2, entries size=${entries.size}")
        val singleEntry = entries.last()
        return SinglePaneScene(
            key = "sceneKey",
            entry = singleEntry,
            previousEntries = entries.dropLast(1)
        )
    }


    /**
     * Helper for marking an entry as "minimizable" (i.e. a player that should be shown as the second pane).
     */
    companion object {
        internal const val MinimizeRoleKey: String = "MinimizeRole"

        fun mainPane(
            sceneKey: Any = Unit
        ): Map<String, Any> = mapOf(MinimizeRoleKey to MainMetadata(sceneKey))

        fun minimizablePane(
            type: MinimizeSceneType,
            sceneKey: Any = Unit,
        ): Map<String, Any> = mapOf(MinimizeRoleKey to MinimizableMetadata(sceneKey, type))

        internal fun <T : Any> getPaneMetadata(entry: NavEntry<T>): MinimizePaneMetadata? =
            entry.metadata[MinimizeRoleKey] as? MinimizePaneMetadata
    }
}

sealed interface MinimizePaneMetadata {
    val sceneKey: Any
    val role: MinimizeScaffoldRole
}

internal class MainMetadata(
    override val sceneKey: Any
) : MinimizePaneMetadata {
    override val role: MinimizeScaffoldRole
        get() = MinimizeScaffoldRole.Main
}

internal class MinimizableMetadata(
    override val sceneKey: Any,
    val type: MinimizeSceneType
) : MinimizePaneMetadata {
    override val role: MinimizeScaffoldRole
        get() = MinimizeScaffoldRole.Minimizable
}


enum class MinimizeScaffoldRole {
    /**
     * The primary pane of MinimizeScene.
     * This is the main background content shown when a minimizable entry is present.
     */
    Main,

    /**
     * The secondary pane of MinimizeScene.
     * This is the minimizable foreground content (e.g., player).
     */
    Minimizable,
}
