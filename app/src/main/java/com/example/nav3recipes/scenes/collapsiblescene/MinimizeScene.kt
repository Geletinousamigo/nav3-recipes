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
        return when {
            // When there's only one entry, we can't minimize further - just pop out
            sceneEntries.size <= 1 -> OnBackResult(
                previousState = null, // pop out of this scene
                previousEntries = emptyList() // no previous entries when only one left
            )
            // When expanded with multiple entries, can minimize
            minimizeLayoutState.currentValue == MinimizeState.Expanded -> OnBackResult(
                previousState = MinimizeState.Minimized,
                previousEntries = sceneEntries.dropLast(1)
            )
            // When minimized with multiple entries, pop the minimizable entry
            minimizeLayoutState.currentValue == MinimizeState.Minimized -> OnBackResult(
                previousState = null, // pop out of minimize mode
                previousEntries = sceneEntries.dropLast(1)
            )
            // Default case (shouldn't happen, but required for exhaustiveness)
            else -> OnBackResult(
                previousState = null,
                previousEntries = sceneEntries.dropLast(1)
            )
        }
    }

    override val entries: List<NavEntry<T>>
        get() = sceneEntries


    override val content: @Composable () -> Unit = {
        val scaffoldValue = minimizeLayoutState.currentValue
        Log.d("MinimizeScene", "MinimizeScene.content")
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
                Log.d("MinimizeScene", "PredictiveBackHandler started. previousValue=$previousValue")

                // 1️⃣ Track user drag progress
                progress.collect { backEvent ->
                    val fraction = backProgressToStateProgress(
                        progress = backEvent.progress,
                        currentValue = minimizeLayoutState.currentValue
                    )
                    minimizeLayoutState.seekTo(
                        fraction = fraction,
                        target = previousValue!!,
                        predictiveBack = true
                    )
                }

                // 2️⃣ Gesture completed → animate to previous state (like BackHandler would)
                Log.d("MinimizeScene", "PredictiveBackHandler complete → animate to $previousValue")
                scope.launch {
                    minimizeLayoutState.animateTo(previousValue!!)
                }

                // 3️⃣ Also handle popping logic (like your existing onBack)
                repeat(entries.size - onBackResult.previousEntries.size) { onBack() }

            } catch (_: CancellationException) {
                // 4️⃣ Gesture canceled → restore to current state
                Log.d("MinimizeScene", "PredictiveBackHandler cancelled → animate back to $scaffoldValue")
                scope.launch {
                    minimizeLayoutState.animateTo(scaffoldValue)
                }
            }
        }
        Log.d("MinimizeScene", "MinimizeScene.typeSwitch")
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

            is MinimizeSceneType.Floating -> FloatingMinimizeContent(
                minimizeLayoutState = minimizeLayoutState,
                detailPlaceholder = { currentContent.detailPlaceholder() }
            )
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

        Log.d("MinimizeScene", "BottomMinimizeContent")

        val minimizableContent: @Composable (Modifier) -> Unit = { dragModifier ->
            Box(
                Modifier
                    .fillMaxSize()
                    .then(dragModifier)
            ) {
                lastMinimizableContent?.Content() ?: detailPlaceholder()
            }
        }

        val mainContent: @Composable (Dp) -> Unit = { bottomPaddingDp->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPaddingDp)
            ) {
                lastMainContent?.Content()
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
        val lastMinimizableContent = entries.findLast { getPaneMetadata(it) is MinimizePaneMetadata }

        val minimizableContent: @Composable (Modifier) -> Unit = { dragModifier ->
            Box(
                Modifier
                    .fillMaxSize()
                    .then(dragModifier)
            ) {
                lastMinimizableContent?.Content() ?: detailPlaceholder()
            }
        }

        val mainContent: @Composable (Dp) -> Unit = { bottomPaddingDp ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = bottomPaddingDp)
            ) {
                lastMainContent?.Content()
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

// Removed SinglePaneScene as it is not used

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
        Log.d("MinimizeSceneStrategy", "calculateScene: minimizeLayoutState = ${minimizeLayoutState.currentValue}")

        // Always return MinimizeScene to keep it alive, regardless of entry count
        Log.d("MinimizeSceneStrategy", "calculateScene: entries.size = ${entries.size}")

        // For single entry, we'll still create MinimizeScene but it will handle the single-entry case internally
        return MinimizeScene(
            key = "sceneKey",
            sceneEntries = entries, // Pass all entries, let MinimizeScene handle single vs multiple
            getPaneMetadata = ::getPaneMetadata,
            minimizeLayoutState = minimizeLayoutState,
            type = minimizeType,
            onBack = onBack
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
