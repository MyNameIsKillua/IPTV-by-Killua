package dev.killua.iptv.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.killua.iptv.AppContainer
import dev.killua.iptv.core.player.PlaybackRequest
import dev.killua.iptv.core.preferences.PlaybackGestureOptions
import dev.killua.iptv.domain.model.Account
import dev.killua.iptv.domain.model.LiveChannel
import dev.killua.iptv.domain.model.ResumableKind
import dev.killua.iptv.domain.model.SessionState
import dev.killua.iptv.domain.model.SubtitleStyle
import dev.killua.iptv.domain.userdata.UserDataExportCodec
import dev.killua.iptv.domain.model.ThemeMode
import dev.killua.iptv.domain.model.VideoScaleMode
import dev.killua.iptv.domain.model.WatchlistKind
import dev.killua.iptv.feature.auth.LoginRoute
import dev.killua.iptv.feature.auth.LoginViewModel
import dev.killua.iptv.feature.auth.ReconnectRoute
import dev.killua.iptv.feature.auth.ReconnectViewModel
import dev.killua.iptv.feature.guide.GuideRoute
import dev.killua.iptv.feature.guide.GuideViewModel
import dev.killua.iptv.feature.home.HomeRoute
import dev.killua.iptv.feature.home.HomeViewModel
import dev.killua.iptv.feature.live.LiveRoute
import dev.killua.iptv.feature.live.LiveViewModel
import dev.killua.iptv.feature.movies.MovieDetailsRoute
import dev.killua.iptv.feature.movies.MovieDetailsViewModel
import dev.killua.iptv.feature.movies.MoviesRoute
import dev.killua.iptv.feature.movies.MoviesViewModel
import dev.killua.iptv.feature.onboarding.IntroductionOverlay
import dev.killua.iptv.feature.player.PlayerRoute
import dev.killua.iptv.feature.player.PlayerRouteWindow
import dev.killua.iptv.feature.player.PlayerViewModel
import dev.killua.iptv.feature.search.SearchRoute
import dev.killua.iptv.feature.search.SearchViewModel
import dev.killua.iptv.feature.series.SeriesDetailsRoute
import dev.killua.iptv.feature.series.SeriesDetailsViewModel
import dev.killua.iptv.feature.series.SeriesRoute
import dev.killua.iptv.feature.series.SeriesViewModel
import dev.killua.iptv.feature.settings.SettingsRoute
import dev.killua.iptv.feature.settings.SettingsViewModel
import dev.killua.iptv.feature.sync.InitialSyncRoute
import dev.killua.iptv.feature.sync.InitialSyncViewModel
import dev.killua.iptv.ui.theme.KilluasIptvTheme
import dev.killua.iptv.ui.update.UpdatePrompt
import kotlinx.coroutines.launch
import dev.killua.iptv.ui.theme.TelevisionOverscan
import dev.killua.iptv.ui.theme.LocalIsTelevision

@Composable
fun IptvApp(
    container: AppContainer,
    isInPictureInPictureMode: Boolean,
) {
    val sessionState by container.sessionRepository.state.collectAsStateWithLifecycle()
    val themeMode by container.preferences.themeMode.collectAsStateWithLifecycle(initialValue = ThemeMode.Dark)
    LaunchedEffect(Unit) { container.sessionRepository.start() }
    LaunchedEffect(sessionState is SessionState.Authenticated) {
        if (sessionState !is SessionState.Authenticated) {
            container.playbackCoordinator.stopAndClear()
            container.playerPresentationState.clear()
        }
    }

    // Authentication has a deliberately dark, cinematic surface. Force its complete Material
    // palette to dark so a previously saved Light/System setting cannot create dark-on-dark fields.
    val effectiveThemeMode = when (sessionState) {
        SessionState.Booting,
        SessionState.SignedOut,
        -> ThemeMode.Dark
        else -> themeMode
    }
    KilluasIptvTheme(effectiveThemeMode) {
        when (val session = sessionState) {
            SessionState.Booting -> LoadingScreen()
            SessionState.SignedOut -> LoginRoot(container)
            is SessionState.ReconnectRequired -> ReconnectRoot(container, session)
            is SessionState.Authenticated -> AuthenticatedRoot(
                container = container,
                session = session,
                isInPictureInPictureMode = isInPictureInPictureMode,
            )
        }
        // Over the app rather than inside a screen, so it survives navigation - and only once the
        // viewer is actually in the app. Interrupting a sign-in, or a video already in
        // Picture-in-Picture, to talk about a new version would be the wrong moment for it.
        if (sessionState is SessionState.Authenticated && !isInPictureInPictureMode) {
            UpdatePrompt(container)
        }
    }
}

/**
 * Shows the one-time library sync before the app when nothing is cached yet.
 *
 * A large provider needs well over a minute for its first sync. Doing that lazily when a tab was
 * first opened left the user on an empty screen with no explanation, so the wait is made explicit
 * once and the tabs are instant afterwards. An existing cache skips this entirely.
 */
@Composable
private fun AuthenticatedRoot(
    container: AppContainer,
    session: SessionState.Authenticated,
    isInPictureInPictureMode: Boolean,
) {
    var syncState by rememberSaveable(session.account.id) {
        mutableStateOf(InitialSyncGate.Undecided)
    }
    LaunchedEffect(session.account.id) {
        if (syncState != InitialSyncGate.Undecided) return@LaunchedEffect
        val cached = runCatching {
            container.liveRepository.hasCachedLibrary(session.account.id) &&
                container.movieRepository.hasCachedLibrary(session.account.id) &&
                container.seriesRepository.hasCachedLibrary(session.account.id)
        }.getOrDefault(false)
        syncState = if (cached) InitialSyncGate.Ready else InitialSyncGate.Syncing
    }

    when (syncState) {
        // Briefly indeterminate while the cache check runs; it is a single indexed count.
        InitialSyncGate.Undecided -> LoadingScreen()
        InitialSyncGate.Syncing -> {
            val syncVm: InitialSyncViewModel = viewModel(
                key = "sync-${session.account.id}",
                factory = SimpleViewModelFactory {
                    InitialSyncViewModel(
                        account = session.account,
                        liveRepository = container.liveRepository,
                        movieRepository = container.movieRepository,
                        seriesRepository = container.seriesRepository,
                    )
                },
            )
            InitialSyncRoute(syncVm, onFinished = { syncState = InitialSyncGate.Ready })
        }
        InitialSyncGate.Ready -> {
            val introductionSeen by container.preferences.introductionSeen
                .collectAsStateWithLifecycle(initialValue = true)
            val scope = rememberCoroutineScope()
            MainRoot(
                container = container,
                session = session,
                isInPictureInPictureMode = isInPictureInPictureMode,
            )
            // Drawn over the app rather than in front of it: the tour describes things that are
            // already there, and dismissing it should reveal them rather than load them.
            // Defaulting to "seen" while the preference is still being read keeps it from
            // flashing up for a fraction of a second on every launch.
            if (!introductionSeen && !isInPictureInPictureMode) {
                IntroductionOverlay(
                    onFinish = {
                        scope.launch { container.preferences.setIntroductionSeen(true) }
                    },
                )
            }
        }
    }
}

private enum class InitialSyncGate { Undecided, Syncing, Ready }

@Composable
private fun ReconnectRoot(container: AppContainer, session: SessionState.ReconnectRequired) {
    val viewModel: ReconnectViewModel = viewModel(
        key = "reconnect-${session.account?.id.orEmpty()}-${session.reason.kind}",
        factory = SimpleViewModelFactory { ReconnectViewModel(session, container.sessionRepository) },
    )
    ReconnectRoute(viewModel)
}

@Composable
private fun LoadingScreen() {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
        Text(
            text = "Developed by MyNameIsKillua",
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 20.dp),
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.72f),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LoginRoot(container: AppContainer) {
    val factory = SimpleViewModelFactory { LoginViewModel(container.sessionRepository) }
    val viewModel: LoginViewModel = viewModel(key = "login", factory = factory)
    LoginRoute(viewModel)
}

private enum class MainDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    Home("home", "Home", Icons.Default.Home),
    Live("live", "Live", Icons.Default.LiveTv),
    Movies("movies", "Movies", Icons.Default.Movie),
    Series("series", "Series", Icons.Default.VideoLibrary),
    Search("search", "Search", Icons.Default.Search),
    ;

    companion object {
        /**
         * The destinations this account has.
         *
         * A playlist loses **Movies** and **Series**, because an M3U has neither and a tab that can
         * only ever be empty reads as a library that failed to load. **Search** stays: it searches
         * what is cached, and a playlist's channels are cached like any others.
         */
        fun forAccount(playlist: Boolean): List<MainDestination> =
            if (playlist) entries.filterNot { it == Movies || it == Series } else entries
    }
}

private const val SETTINGS_ROUTE = "settings"
private const val GUIDE_ROUTE = "guide"
private const val PLAYER_ROUTE = "player"

/**
 * One player route serves both content types. The kind is part of the path rather than a second
 * route so the player screen, its ViewModel key, and Picture-in-Picture handling stay single.
 * Only credential-free identifiers travel here.
 */
private const val PLAYER_KIND_LIVE = "live"
private const val PLAYER_KIND_MOVIE = "movie"
private const val PLAYER_KIND_EPISODE = "episode"
private const val PLAYER_ROUTE_PATTERN = "$PLAYER_ROUTE/{kind}/{contentId}?restart={restart}"
private const val MOVIE_DETAILS_ROUTE = "movie"
private const val MOVIE_DETAILS_ROUTE_PATTERN = "$MOVIE_DETAILS_ROUTE/{movieId}"
private const val SERIES_DETAILS_ROUTE = "series-details"
private const val SERIES_DETAILS_ROUTE_PATTERN = "$SERIES_DETAILS_ROUTE/{seriesId}"

@Composable
private fun MainRoot(
    container: AppContainer,
    session: SessionState.Authenticated,
    isInPictureInPictureMode: Boolean,
) {
    val navController = rememberNavController()
    val doubleTapSeekSeconds by container.preferences.doubleTapSeekSeconds
        .collectAsStateWithLifecycle(initialValue = PlaybackGestureOptions.defaultSeekSeconds)
    val holdPlaybackSpeed by container.preferences.holdPlaybackSpeed
        .collectAsStateWithLifecycle(
            initialValue = PlaybackGestureOptions.defaultHoldSpeedHundredths / 100f,
        )
    val autoPlayNextEpisode by container.preferences.autoPlayNextEpisode
        .collectAsStateWithLifecycle(initialValue = true)
    val videoScaleMode by container.preferences.videoScaleMode
        .collectAsStateWithLifecycle(initialValue = VideoScaleMode.Fit)
    val rememberedBrightness by container.preferences.playerBrightness
        .collectAsStateWithLifecycle(initialValue = null)
    val subtitleStyle by container.preferences.subtitleStyle
        .collectAsStateWithLifecycle(initialValue = SubtitleStyle())
    val scope = rememberCoroutineScope()
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val playerVisible = currentRoute == PLAYER_ROUTE_PATTERN
    val showBottomBar = currentRoute in MainDestination.entries.map(MainDestination::route)

    // Immersive bars and the brightness override follow the route, not the individual player
    // screen. An episode change replaces that screen and the outgoing one is torn down after the
    // incoming one is running, so owning them per screen meant each switch handed both back.
    PlayerRouteWindow(
        playerVisible = playerVisible,
        isInPictureInPictureMode = isInPictureInPictureMode,
        rememberedBrightness = rememberedBrightness,
    )

    Scaffold(
        bottomBar = {
            if (showBottomBar && !isInPictureInPictureMode) {
                MainBottomBar(navController, playlistAccount = session.account.isPlaylist)
            }
        },
    ) { padding ->
        /*
         * The margin a television is likely to crop, applied once at the root.
         *
         * Not while the player is on screen: a film is meant to fill the panel, and insetting the
         * picture would put black bars inside the black bars a television already adds. It is the
         * *browsing* that must stay inside the safe area - a row whose first tile is off the left
         * edge is a row a viewer cannot reach with a remote, and nothing on screen says why.
         */
        val overscan = if (LocalIsTelevision.current && !playerVisible) {
            Modifier.padding(TelevisionOverscan)
        } else {
            Modifier
        }
        NavHost(
            navController = navController,
            startDestination = MainDestination.Home.route,
            modifier = Modifier
                .fillMaxSize()
                .then(if (playerVisible) Modifier else Modifier.padding(padding))
                .then(overscan),
        ) {
            composable(MainDestination.Home.route) {
                val homeVm: HomeViewModel = viewModel(
                    key = "home-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        HomeViewModel(
                            account = session.account,
                            liveRepository = container.liveRepository,
                            movieRepository = container.movieRepository,
                            seriesRepository = container.seriesRepository,
                            watchlistRepository = container.watchlistRepository,
                        )
                    },
                )
                androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
                    HomeRoute(
                        viewModel = homeVm,
                        account = session.account,
                        validationWarning = session.validationWarning,
                        onBrowseLive = { navController.navigate(MainDestination.Live.route) },
                        onPlay = { playChannel(navController, it) },
                        onOpenEntry = { entry ->
                            when (entry.kind) {
                                ResumableKind.Movie -> openMovie(navController, entry.contentId)
                                ResumableKind.Series -> openSeries(navController, entry.contentId)
                            }
                        },
                        onOpenSaved = { entry ->
                            when (entry.kind) {
                                WatchlistKind.Movie -> openMovie(navController, entry.contentId)
                                WatchlistKind.Series -> openSeries(navController, entry.contentId)
                                // A channel has no details screen; the saved list starts it.
                                WatchlistKind.Channel ->
                                    playChannelById(navController, entry.contentId)
                            }
                        },
                    )
                    SettingsButton(
                        modifier = Modifier.align(Alignment.TopEnd),
                        onClick = { navController.navigate(SETTINGS_ROUTE) },
                    )
                }
            }
            composable(MainDestination.Live.route) {
                val liveVm: LiveViewModel = viewModel(
                    key = "live-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        LiveViewModel(
                            session.account,
                            container.liveRepository,
                            container.watchlistRepository,
                        )
                    },
                )
                LiveRoute(
                    viewModel = liveVm,
                    onPlay = { playChannel(navController, it) },
                    onToggleSaved = liveVm::toggleSaved,
                    onOpenGuide = { navController.navigate(GUIDE_ROUTE) { launchSingleTop = true } },
                )
            }
            composable(GUIDE_ROUTE) {
                val guideVm: GuideViewModel = viewModel(
                    key = "guide-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        GuideViewModel(
                            account = session.account,
                            liveRepository = container.liveRepository,
                            watchlistRepository = container.watchlistRepository,
                        )
                    },
                )
                GuideRoute(
                    viewModel = guideVm,
                    onBack = { navController.popBackStack() },
                    onPlayChannel = { playChannelById(navController, it) },
                )
            }
            composable(MainDestination.Movies.route) {
                val moviesVm: MoviesViewModel = viewModel(
                    key = "movies-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        MoviesViewModel(session.account, container.movieRepository)
                    },
                )
                MoviesRoute(
                    viewModel = moviesVm,
                    onOpenMovie = { movieId -> openMovie(navController, movieId) },
                )
            }
            composable(
                route = MOVIE_DETAILS_ROUTE_PATTERN,
                arguments = listOf(navArgument("movieId") { type = NavType.StringType }),
            ) { entry ->
                val movieId = entry.arguments?.getString("movieId")
                if (movieId != null) {
                    val detailsVm: MovieDetailsViewModel = viewModel(
                        key = "movie-${session.account.id}-$movieId",
                        factory = SimpleViewModelFactory {
                            MovieDetailsViewModel(
                                account = session.account,
                                movieId = movieId,
                                repository = container.movieRepository,
                                watchlist = container.watchlistRepository,
                            )
                        },
                    )
                    MovieDetailsRoute(
                        viewModel = detailsVm,
                        onBack = { navController.popBackStack() },
                        onPlay = { restart -> playMovie(navController, movieId, restart) },
                    )
                } else LaunchedEffect(Unit) { navController.popBackStack() }
            }
            composable(MainDestination.Series.route) {
                val seriesVm: SeriesViewModel = viewModel(
                    key = "series-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        SeriesViewModel(session.account, container.seriesRepository)
                    },
                )
                SeriesRoute(
                    viewModel = seriesVm,
                    onOpenSeries = { seriesId -> openSeries(navController, seriesId) },
                )
            }
            composable(
                route = SERIES_DETAILS_ROUTE_PATTERN,
                arguments = listOf(navArgument("seriesId") { type = NavType.StringType }),
            ) { entry ->
                val seriesId = entry.arguments?.getString("seriesId")
                if (seriesId != null) {
                    val detailsVm: SeriesDetailsViewModel = viewModel(
                        key = "series-${session.account.id}-$seriesId",
                        factory = SimpleViewModelFactory {
                            SeriesDetailsViewModel(
                                account = session.account,
                                seriesId = seriesId,
                                repository = container.seriesRepository,
                                watchlist = container.watchlistRepository,
                            )
                        },
                    )
                    SeriesDetailsRoute(
                        viewModel = detailsVm,
                        onBack = { navController.popBackStack() },
                        onPlayEpisode = { episodeId, restart ->
                            playEpisode(navController, episodeId, restart)
                        },
                    )
                } else LaunchedEffect(Unit) { navController.popBackStack() }
            }
            composable(MainDestination.Search.route) {
                val searchVm: SearchViewModel = viewModel(
                    key = "search-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        SearchViewModel(
                            account = session.account,
                            liveRepository = container.liveRepository,
                            movieRepository = container.movieRepository,
                            seriesRepository = container.seriesRepository,
                        )
                    },
                )
                SearchRoute(
                    viewModel = searchVm,
                    onPlayChannel = { playChannel(navController, it) },
                    onOpenMovie = { movieId -> openMovie(navController, movieId) },
                    onOpenSeries = { seriesId -> openSeries(navController, seriesId) },
                )
            }
            composable(SETTINGS_ROUTE) {
                val settingsVm: SettingsViewModel = viewModel(
                    key = "settings-${session.account.id}",
                    factory = SimpleViewModelFactory {
                        SettingsViewModel(
                            account = session.account,
                            sessionRepository = container.sessionRepository,
                            liveRepository = container.liveRepository,
                            preferences = container.preferences,
                            stopPlayback = container.playbackCoordinator::stopAndClear,
                            clearArtworkCache = container::clearArtworkCache,
                            clearTrackLanguages = container::clearTrackLanguages,
                            planUserDataImport = { accountId, document ->
                                container.userDataImporter.plan(accountId, document)
                            },
                            applyUserDataImport = { accountId, plan ->
                                container.userDataImporter.apply(accountId, plan)
                            },
                            buildUserDataExport = { accountId ->
                                UserDataExportCodec.encode(
                                    container.userDataExporter.export(accountId),
                                )
                            },
                            // Forced, because this one is a person asking rather than a launch.
                            checkForUpdate = { container.checkForUpdate(force = true) },
                            collectDiagnostics = { television ->
                                container.collectDiagnostics(session.account, television)
                            },
                        )
                    },
                )
                SettingsRoute(settingsVm)
            }
            composable(
                route = PLAYER_ROUTE_PATTERN,
                arguments = listOf(
                    navArgument("kind") { type = NavType.StringType },
                    navArgument("contentId") { type = NavType.StringType },
                    navArgument("restart") {
                        type = NavType.BoolType
                        defaultValue = false
                    },
                ),
            ) { entry ->
                val kind = entry.arguments?.getString("kind")
                val contentId = entry.arguments?.getString("contentId")
                val restart = entry.arguments?.getBoolean("restart") == true
                val request = when {
                    contentId == null -> null
                    kind == PLAYER_KIND_LIVE -> PlaybackRequest.Live(contentId)
                    kind == PLAYER_KIND_MOVIE -> PlaybackRequest.Movie(contentId, restart)
                    kind == PLAYER_KIND_EPISODE -> PlaybackRequest.Episode(contentId, restart)
                    else -> null
                }
                if (request != null) {
                    val playerVm: PlayerViewModel = viewModel(
                        key = "player-${session.account.id}-$kind-$contentId-$restart",
                        factory = SimpleViewModelFactory {
                            PlayerViewModel(
                                account = session.account,
                                request = request,
                                liveRepository = container.liveRepository,
                                movieRepository = container.movieRepository,
                                seriesRepository = container.seriesRepository,
                                coordinator = container.playbackCoordinator,
                                connection = container.playerConnection,
                                presentationState = container.playerPresentationState,
                                progressWriter = container.watchProgressWriter,
                                trackLanguageWriter = container.trackLanguageWriter,
                                autoPlayNextEpisode = autoPlayNextEpisode,
                            )
                        },
                    )
                    PlayerRoute(
                        viewModel = playerVm,
                        connection = container.playerConnection,
                        isInPictureInPictureMode = isInPictureInPictureMode,
                        videoScaleMode = videoScaleMode,
                        seekIncrementMs = doubleTapSeekSeconds * 1_000L,
                        holdPlaybackSpeed = holdPlaybackSpeed,
                        subtitleStyle = subtitleStyle,
                        onVideoScaleModeChange = { mode ->
                            scope.launch { container.preferences.setVideoScaleMode(mode) }
                        },
                        onBrightnessSettled = { level ->
                            scope.launch { container.preferences.setPlayerBrightness(level) }
                        },
                        onBack = {
                            playerVm.stop()
                            navController.popBackStack()
                        },
                        onPlayEpisode = { episodeId ->
                            playerVm.stop()
                            playNextEpisode(navController, episodeId)
                        },
                    )
                } else LaunchedEffect(Unit) { navController.popBackStack() }
            }
        }
    }
}

@Composable
private fun SettingsButton(modifier: Modifier, onClick: () -> Unit) {
    androidx.compose.material3.IconButton(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Settings, contentDescription = "Settings")
    }
}

@Composable
private fun MainBottomBar(navController: NavHostController, playlistAccount: Boolean) {
    val backStack by navController.currentBackStackEntryAsState()
    val current = backStack?.destination
    NavigationBar {
        MainDestination.forAccount(playlistAccount).forEach { destination ->
            NavigationBarItem(
                selected = current?.hierarchy?.any { it.route == destination.route } == true,
                onClick = {
                    navController.navigate(destination.route) {
                        popUpTo(MainDestination.Home.route) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label) },
            )
        }
    }
}

private fun playChannel(navController: NavHostController, channel: LiveChannel) =
    playChannelById(navController, channel.id)

private fun playChannelById(navController: NavHostController, channelId: String) {
    navController.navigate("$PLAYER_ROUTE/$PLAYER_KIND_LIVE/${Uri.encode(channelId)}") {
        launchSingleTop = true
    }
}

private fun openMovie(navController: NavHostController, movieId: String) {
    navController.navigate("$MOVIE_DETAILS_ROUTE/${Uri.encode(movieId)}") {
        launchSingleTop = true
    }
}

private fun openSeries(navController: NavHostController, seriesId: String) {
    navController.navigate("$SERIES_DETAILS_ROUTE/${Uri.encode(seriesId)}") {
        launchSingleTop = true
    }
}

private fun playMovie(navController: NavHostController, movieId: String, restart: Boolean) {
    navController.navigate(
        "$PLAYER_ROUTE/$PLAYER_KIND_MOVIE/${Uri.encode(movieId)}?restart=$restart",
    ) {
        launchSingleTop = true
    }
}

/**
 * Replaces the player rather than stacking one, so Back from episode five returns to the series
 * instead of walking backwards through every episode watched in a sitting.
 */
private fun playNextEpisode(navController: NavHostController, episodeId: String) {
    navController.navigate("$PLAYER_ROUTE/$PLAYER_KIND_EPISODE/${Uri.encode(episodeId)}") {
        popUpTo(PLAYER_ROUTE_PATTERN) { inclusive = true }
        launchSingleTop = true
    }
}

private fun playEpisode(navController: NavHostController, episodeId: String, restart: Boolean) {
    navController.navigate(
        "$PLAYER_ROUTE/$PLAYER_KIND_EPISODE/${Uri.encode(episodeId)}?restart=$restart",
    ) {
        launchSingleTop = true
    }
}
