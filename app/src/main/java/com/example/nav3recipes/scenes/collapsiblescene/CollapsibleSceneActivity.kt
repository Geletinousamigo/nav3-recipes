package com.example.nav3recipes.scenes.collapsiblescene

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.currentRecomposeScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import com.example.nav3recipes.ui.setEdgeToEdgeConfig
import com.example.nav3recipes.ui.theme.colors
import kotlinx.serialization.Serializable

@Serializable
object HomeKey : NavKey

@Serializable
data class PlayerKey(
    val id: Int,
) : NavKey {
    val color: Color
        get() = colors[id % colors.size]
}

@Composable
fun logRecompose(tag: String) {
    val scope = currentRecomposeScope
    Log.d("RecomposeTracker", "$tag recomposed @${scope.hashCode()}")
}

class CollapsibleSceneActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setEdgeToEdgeConfig()
        super.onCreate(savedInstanceState)
        setContent {
            var minimizableSceneType by remember {
                mutableStateOf<MinimizeSceneType>(
                    MinimizeSceneType.Bottom({})
                )
            }
            val minimizeLayoutState = rememberMinimizeLayoutState(
                initialValue = MinimizeState.Expanded
            )
            val collapsibleSceneStrategy =
                rememberMinimizeSceneStrategy<NavKey>(minimizeLayoutState, minimizableSceneType)

            val backStack = rememberNavBackStack(
                HomeKey   // starting screen
            )

            LaunchedEffect(backStack.size) {
                Log.d("CollapsibleSceneActivity", "onCreate: currentBackStack=${backStack.joinToString(prefix = "[", separator = ", ", postfix = "]")}")
            }

            Scaffold { innerPadding ->
                NavDisplay(
                    modifier = Modifier.padding(innerPadding),
                    backStack = backStack,
                    onBack = { backStack.removeLastOrNull() },
                    entryProvider = entryProvider {
                        // Home screen (A screen)
                        entry<HomeKey>(
                            metadata = MinimizeSceneStrategy.mainPane()
                            + NavDisplay.transitionSpec {
                                EnterTransition.None togetherWith ExitTransition.None
                            } + NavDisplay.popTransitionSpec {
                                EnterTransition.None togetherWith ExitTransition.None
                            } + NavDisplay.predictivePopTransitionSpec {
                                EnterTransition.None togetherWith ExitTransition.None
                            }
                        ) {
                            HomeScreen(
                                selectedType = minimizableSceneType,
                                onSelect = { newType ->
                                    backStack.clearPlayerRoute()
                                    minimizableSceneType = newType
                                },
                                onPlay = { videoId ->
                                    // Push the player on top (B screen)
                                    backStack.addVideoRoute(videoId)
                                }
                            )
                        }

                        // Player screen (B screen)
                        entry<PlayerKey>(
                            clazzContentKey = { "playerKey" }, // Passing constant Key, since PlayerKey changes with different id
                            // mark this entry as minimizable
                            metadata = MinimizeSceneStrategy.minimizablePane(type = minimizableSceneType)
                                    + NavDisplay.transitionSpec {
                                EnterTransition.None togetherWith ExitTransition.None
                            } + NavDisplay.popTransitionSpec {
                                EnterTransition.None togetherWith ExitTransition.None
                            } + NavDisplay.predictivePopTransitionSpec {
                                EnterTransition.None togetherWith ExitTransition.None
                            }
                        ) { playerKey ->
                            LaunchedEffect(playerKey) {
                                Log.d("PlayerKey", "onCreate: player key changed")
                                minimizeLayoutState.animateTo(MinimizeState.Expanded)
                            }
                            val title = "Title for video id:${playerKey.id}"
                            UnifiedVideoPlayerLayout(
                                modifier = Modifier,
                                pipMode = minimizableSceneType.isFloating(),
                                videoPlayer = {
                                    // Actual Player
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(250.dp)
                                            .background(Color.Black),
                                    )
                                },
                                content = {
                                    // Expanded Content
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(playerKey.color)
                                    ) {
                                        Text(
                                            title,
                                            style = TextStyle(
                                                fontSize = 22.sp,
                                                fontWeight = FontWeight.Bold
                                            ),
                                            modifier = Modifier
                                                .padding(16.dp)
                                        )
                                        Text(
                                            "Common Description",
                                            style = TextStyle(
                                                fontSize = 14.sp
                                            ),
                                            modifier = Modifier.padding(horizontal = 16.dp)
                                        )
                                    }
                                },
                                minimizedContent = {
//                                    MinimizedTitleAndControls(
//                                        modifier = Modifier.fillMaxSize().background(playerKey.color),
//                                        videoTitle = title,
//                                        onDismiss = { backStack.removeLastOrNull() },
//                                    )
                                    var isPlaying by remember { mutableStateOf(false) }
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Transparent)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .background(Color.DarkGray)
                                                .align(Alignment.BottomCenter)
                                                .height(48.dp)
                                                .fillMaxWidth()
                                                .padding(8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            IconButton(onClick = { isPlaying = !isPlaying }) {
                                                if (isPlaying) {
                                                    Icon(
                                                        imageVector = Icons.Default.Pause,
                                                        contentDescription = "Pause",
                                                        tint = Color.White
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.Default.PlayArrow,
                                                        contentDescription = "Play",
                                                        tint = Color.White
                                                    )
                                                }
                                            }

                                        }


                                        IconButton(
                                            modifier = Modifier.align(Alignment.TopEnd),
                                            onClick = { backStack.removeLastOrNull() }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Close,
                                                contentDescription = "Close",
                                                tint = Color.White
                                            )
                                        }

                                    }
                                },
                                expandProgress = minimizeLayoutState.expandProgress,
                                minimizeProgress = minimizeLayoutState.minimizeProgress,
                            )
                        }
                    },
                    // scene strategies: Minimize first, fallback to SinglePane
                    sceneStrategy = collapsibleSceneStrategy
                )
            }
        }
    }

    private fun NavBackStack<NavKey>.addVideoRoute(playerRoute: PlayerKey) {

        // Remove the player route if it exists
        if (last() is PlayerKey) {
            removeLastOrNull()
        }

        // Avoid adding the same video route to the back stack twice.
        if (!contains(playerRoute)) {

            add(playerRoute)
        }
    }

    private fun NavBackStack<NavKey>.clearPlayerRoute() {
        if (last() is PlayerKey) {
            removeLastOrNull()
        }
    }
}
