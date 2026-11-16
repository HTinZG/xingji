package com.llyytt.path

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.llyytt.path.feature.ai.AiChatScreen
import com.llyytt.path.feature.offline.OfflineInfoScreen
import com.llyytt.path.feature.privacy.PrivacyGateScreen
import com.llyytt.path.feature.privacy.PrivacyPolicyScreen
import com.llyytt.path.feature.record.RecordScreen
import com.llyytt.path.feature.settings.SettingsScreen
import com.llyytt.path.feature.trips.SegmentEditorScreen
import com.llyytt.path.feature.trips.TripDetailScreen
import com.llyytt.path.feature.trips.TripListScreen

private sealed interface Screen {
    data object Record : Screen
    data object Trips : Screen
    data object Settings : Screen
    data object Offline : Screen
    data object Privacy : Screen
    data object Ai : Screen
    data class Detail(val tripId: Long) : Screen
    data class Segments(val tripId: Long) : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as XjApp

        setContent {
            MaterialTheme {
                var accepted by remember { mutableStateOf(app.isPrivacyAccepted) }
                var screen by remember { mutableStateOf<Screen>(Screen.Record) }

                BackHandler(enabled = accepted && screen != Screen.Record) {
                    screen = when (val current = screen) {
                        is Screen.Detail -> Screen.Trips
                        is Screen.Segments -> Screen.Detail(current.tripId)
                        is Screen.Offline -> Screen.Settings
                        is Screen.Privacy -> Screen.Settings
                        is Screen.Ai -> Screen.Record
                        else -> Screen.Record
                    }
                }

                if (!accepted) {
                    PrivacyGateScreen(
                        onAgree = {
                            app.acceptPrivacy()
                            accepted = true
                        },
                        onDecline = { finish() },
                    )
                    return@MaterialTheme
                }

                when (val current = screen) {
                    Screen.Record -> RecordScreen(
                        onOpenTrips = { screen = Screen.Trips },
                        onOpenSettings = { screen = Screen.Settings },
                        onOpenAi = { screen = Screen.Ai },
                    )
                    Screen.Trips -> TripListScreen(
                        onOpen = { screen = Screen.Detail(it) },
                        onBack = { screen = Screen.Record },
                    )
                    Screen.Settings -> SettingsScreen(
                        onBack = { screen = Screen.Record },
                        onOpenOfflineInfo = { screen = Screen.Offline },
                        onOpenPrivacy = { screen = Screen.Privacy },
                    )
                    Screen.Privacy -> PrivacyPolicyScreen(
                        onBack = { screen = Screen.Settings },
                    )
                    Screen.Offline -> OfflineInfoScreen(
                        onBack = { screen = Screen.Settings },
                    )
                    Screen.Ai -> AiChatScreen(
                        onBack = { screen = Screen.Record },
                        onOpenSettings = { screen = Screen.Settings },
                    )
                    is Screen.Detail -> TripDetailScreen(
                        tripId = current.tripId,
                        onBack = { screen = Screen.Trips },
                        onOpenSegments = { screen = Screen.Segments(current.tripId) },
                    )
                    is Screen.Segments -> SegmentEditorScreen(
                        tripId = current.tripId,
                        onBack = { screen = Screen.Detail(current.tripId) },
                    )
                }
            }
        }
    }
}
