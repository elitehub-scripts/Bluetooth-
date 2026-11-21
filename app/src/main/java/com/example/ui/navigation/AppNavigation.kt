package com.example.ui.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Contacts
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.features.contacts.ContactsScreen
import com.example.features.files.FileSharingScreen
import com.example.features.home.HomeScreen
import com.example.features.messages.ChatDetailScreen
import com.example.features.messages.MessagesScreen
import com.example.features.nearby.NearbyScreen
import com.example.features.network.NetworkScreen
import com.example.features.settings.SettingsScreen
import com.example.ui.viewmodel.MainViewModel
import kotlinx.serialization.Serializable

@Serializable object HomeRoute
@Serializable object NearbyRoute
@Serializable object MessagesRoute
@Serializable data class ChatDetailRoute(val conversationId: String, val peerDeviceId: String)
@Serializable object FileSharingRoute
@Serializable object NetworkRoute
@Serializable object ContactsRoute
@Serializable object SettingsRoute

private data class NavDestinationItem(
    val route: Any,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
    val testTag: String
)

private val primaryDestinations = listOf(
    NavDestinationItem(HomeRoute, "Home", Icons.Filled.Home, Icons.Outlined.Home, "nav_home"),
    NavDestinationItem(NearbyRoute, "Nearby", Icons.Filled.Radar, Icons.Outlined.Radar, "nav_nearby"),
    NavDestinationItem(MessagesRoute, "Messages", Icons.AutoMirrored.Filled.Chat, Icons.AutoMirrored.Outlined.Chat, "nav_messages"),
    NavDestinationItem(FileSharingRoute, "Files", Icons.Filled.FolderZip, Icons.Outlined.FolderZip, "nav_files"),
    NavDestinationItem(NetworkRoute, "Network", Icons.Filled.Hub, Icons.Outlined.Hub, "nav_network")
)

private val railAllDestinations = primaryDestinations + listOf(
    NavDestinationItem(ContactsRoute, "Contacts", Icons.Filled.Contacts, Icons.Outlined.Contacts, "nav_contacts"),
    NavDestinationItem(SettingsRoute, "Settings", Icons.Filled.Settings, Icons.Outlined.Settings, "nav_settings")
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BluetoothGlobalShareShell(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentDest = backStackEntry?.destination
    val snackbarHostState = remember { SnackbarHostState() }
    val feedback by viewModel.uiFeedbackMessage.collectAsStateWithLifecycle()

    LaunchedEffect(feedback) {
        val msg = feedback
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.clearFeedback()
        }
    }

    val isChatDetail = currentDest?.hasRoute(ChatDetailRoute::class) == true

    fun navigateTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) {
                saveState = true
            }
            launchSingleTop = true
            restoreState = true
        }
    }

    val currentTitle = when {
        currentDest?.hasRoute(NearbyRoute::class) == true -> "Nearby Bluetooth"
        currentDest?.hasRoute(MessagesRoute::class) == true -> "Encrypted Messages"
        currentDest?.hasRoute(FileSharingRoute::class) == true -> "Offline File Sharing"
        currentDest?.hasRoute(NetworkRoute::class) == true -> "Relay Network Graph"
        currentDest?.hasRoute(ContactsRoute::class) == true -> "Contacts & QR Keys"
        currentDest?.hasRoute(SettingsRoute::class) == true -> "Settings & Security"
        else -> "Bluetooth Global Share"
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 840.dp

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                if (!isChatDetail) {
                    TopAppBar(
                        title = {
                            Text(
                                text = currentTitle,
                                style = MaterialTheme.typography.titleLarge
                            )
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.background
                        ),
                        actions = {
                            if (!useRail) {
                                IconButton(
                                    onClick = { navigateTopLevel(ContactsRoute) },
                                    modifier = Modifier.testTag("topbar_contacts_button")
                                ) {
                                    Icon(
                                        imageVector = if (currentDest?.hasRoute(ContactsRoute::class) == true) {
                                            Icons.Filled.Contacts
                                        } else {
                                            Icons.Outlined.Contacts
                                        },
                                        contentDescription = "Contacts"
                                    )
                                }
                                IconButton(
                                    onClick = { navigateTopLevel(SettingsRoute) },
                                    modifier = Modifier.testTag("topbar_settings_button")
                                ) {
                                    Icon(
                                        imageVector = if (currentDest?.hasRoute(SettingsRoute::class) == true) {
                                            Icons.Filled.Settings
                                        } else {
                                            Icons.Outlined.Settings
                                        },
                                        contentDescription = "Settings"
                                    )
                                }
                            }
                        }
                    )
                }
            },
            bottomBar = {
                if (!useRail && !isChatDetail) {
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        primaryDestinations.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            NavigationBarItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail && !isChatDetail) {
                    NavigationRail(
                        containerColor = MaterialTheme.colorScheme.surface
                    ) {
                        railAllDestinations.forEach { item ->
                            val selected = currentDest?.hasRoute(item.route::class) == true
                            NavigationRailItem(
                                selected = selected,
                                onClick = { navigateTopLevel(item.route) },
                                icon = {
                                    Icon(
                                        imageVector = if (selected) item.selectedIcon else item.unselectedIcon,
                                        contentDescription = item.label
                                    )
                                },
                                label = { Text(item.label) },
                                modifier = Modifier.testTag(item.testTag)
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    NavHost(
                        navController = navController,
                        startDestination = HomeRoute
                    ) {
                        composable<HomeRoute> {
                            HomeScreen(
                                viewModel = viewModel,
                                onNavigateNearby = { navigateTopLevel(NearbyRoute) },
                                onNavigateMessages = { navigateTopLevel(MessagesRoute) },
                                onNavigateFiles = { navigateTopLevel(FileSharingRoute) },
                                onNavigateNetwork = { navigateTopLevel(NetworkRoute) },
                                onNavigateContacts = { navigateTopLevel(ContactsRoute) },
                                onNavigateSettings = { navigateTopLevel(SettingsRoute) }
                            )
                        }
                        composable<NearbyRoute> {
                            NearbyScreen(viewModel = viewModel)
                        }
                        composable<MessagesRoute> {
                            MessagesScreen(
                                viewModel = viewModel,
                                onOpenConversation = { convId, peerId ->
                                    navController.navigate(ChatDetailRoute(convId, peerId))
                                },
                                onNavigateContacts = { navigateTopLevel(ContactsRoute) }
                            )
                        }
                        composable<ChatDetailRoute> { entry ->
                            val route = entry.toRoute<ChatDetailRoute>()
                            ChatDetailScreen(
                                conversationId = route.conversationId,
                                peerDeviceId = route.peerDeviceId,
                                viewModel = viewModel,
                                onBack = { navController.popBackStack() }
                            )
                        }
                        composable<FileSharingRoute> {
                            FileSharingScreen(viewModel = viewModel)
                        }
                        composable<NetworkRoute> {
                            NetworkScreen(viewModel = viewModel)
                        }
                        composable<ContactsRoute> {
                            ContactsScreen(
                                viewModel = viewModel,
                                onOpenConversation = { convId, peerId ->
                                    navController.navigate(ChatDetailRoute(convId, peerId))
                                }
                            )
                        }
                        composable<SettingsRoute> {
                            SettingsScreen(viewModel = viewModel)
                        }
                    }
                }
            }
        }
    }
}
