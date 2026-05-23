package com.example.nav3recipes.scenes.collapsiblescene

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.annotation.FloatRange
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.debugInspectorInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.fontscaling.MathUtils.lerp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Stable
@Immutable
enum class MinimizeState {
    Expanded,
    Minimized
}


/**
 * Public read-only state of the Minimize layout.
 * Similar in pattern to ThreePaneScaffoldState.
 */
@Stable
sealed class MinimizeLayoutState {
    abstract val animationSpec: AnimationSpec<Float>
    abstract val currentValue: MinimizeState
    abstract val targetValue: MinimizeState
    @get:FloatRange(from = 0.0, to = 1.0)
    abstract val progressFraction: Float

    @get:FloatRange(from = 0.0, to = 1.0)
    abstract val expandProgress: Float

    @get:FloatRange(from = 0.0, to = 1.0)
    abstract val minimizeProgress: Float
    abstract val isPredictiveBackInProgress: Boolean

    abstract val anchoredState: AnchoredDraggableState<MinimizeState>

    abstract val offset: Float
    abstract fun requireOffset(): Float

    @Composable
    internal abstract fun rememberTransition(): Transition<MinimizeState>

    abstract fun updateAnchors(anchors: DraggableAnchors<MinimizeState>)

    abstract suspend fun snapTo(target: MinimizeState)
    abstract suspend fun seekTo(
        @FloatRange(from = 0.0, to = 1.0) fraction: Float,
        target: MinimizeState,
        predictiveBack: Boolean
    )
    abstract suspend fun animateTo(
        target: MinimizeState,
        spec: AnimationSpec<Float> = animationSpec,
        predictiveBack: Boolean = false
    )
}
/**
 * Mutable implementation of [MinimizeLayoutState].
 */
@Stable
class MutableMinimizeLayoutState(
    initialValue: MinimizeState,
    private val animSpec: AnimationSpec<Float> = tween(300)
) : MinimizeLayoutState()
{

    override val animationSpec get() = animSpec

    private val transitionState = SeekableTransitionState(initialValue)
    private val mutatorMutex = MutatorMutex()

    override val anchoredState = AnchoredDraggableState(initialValue)

    // predictiveStartState stores the semantic state when predictive back begins.
    // While predictive is in progress, we return this as currentValue to avoid mid-gesture flips.
    private var predictiveStartState: MinimizeState? = null

    override val currentValue: MinimizeState
        get() = if (isPredictiveBackInProgress) {
            predictiveStartState ?: anchoredState.currentValue
        } else anchoredState.currentValue

    override val targetValue: MinimizeState
        get() = anchoredState.targetValue

    override val expandProgress: Float
        get() = anchoredState.progress(from = MinimizeState.Minimized, to = MinimizeState.Expanded)

    override val minimizeProgress: Float
        get() = anchoredState.progress(from = MinimizeState.Expanded, to = MinimizeState.Minimized)

    override val progressFraction: Float
        get() = when {
            currentValue == targetValue -> 0f
            targetValue == MinimizeState.Expanded ->
                anchoredState.progress(from = MinimizeState.Minimized, to = MinimizeState.Expanded)
            else ->
                anchoredState.progress(from = MinimizeState.Expanded, to = MinimizeState.Minimized)
        }


//        get() = if (isPredictiveBackInProgress) {
//            // During predictive back, driven by transitionFraction we set in seekTo
//            transitionState.fraction
//        } else {
//            // Otherwise prefer the draggable progress (physical) to drive UI.
//            // This keeps UI consistent during programmatic drags/animations.
//            anchoredState.progress(from = MinimizeState.Minimized, to = MinimizeState.Expanded)
//        }

    override var isPredictiveBackInProgress by mutableStateOf(false)
        private set

    @Composable
    override fun rememberTransition(): Transition<MinimizeState> =
        rememberTransition(transitionState, label = "MinimizeLayoutState")

    override val offset: Float
        get() = anchoredState.requireOffset()

    override fun requireOffset(): Float = anchoredState.requireOffset()

    override suspend fun snapTo(target: MinimizeState) {
        mutatorMutex.mutate {
            // End predictive mode if any
            isPredictiveBackInProgress = false
            predictiveStartState = null
            anchoredState.snapTo(target)
            transitionState.snapTo(target)
        }
    }

    override suspend fun animateTo(
        target: MinimizeState,
        spec: AnimationSpec<Float>,
        predictiveBack: Boolean
    ) {
        mutatorMutex.mutate {
            try {
                // Clear predictive start if we're starting a real animation (but track flag briefly)
                isPredictiveBackInProgress = predictiveBack
                // Run the real draggable animation (pixel/anchor-based)
                anchoredState.animateTo(target, spec)
                // Sync the semantic/transition state (finite spec only)
                val finiteSpec = spec as? FiniteAnimationSpec<Float>
                transitionState.animateTo(target, finiteSpec)
            } finally {
                isPredictiveBackInProgress = false
                predictiveStartState = null
            }
        }
    }

    override suspend fun seekTo(
        @FloatRange(from = 0.0, to = 1.0) fraction: Float,
        target: MinimizeState,
        predictiveBack: Boolean
    ) {
        mutatorMutex.mutate {
            isPredictiveBackInProgress = predictiveBack

            val anchors = anchoredState.anchors

            // determine start and end based on direction
            val from = when (target) {
                MinimizeState.Expanded -> MinimizeState.Minimized
                MinimizeState.Minimized -> MinimizeState.Expanded
            }

            val fromOffset = anchors.positionOf(from)
            val toOffset = anchors.positionOf(target)

            // use *absolute progress* from anchors instead of raw fraction
            val clampedFraction = fraction.coerceIn(0f, 1f)
            val offset = lerp(fromOffset, toOffset, clampedFraction)

            // Smoothly update offset using dragDelta (not absolute)
            val delta = offset - anchoredState.requireOffset()
            if (abs(delta) > 0.5f) { // prevent micro-jitter
                anchoredState.dispatchRawDelta(delta)
            }

            // Sync Compose transition for visual states
            transitionState.seekTo(clampedFraction, target)
        }
    }





    fun dispatchDelta(delta: Float) {
        anchoredState.dispatchRawDelta(delta)
    }

    override fun updateAnchors(anchors: DraggableAnchors<MinimizeState>) {
        anchoredState.updateAnchors(anchors)
    }

    companion object {
        fun Saver(
            animationSpec: AnimationSpec<Float> = tween(300)
        ): Saver<MutableMinimizeLayoutState, MinimizeState> = Saver(
            save = { it.currentValue },
            restore = { MutableMinimizeLayoutState(it, animationSpec) }
        )
    }
}


/**
 * Convenience for remembering and saving state across recompositions.
 */
@Composable
fun rememberMinimizeLayoutState(
    initialValue: MinimizeState = MinimizeState.Minimized,
    animationSpec: AnimationSpec<Float> = tween(300)
): MutableMinimizeLayoutState {
    return rememberSaveable(
        saver = MutableMinimizeLayoutState.Saver(animationSpec)
    ) {
        MutableMinimizeLayoutState(initialValue, animationSpec)
    }
}


//class MinimizeLayoutState(
//    initialValue: MinimizeState,
//    private val animationSpec: AnimationSpec<Float> = tween(300)
//)
//{
//    // internal anchored draggable state
//    val anchoredState = AnchoredDraggableState(initialValue = initialValue)
//
//    // Expose progres State
//    val expandProgress: Float
//        get() = anchoredState.progress(from = MinimizeState.Minimized, to = MinimizeState.Expanded)
//
//    val minimizeProgress: Float
//        get() = anchoredState.progress(from = MinimizeState.Expanded, to = MinimizeState.Minimized)
//
//
//    // Expose current settled (or target) value
//    val currentValue: MinimizeState
//        get() = anchoredState.currentValue
//
//    val targetValue: MinimizeState
//        get() = anchoredState.targetValue
//
//    // The offset (px). Use requireOffset() after anchors are set.
//    val offset: Float
//        get() = anchoredState.requireOffset()
//
//
//    fun requireOffset(): Float = anchoredState.requireOffset()
//
//    suspend fun animateTo(target: MinimizeState) {
//        anchoredState.animateTo(target, animationSpec)
//    }
//
//    suspend fun snapTo(target: MinimizeState) {
//        anchoredState.snapTo(target)
//    }
//
//    suspend fun settle() {
//        anchoredState.settle(animationSpec)
//    }
//
//    fun dispatchDelta(delta: Float) {
//        anchoredState.dispatchRawDelta(delta)
//    }
//
//    // Update anchors dynamically (for example, after layout measurement)
//    fun updateAnchors(anchors: DraggableAnchors<MinimizeState>) {
//        anchoredState.updateAnchors(anchors)
//    }
//
//    companion object {
//        fun Saver(
//            animationSpec: AnimationSpec<Float>
//        ): Saver<MinimizeLayoutState, MinimizeState> = Saver(
//            save = { it.currentValue },
//            restore = { MinimizeLayoutState(it, animationSpec) }
//        )
//    }
//}

//@Composable
//fun rememberMinimizeLayoutState(
//    initialValue: MinimizeState = MinimizeState.Expanded,
//    animationSpec: AnimationSpec<Float> = tween(300)
//): MinimizeLayoutState {
//    return rememberSaveable(
//        saver = MinimizeLayoutState.Saver(animationSpec)
//    ) {
//        MinimizeLayoutState(initialValue = initialValue, animationSpec = animationSpec)
//    }
//}

private class MinimizeAnchorsElement(
    private val state: MinimizeLayoutState,
    private val minimizedHeight: Density.(Dp) -> Dp
) : ModifierNodeElement<MinimizeAnchorsNode>() {

    override fun InspectorInfo.inspectableProperties() {
        debugInspectorInfo {
            properties["state"] = state
            properties["directions"] = minimizedHeight
        }
    }

    override fun create(): MinimizeAnchorsNode =
        MinimizeAnchorsNode(state, minimizedHeight)

    override fun update(node: MinimizeAnchorsNode) {
        node.state = state
        node.minimizedHeight = minimizedHeight
    }

    override fun equals(other: Any?): Boolean {
        return other is MinimizeAnchorsElement &&
                state == other.state &&
                minimizedHeight == other.minimizedHeight
    }

    override fun hashCode(): Int {
        var result = state.hashCode()
        result = 31 * result + minimizedHeight.hashCode()
        return result
    }
}

private class MinimizeAnchorsNode(
    var state: MinimizeLayoutState,
    var minimizedHeight: Density.(Dp) -> Dp
) : Modifier.Node(), LayoutModifierNode {
    private var didLookahead = false

    override fun onDetach() {
        didLookahead = false
    }

    override fun MeasureScope.measure(
        measurable: Measurable,
        constraints: Constraints
    ): MeasureResult {
        val fullHeightPx = constraints.maxHeight.toFloat()
        val fullWidthPx = constraints.maxWidth.toFloat()
        val basePxToUse = max(fullHeightPx, fullWidthPx)
        val minimizedPx = with(this) {
            minimizedHeight(basePxToUse.toDp()).toPx()
        }.coerceAtMost(basePxToUse)

        if (isLookingAhead || !didLookahead) {
            val newAnchors = DraggableAnchors {
                MinimizeState.Expanded at 0f
                MinimizeState.Minimized at fullHeightPx - minimizedPx
            }
            state.updateAnchors(newAnchors)
        }
        didLookahead = isLookingAhead || didLookahead

        val xOffset = if (isLookingAhead) {
            state.anchoredState.anchors.positionOf(state.targetValue)
        } else state.requireOffset()
        val heightPx = (fullHeightPx - xOffset.roundToInt()).coerceIn(minimizedPx, fullHeightPx)
        val placeable = measurable.measure(
            constraints.copy(
                minWidth = constraints.minWidth, // respect parent min
                maxWidth = constraints.maxWidth, // don’t override to full width manually
                minHeight = heightPx.toInt(),
                maxHeight = heightPx.toInt()
            )
        )
        return layout(placeable.width, heightPx.toInt()) {
            placeable.place(0, 0)
        }
    }


}


private fun Modifier.minimizeAnchors(
    state: MinimizeLayoutState,
    minimizedHeight: Density.(Dp) -> Dp
) = this then MinimizeAnchorsElement(state, minimizedHeight)

@Composable
private fun Modifier.minimizeAnchors2(
    state: MinimizeLayoutState,
    minimizedHeight: Density.(Dp) -> Dp
): Modifier {
    val density = LocalDensity.current

    return this.layout { measurable, constraints ->
        val fullHeightPx = constraints.maxHeight.toFloat()
        val fullWidthPx = constraints.maxWidth.toFloat()
        val basePxToUse = max(fullHeightPx, fullWidthPx)

        // convert minimized height lambda (density captured above)
        val minimizedPx = with(density) {
            minimizedHeight(basePxToUse.toDp()).toPx()
        }.coerceAtMost(basePxToUse)

        // Build anchors same as before and update state
        val newAnchors = DraggableAnchors {
            MinimizeState.Expanded at 0f
            MinimizeState.Minimized at fullHeightPx - minimizedPx
        }
        // Call updateAnchors so state has valid anchors for offset calls
        state.updateAnchors(newAnchors)

        // choose offset safely (state.requireOffset() may throw if anchors not set,
        // but we already called updateAnchors)
        val xOffset = try {
            state.requireOffset()
        } catch (e: Throwable) {
            0f
        }

        val heightPx = (fullHeightPx - xOffset.roundToInt()).coerceIn(minimizedPx, fullHeightPx).toInt()

        // measure child fixed to computed height
        val placeable = measurable.measure(
            constraints.copy(
                minHeight = heightPx,
                maxHeight = heightPx
            )
        )

        layout(placeable.width, heightPx) {
            placeable.place(0, 0)
        }
    }
}


@Composable
fun FloatingMinimizeLayout(
    minimizableContent: @Composable (draggableModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    minimizeLayoutState: MinimizeLayoutState = rememberMinimizeLayoutState(),
    content: @Composable (bottomPaddingDp: Dp) -> Unit
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val config = LocalConfiguration.current

    val screenWidthPx = with(density) { config.screenWidthDp.dp.toPx() }
    val screenHeightPx = with(density) { config.screenHeightDp.dp.toPx() }

    val isExpanded by remember(minimizeLayoutState.currentValue) {
        derivedStateOf { minimizeLayoutState.currentValue == MinimizeState.Expanded }
    }

    // anchors for draggable (expanded <-> minimized)
    val anchors = DraggableAnchors {
        MinimizeState.Expanded at 0f
        MinimizeState.Minimized at screenHeightPx
    }
    LaunchedEffect(screenHeightPx) {
        minimizeLayoutState.updateAnchors(anchors)
    }

//    BackHandler(isExpanded) {
//        scope.launch { minimizeLayoutState.animateTo(MinimizeState.Minimized) }
//    }

    val draggableModifier = Modifier.anchoredDraggable(
        state = minimizeLayoutState.anchoredState,
        orientation = Orientation.Vertical
    )

    val progress by animateFloatAsState(
        targetValue = if (isExpanded) 0f else 1f,
        label = "minimizeProgress"
    )

    // Expanded = full screen
    val expandedWidthPx = screenWidthPx
    val expandedHeightPx = screenHeightPx

    val baseSize = min(screenWidthPx, screenHeightPx)

    // Minimized = 16:9 PiP, half of shorter side
    var isWidePip by remember { mutableStateOf(false) }

    val targetMinimizedWidthPx by animateFloatAsState(
        targetValue = if (isWidePip) screenWidthPx - with(density) { 32.dp.toPx() } // leave side padding
        else baseSize / 2f,
        label = "minimizedWidth"
    )
    val targetMinimizedHeightPx = targetMinimizedWidthPx * 3f / 4f

    val minimizedWidthPx = targetMinimizedWidthPx
    val minimizedHeightPx = targetMinimizedHeightPx

    val widthPx = lerp(expandedWidthPx, minimizedWidthPx, progress)
    val heightPx = lerp(expandedHeightPx, minimizedHeightPx, progress)

    // padding for PiP margin
    val paddingPx = with(density) { 16.dp.toPx() }

    // default bottom-end corner
    val initialPipX = (screenWidthPx - minimizedWidthPx - paddingPx).coerceAtLeast(paddingPx)
    val initialPipY = (screenHeightPx - minimizedHeightPx - paddingPx).coerceAtLeast(paddingPx)

    // Animatable x/y for PiP
    val pipX = remember { Animatable(0f) }
    val pipY = remember { Animatable(0f) }

    LaunchedEffect(screenWidthPx, screenHeightPx) {
        scope.launch {
            launch { pipX.snapTo(initialPipX) }
            launch { pipY.snapTo(initialPipY) }
        }
    }

    LaunchedEffect(targetMinimizedWidthPx, targetMinimizedHeightPx, screenWidthPx, screenHeightPx) {
        val padding = paddingPx

        val maxX = (screenWidthPx - targetMinimizedWidthPx - padding).coerceAtLeast(padding)
        val maxY = (screenHeightPx - targetMinimizedHeightPx - padding).coerceAtLeast(padding)

        val newX = pipX.value.coerceIn(padding, maxX)
        val newY = pipY.value.coerceIn(padding, maxY)

        scope.launch {
            pipX.snapTo(newX)
            pipY.snapTo(newY)
        }
    }

    var lastDragDelta by remember { mutableStateOf(Offset.Zero) }

    // TODO("Implement Zoom Modifier")
//    val zoomScale = remember { mutableFloatStateOf(1f) }
//    val minZoom = 0.5f
//    val maxZoom = 2f
//
//    val zoomModifier = Modifier.pointerInput(
//        minimizedWidthPx,
//        minimizedHeightPx,
//        screenWidthPx,
//        screenHeightPx
//    ) {
//        detectTransformGestures(
//            onGesture = { _, pan, zoom, _ ->
//                // --- handle drag (pan) ---
//                val newX = (pipX.value + pan.x)
//                    .coerceIn(paddingPx, screenWidthPx - minimizedWidthPx * zoomScale.value - paddingPx)
//                val newY = (pipY.value + pan.y)
//                    .coerceIn(paddingPx, screenHeightPx - minimizedHeightPx * zoomScale.value - paddingPx)
//
//                scope.launch {
//                    pipX.snapTo(newX)
//                    pipY.snapTo(newY)
//                }
//
//                // --- Handle pinch zoom ---
//                val newZoom = (zoomScale.value * zoom).coerceIn(minZoom, maxZoom)
//                zoomScale.value = newZoom
//            }
//        )
//    }

    val pipDragModifier = Modifier.pointerInput(
        minimizedWidthPx,
        minimizedHeightPx,
        screenWidthPx,
        screenHeightPx
    ) {
        detectDragGestures(
            onDragStart = {
                scope.launch {
                    pipX.stop()
                    pipY.stop()
                }
            },
            onDrag = { change, dragAmount ->
                lastDragDelta = Offset(dragAmount.x, dragAmount.y) // track last delta

                val newX = (pipX.value + dragAmount.x)
                    .coerceIn(paddingPx, screenWidthPx - minimizedWidthPx - paddingPx)
                val newY = (pipY.value + dragAmount.y)
                    .coerceIn(paddingPx, screenHeightPx - minimizedHeightPx - paddingPx)

                scope.launch {
                    pipX.snapTo(newX)
                    pipY.snapTo(newY)
                }
                change.consume()
            },
            onDragEnd = {
                // Directional preference from last drag
                val horizontalPref = if (lastDragDelta.x < 0) "left" else "right"
                val verticalPref = if (lastDragDelta.y < 0) "top" else "bottom"

                val targetX = if (horizontalPref == "left") paddingPx
                else screenWidthPx - minimizedWidthPx - paddingPx
                val targetY = if (verticalPref == "top") paddingPx
                else screenHeightPx - minimizedHeightPx - paddingPx

                scope.launch {
                    val tweenSpec = tween<Float>(
                        durationMillis = 300,
                        easing = FastOutSlowInEasing // smooth start, gentle stop
                    )
                    val springSpec = spring<Float>(
                        dampingRatio = Spring.DampingRatioLowBouncy,
                        stiffness = Spring.StiffnessLow
                    )
                    launch { pipX.animateTo(targetX, springSpec) }
                    launch { pipY.animateTo(targetY, springSpec) }
                }
            }
        )
    }



    val offsetX = lerp(0f, pipX.value, progress)
    val offsetY = lerp(0f, pipY.value, progress)

    Box(modifier = modifier.fillMaxSize()) {
        content(0.dp)

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(
                    with(density) {
                        (widthPx).coerceIn(minimizedWidthPx, screenWidthPx).toDp()
                    },
                    with(density) {
                        (heightPx).coerceIn(minimizedHeightPx, screenHeightPx).toDp()
                    }
                )
                .clip(RoundedCornerShape(lerp(0.dp, 12.dp, progress)))
                .then(if (progress < 0.5f) draggableModifier else pipDragModifier)
                .combinedClickable(
                    indication = null,
                    interactionSource = null,
                    onDoubleClick = {
                        isWidePip = !isWidePip
                    }
                ) {
                    if (!isExpanded) {
                        scope.launch { minimizeLayoutState.animateTo(MinimizeState.Expanded) }
                    }
                }
        ) {
            minimizableContent(Modifier)
        }
    }
}


/** simple lerp for floats */
private fun lerp(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

/** simple lerp for Dp */
private fun lerp(start: Dp, stop: Dp, fraction: Float): Dp {
    return (start.value + (stop.value - start.value) * fraction.coerceIn(0f, 1f)).dp
}


/**
 * The main layout, migrating from swipeable → anchoredDraggable
 */
@Composable
fun MinimizeLayout(
    minimizableContent: @Composable (draggableModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    minimizeLayoutState: MinimizeLayoutState = rememberMinimizeLayoutState(),
    minimizedContentHeight: Density.(Dp) -> Dp = { it / 10 },
    content: @Composable (bottomPaddingDp: Dp) -> Unit
) {
    val density = LocalDensity.current
    // Use updated lambda so recomposition is safe
    val minimizedHeightLambda by rememberUpdatedState(minimizedContentHeight)
    val scope = rememberCoroutineScope()
    val isExpanded by remember(minimizeLayoutState.currentValue) {
        derivedStateOf { minimizeLayoutState.currentValue == MinimizeState.Expanded }
    }
    BackHandler(isExpanded) {
        scope.launch {
            minimizeLayoutState.animateTo(MinimizeState.Minimized)
        }
    }

    MinimizeStack(
        modifier = modifier,
        hideOffsetFraction = 0f,  // for simplicity; you can manage a “hide” offset if needed
        minimizableContent = { constraints ->
            // We measure fullHeight in px
            val fullHeightPx = constraints.maxHeight.toFloat()
            // Convert minimized height from dp → px
            val minimizedPx = with(density) {
                minimizedHeightLambda(fullHeightPx.toDp()).toPx()
            }.coerceAtMost(fullHeightPx)

            // Define the anchors: map from offset → state
            // Note: offset is the draggable offset from top of expanded (0), so:
            val anchors = DraggableAnchors {
                MinimizeState.Expanded at 0f
                MinimizeState.Minimized at fullHeightPx - minimizedPx
            }

            // Update anchors in the state (so offset is valid)
            LaunchedEffect(fullHeightPx, minimizedPx) {
                minimizeLayoutState.updateAnchors(anchors)
            }

            // The draggable modifier
            val draggableModifier = Modifier.anchoredDraggable(
                state = minimizeLayoutState.anchoredState,
                orientation = Orientation.Vertical,
                reverseDirection = false
            )

            // Compute current height based on offset
            // offset = distance dragged from top. So height = fullHeight - offset
            val offsetPx = runCatching { minimizeLayoutState.offset }.getOrElse { fullHeightPx }
            val heightPx = (fullHeightPx - offsetPx).coerceIn(minimizedPx, fullHeightPx)

            Box(
                modifier = Modifier
                    .height(with(density) { heightPx.toDp() })
                    .clickable(
                        indication = null,
                        interactionSource = null
                    ) {
                        if (!isExpanded) {
                            scope.launch {
                                minimizeLayoutState.animateTo(MinimizeState.Expanded)
                            }
                        }
                    }
            ) {
                minimizableContent(draggableModifier)
            }
        },
        content = { bottomPadding ->
            Box(
                Modifier.fillMaxSize()
            ) {
                content(bottomPadding)
            }
        }
    )
}

@Composable
fun BottomMinimizeLayout(
    minimizableContent: @Composable (draggableModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    minimizeLayoutState: MinimizeLayoutState = rememberMinimizeLayoutState(),
    minimizedContentHeight: Density.(Dp) -> Dp = { it / 10 },
    content: @Composable (bottomPaddingDp: Dp) -> Unit
) {
    val density = LocalDensity.current
    // keep the lambda stable
    val minimizedHeightLambda by rememberUpdatedState(minimizedContentHeight)
    val scope = rememberCoroutineScope()
    val isExpanded by remember(minimizeLayoutState.currentValue) {
        derivedStateOf { minimizeLayoutState.currentValue == MinimizeState.Expanded }
    }
//    BackHandler(isExpanded) {
//        scope.launch {
//            minimizeLayoutState.animateTo(MinimizeState.Minimized)
//        }
//    }

    MinimizeStack(
        modifier = modifier,
        hideOffsetFraction = 0f,
        minimizableContent = { constraints ->
            // We measure fullHeight in px
            val fullHeightPx = constraints.maxHeight.toFloat()
//            val fullWidthPx = constraints.maxWidth.toFloat()
//            val baseToUse = max(fullHeightPx, fullWidthPx)
//            Log.d("MinimizeStack", "MinimizeLayout2: fullHeightPx: $fullHeightPx, fullWidthPx: $fullWidthPx, baseToUse: $baseToUse")
            // Convert minimized height from dp → px
            val minimizedPx = with(density) {
                minimizedHeightLambda(fullHeightPx.toDp()).toPx()
            }.coerceAtMost(fullHeightPx)
            val anchors = DraggableAnchors {
                MinimizeState.Expanded at 0f
                MinimizeState.Minimized at fullHeightPx - minimizedPx
            }

            // Update anchors in the state (so offset is valid)
            LaunchedEffect(fullHeightPx, minimizedPx) {
                minimizeLayoutState.updateAnchors(anchors)
            }
            // Apply both: our custom anchor modifier and anchoredDraggable
            val draggableModifier = Modifier.anchoredDraggable(
                state = minimizeLayoutState.anchoredState,
                orientation = Orientation.Vertical,
                reverseDirection = false
            )
            Box(
                modifier = Modifier
                    .minimizeAnchors(minimizeLayoutState, minimizedHeightLambda)
                    .clickable(
                        indication = null,
                        interactionSource = null
                    ) {
                        if (!isExpanded) {
                            scope.launch {
                                minimizeLayoutState.animateTo(MinimizeState.Expanded)
                            }
                        }
                    }
            ) {
                minimizableContent(draggableModifier)
            }
        },
        content = { bottomPadding ->
            Box(Modifier.fillMaxSize()) {
                content(bottomPadding)
            }
        }
    )
}

@Composable
private fun MinimizeStack(
    modifier: Modifier,
    hideOffsetFraction: Float,
    minimizableContent: @Composable (constraints: Constraints) -> Unit,
    content: @Composable (bottomPadding: Dp) -> Unit
) {

    val density = LocalDensity.current

    SubcomposeLayout(modifier) { constraints ->
        val minimizablePlaceable = subcompose(MinimizeStackSlot.Minimizable) {
            minimizableContent(constraints)
        }.first().measure(constraints.copy(minWidth = 0, minHeight = 0))

        val hideOffsetPx = (minimizablePlaceable.height * hideOffsetFraction).roundToInt()

        val contentPlaceable = subcompose(MinimizeStackSlot.Main) {
            content(with(density) {
                (minimizablePlaceable.height - hideOffsetPx).toDp()
            })
        }.first().measure(constraints)

        val middleX = (contentPlaceable.width - minimizablePlaceable.width) / 2
        val bottomY = (contentPlaceable.height - minimizablePlaceable.height) + hideOffsetPx

        layout(contentPlaceable.width, contentPlaceable.height) {
            contentPlaceable.placeRelative(0, 0)
            minimizablePlaceable.placeRelative(middleX, bottomY)
        }
    }

}

private enum class MinimizeStackSlot { Minimizable, Main }

/** A dummy “video player” box to simulate your player content */
@Composable
fun MinimizedTitleAndControls(
    videoTitle: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            videoTitle,
            style = TextStyle(
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )

        IconButton(onClick = { isPlaying = !isPlaying }) {
            if (isPlaying) {
                Icon(Icons.Default.Pause, contentDescription = "Pause")
            } else {
                Icon(Icons.Default.PlayArrow, contentDescription = "Play")
            }
        }

        IconButton(onClick = onDismiss) {
            Icon(Icons.Default.Close, contentDescription = "Close")
        }
    }
}

@Composable
fun UnifiedVideoPlayerLayout(
    videoPlayer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    minimizedContent: @Composable () -> Unit = {}, // optional
    expandProgress: Float,   // 1f = expanded, 0f = collapsed
    minimizeProgress: Float, // 1f = PiP, 0f = expanded
    pipMode: Boolean = false, // switch between PiP and side-by-side
    modifier: Modifier = Modifier
) {
    LaunchedEffect(minimizeProgress, expandProgress) {
        Log.d("UnifiedVideoPlayerLayout", "UnifiedVideoPlayerLayout: minimizeProgress: $minimizeProgress, expandProgress: $expandProgress")
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 16.dp,
        shadowElevation = 16.dp,
    ) {
        Layout(
            content = {
                videoPlayer()
                Column { content() }
                minimizedContent()
            },
            measurePolicy = { measurables, constraints ->
                val screenWidth = constraints.maxWidth
                val screenHeight = constraints.maxHeight
                if (pipMode) {
                    val videoPlaceable = measurables[0].measure(constraints)
                    val contentPlaceable = measurables[1].measure(constraints)
                    val minimizedPlaceable = measurables[2].measure(constraints)

                    val parentWidth = constraints.maxWidth
                    val parentHeight = constraints.maxHeight
                    val videoWidth = videoPlaceable.width
                    val videoHeight = videoPlaceable.height

                    // Define target minimized size (e.g., 40% of screen)
                    val pipWidth = (videoWidth * 1f).toInt().coerceAtMost(parentWidth)
                    val pipHeight = (videoHeight * 1f).toInt().coerceAtMost(parentHeight)

                    val pipX = parentWidth - pipWidth
                    val pipY = parentHeight - pipHeight

                    val hideThreshold = 0.55f

                    layout(parentWidth, parentHeight) {
                        // --- Smooth animated scaling during drag ---
                        val baseScale = lerp(1f, pipWidth.toFloat() / videoWidth, minimizeProgress)
                        val scale =
                            if (minimizeProgress > hideThreshold) 1f // keep fullscreen at end
                            else baseScale

                        val translationX = lerp(0f, pipX.toFloat(), minimizeProgress)
                        val translationY = lerp(0f, pipY.toFloat(), minimizeProgress)

                        // --- VIDEO ---
                        videoPlaceable.placeRelativeWithLayer(0, 0) {
                            scaleX = scale
                            scaleY = scale
                            this.translationX = translationX
                            this.translationY = translationY
                            transformOrigin = TransformOrigin(0f, 0f)
                        }

                        // --- EXPANDED CONTENT ---
                        contentPlaceable.placeRelativeWithLayer(0, videoPlaceable.height) {
                            val visibleAlpha = (expandProgress - minimizeProgress).coerceIn(0f, 1f)
                            alpha = if (expandProgress > minimizeProgress) visibleAlpha else 0f
                        }

                        // --- MINIMIZED OVERLAY ---
                        if (minimizeProgress > 0.5f) {
                            minimizedPlaceable.placeRelativeWithLayer(0, 0) {
                                alpha = minimizeProgress
                            }
                        }
                    }
                }


                /* if (pipMode) {
                     val videoPlaceable = measurables[0].measure(constraints)
                     val contentPlaceable = measurables[1].measure(constraints)
                     val minimizedPlaceable = measurables[2].measure(constraints)

                     val parentWidth = constraints.maxWidth
                     val parentHeight = constraints.maxHeight
                     val videoWidth = videoPlaceable.width
                     val videoHeight = videoPlaceable.height

 //                    val pipWidth = (videoWidth * 0.4f).toInt().coerceAtLeast(constraints.maxWidth)
 //                    val pipHeight = (videoHeight * 0.4f).toInt()
                     val pipWidth = parentWidth
                     val pipHeight = parentHeight

                     val pipX = (parentWidth - pipWidth)
                     val pipY = (parentHeight - pipHeight)

                     val scale = lerp(1f, pipWidth.toFloat() / videoWidth, minimizeProgress)
 //                    val translationX = lerp(0f, pipX.toFloat(), minimizeProgress)
 //                    val translationY = lerp(0f, pipY.toFloat(), minimizeProgress)

                     val hideThreshold = 0.9f

                     layout(parentWidth, parentHeight) {
                         // Video transformation
                         val finalScale =
                             if (minimizeProgress > hideThreshold) 1f else scale // Fullscreen in minimized mode
 //                        val finalTranslationX =
 //                            if (minimizeProgress > hideThreshold) 0f else translationX
 //                        val finalTranslationY =
 //                            if (minimizeProgress > hideThreshold) 0f else translationY

                         videoPlaceable.placeRelativeWithLayer(0, 0) {
                             scaleX = finalScale
                             scaleY = finalScale
 //                            this.translationX = finalTranslationX
 //                            this.translationY = finalTranslationY
                             transformOrigin = TransformOrigin(0f, 0f)
                         }

                         // Expanded content — visible only near expanded state
                         if (expandProgress > (1f - hideThreshold)) {
                             contentPlaceable.placeRelativeWithLayer(0, videoPlaceable.height) {
                                 alpha = expandProgress
                             }
                         }

                         // Minimized overlay — fullscreen overlay over video
                         if (minimizeProgress > hideThreshold) {
                             minimizedPlaceable.placeRelativeWithLayer(0, 0) {
                                 alpha = minimizeProgress
                             }
                         }
                     }
                 }*/


                else {
                    // ✅ Side-by-side behavior
                    val minimizableHeight = constraints.maxHeight

                    val videoPlayerPlaceable =
                        measurables[0].measure(constraints.copy(maxHeight = Constraints.Infinity))
                    val fullPagePlaceable = measurables[1].measure(constraints)

                    val videoPlayerScale =
                        (minimizableHeight.toFloat() / videoPlayerPlaceable.height.toFloat()).coerceAtMost(
                            1f
                        )
                    val videoPlayerScaledWidth =
                        (videoPlayerPlaceable.width * videoPlayerScale).toInt()

                    val minimizedPlaceable = measurables[2].measure(
                        constraints.copy(
                            maxHeight = minimizableHeight,
                            maxWidth = constraints.maxWidth - videoPlayerScaledWidth,
                            minWidth = 0
                        )
                    )

                    layout(constraints.maxWidth, constraints.maxHeight) {
                        videoPlayerPlaceable.placeRelativeWithLayer(0, 0) {
                            if (videoPlayerPlaceable.height > minimizableHeight) {
                                scaleX = videoPlayerScale
                                scaleY = videoPlayerScale
                                transformOrigin = TransformOrigin(0f, 0f)
                            }
                        }
                        if (videoPlayerPlaceable.height <= minimizableHeight) {
                            fullPagePlaceable.placeRelativeWithLayer(
                                0,
                                videoPlayerPlaceable.height
                            ) {
                                alpha = when {
                                    expandProgress > 0f -> expandProgress
                                    minimizeProgress > 0f -> 1f - minimizeProgress
                                    else -> 0f
                                }
                            }
                        } else {
                            minimizedPlaceable.placeRelative(videoPlayerScaledWidth, 0)
                        }
                    }
                }
            }
        )
    }
}


@Composable
fun PiPVideoPlayerPage(
    videoPlayer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    expandProgress: Float, // 1f = fully expanded, 0f = collapsed
    minimizeProgress: Float, // 1f = fully minimized (PiP), 0f = expanded
    pipSize: Dp = 250.dp,
    pipOffset: DpOffset = DpOffset(16.dp, 16.dp),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current

    Layout(
        content = {
            videoPlayer()
            Column { content() }
        },
        modifier = modifier.fillMaxSize(),
        measurePolicy = { measurables, constraints ->
            val videoPlaceable = measurables[0].measure(
                constraints.copy(minWidth = constraints.maxWidth, minHeight = constraints.maxHeight)
            )
            val contentPlaceable = measurables[1].measure(constraints)

            layout(constraints.maxWidth, constraints.maxHeight) {
                val screenWidth = constraints.maxWidth
                val screenHeight = constraints.maxHeight

                // PiP size in px
                val pipSizePx = with(density) { pipSize.toPx() }
                val pipOffsetXPx = with(density) { pipOffset.x.toPx() }
                val pipOffsetYPx = with(density) { pipOffset.y.toPx() }

                // Target PiP coordinates (bottom-end)
                val pipX = screenWidth - pipSizePx - pipOffsetXPx
                val pipY = screenHeight - pipSizePx - pipOffsetYPx

                // Interpolate between full screen and PiP
                val scale = lerp(1f, pipSizePx / videoPlaceable.width.toFloat(), minimizeProgress)
                var translationX = lerp(0f, pipX, minimizeProgress)
                var translationY = lerp(0f, pipY, minimizeProgress)

                // Place single video instance
                videoPlaceable.placeRelativeWithLayer(0, 0) {
                    scaleX = scale
                    scaleY = scale
                    translationX = translationX
                    translationY = translationY
                    transformOrigin = TransformOrigin(0f, 0f)
                }

                // Content fades out as PiP takes over
                contentPlaceable.placeRelativeWithLayer(0, videoPlaceable.height) {
                    alpha = 1f - minimizeProgress
                }
            }
        }
    )
}


@Composable
fun VideoPlayerPage(
    videoPlayer: @Composable () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
    minimizedContent: @Composable () -> Unit,
    expandProgress: Float,
    minimizeProgress: Float,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 16.dp,
        shadowElevation = 16.dp,
    ) {
        Layout(
            content = {
                videoPlayer()
                Column {
                    content()
                }
                minimizedContent()
            },
            measurePolicy = { measurables, constraints ->
                val minimizableHeight = constraints.maxHeight

                val videoPlayerPlaceable =
                    measurables[0].measure(constraints.copy(maxHeight = Constraints.Infinity))
                val fullPagePlaceable = measurables[1].measure(constraints)

                val videoPlayerScale =
                    (minimizableHeight.toFloat() / videoPlayerPlaceable.height.toFloat()).coerceAtMost(
                        1f
                    )
                val videoPlayerScaledWidth =
                    (videoPlayerPlaceable.width * videoPlayerScale).toInt()

                val minimizedPlaceable = measurables[2].measure(
                    constraints.copy(
                        maxHeight = minimizableHeight,
                        maxWidth = constraints.maxWidth - videoPlayerScaledWidth,
                        minWidth = 0
                    )
                )

                layout(constraints.maxWidth, constraints.maxHeight) {
                    videoPlayerPlaceable.placeRelativeWithLayer(0, 0) {
                        if (videoPlayerPlaceable.height > minimizableHeight) {
                            scaleX = videoPlayerScale
                            scaleY = videoPlayerScale
                            transformOrigin = TransformOrigin(0f, 0f)
                        }
                    }
                    if (videoPlayerPlaceable.height <= minimizableHeight) {
                        fullPagePlaceable.placeRelativeWithLayer(0, videoPlayerPlaceable.height) {
                            alpha = when {
                                expandProgress > 0f -> expandProgress
                                minimizeProgress > 0f -> 1f - minimizeProgress
                                else -> 0f
                            }
                        }
                    } else {
                        minimizedPlaceable.placeRelative(videoPlayerScaledWidth, 0)
                    }
                }
            }
        )
    }
}

@Composable
fun HomeScreen(
    selectedType: MinimizeSceneType,
    onSelect: (MinimizeSceneType) -> Unit,
    onPlay: (PlayerKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val options = listOf("Bottom", "Floating")
    LazyColumn(
        modifier = modifier.fillMaxSize()
    ) {
        stickyHeader {
            SingleChoiceSegmentedButtonRow {
                options.forEachIndexed { index, label ->
                    val isSelected by remember(selectedType) {
                        derivedStateOf {
                            if (selectedType is MinimizeSceneType.Bottom) options[0] == label
                            else options[1] == label

                        }
                    }
                    SegmentedButton(
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = options.size),
                        onClick = {
                            if (label == "Bottom")
                                onSelect(MinimizeSceneType.Bottom({}))
                            else
                                onSelect(MinimizeSceneType.Floating({}))
                        },
                        selected = isSelected,
                    ) {
                        Text(
                            text = label
                        )
                    }
                }
            }
        }
        items(10) { index ->
            val videoId = index + 1
            val playerRoute = PlayerKey(videoId)
            val backgroundColor = playerRoute.color

            ListItem(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = { onPlay(playerRoute) }),
                headlineContent = {
                    Text(
                        text = "Video: $videoId",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                },
                colors = ListItemDefaults.colors(
                    containerColor = backgroundColor // Set container color directly
                )
            )
        }
    }

}

