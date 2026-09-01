package dev.killua.iptv

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import coil3.ImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import dev.killua.iptv.core.database.IptvDatabase
import dev.killua.iptv.core.database.MIGRATION_1_2
import dev.killua.iptv.core.database.MIGRATION_2_3
import dev.killua.iptv.core.database.MIGRATION_3_4
import dev.killua.iptv.core.database.MIGRATION_4_5
import dev.killua.iptv.core.database.MIGRATION_5_6
import dev.killua.iptv.core.database.MIGRATION_6_7
import dev.killua.iptv.core.database.MIGRATION_7_8
import dev.killua.iptv.core.database.MIGRATION_8_9
import dev.killua.iptv.core.database.MIGRATION_9_10
import dev.killua.iptv.core.database.RoomTransactionRunner
import dev.killua.iptv.core.network.AndroidNetworkStatus
import dev.killua.iptv.core.network.NetworkFailureMapper
import dev.killua.iptv.core.preferences.AppPreferences
import dev.killua.iptv.core.preferences.noBackupPreferencesFile
import dev.killua.iptv.core.player.PlaybackCoordinator
import dev.killua.iptv.core.player.PlayerConnection
import dev.killua.iptv.core.player.PlayerPresentationState
import dev.killua.iptv.core.player.TrackLanguageWriter
import dev.killua.iptv.core.player.WatchProgressWriter
import dev.killua.iptv.core.security.AndroidCredentialVault
import dev.killua.iptv.core.security.CredentialVault
import dev.killua.iptv.core.update.UpdateChecker
import dev.killua.iptv.core.update.UpdateInstaller
import dev.killua.iptv.data.repository.AccountDataCleaner
import dev.killua.iptv.data.repository.AccountDataCoordinator
import dev.killua.iptv.data.repository.DefaultLiveRepository
import dev.killua.iptv.data.repository.DefaultMovieRepository
import dev.killua.iptv.data.repository.DefaultSeriesRepository
import dev.killua.iptv.data.repository.DefaultSessionRepository
import dev.killua.iptv.data.repository.DefaultWatchlistRepository
import dev.killua.iptv.data.repository.UserDataExporter
import dev.killua.iptv.data.repository.UserDataImporter
import dev.killua.iptv.data.xtream.XtreamRemoteDataSource
import dev.killua.iptv.domain.model.TrackLanguagePreferences
import dev.killua.iptv.domain.repository.LiveRepository
import dev.killua.iptv.domain.repository.MovieRepository
import dev.killua.iptv.domain.repository.SeriesRepository
import dev.killua.iptv.domain.repository.SessionRepository
import dev.killua.iptv.domain.repository.WatchlistRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit
import dev.killua.iptv.data.playlist.PlaylistLiveSource

class AppContainer(context: Context) {
    companion object {
        const val ARTWORK_MEMORY_CACHE_MAX_BYTES = 32L * 1024L * 1024L
        const val ARTWORK_DISK_CACHE_MAX_BYTES = 128L * 1024L * 1024L
    }

    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val dataStore = PreferenceDataStoreFactory.create(
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
        produceFile = { context.noBackupPreferencesFile() },
    )
    val preferences = AppPreferences(dataStore)
    val credentialVault: CredentialVault = AndroidCredentialVault(dataStore)

    // Destructive fallback is deliberately never enabled: an installed production app must
    // survive every schema upgrade with its account, library, favorites, and progress intact.
    val database: IptvDatabase = Room.databaseBuilder(
        context,
        IptvDatabase::class.java,
        "killuas-iptv.db",
    )
        .addMigrations(
            MIGRATION_1_2,
            MIGRATION_2_3,
            MIGRATION_3_4,
            MIGRATION_4_5,
            MIGRATION_5_6,
            MIGRATION_6_7,
            MIGRATION_7_8,
            MIGRATION_8_9,
            MIGRATION_9_10,
        )
        .build()

    val apiHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .callTimeout(35, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build()

    val playbackHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    /**
     * A third client, for the one host that is not the viewer's provider.
     *
     * It is separate rather than reused because the two differ on the setting that matters here.
     * [apiHttpClient] refuses redirects, deliberately - a provider API that redirects is a provider
     * API doing something unexpected. A GitHub release asset *always* redirects to storage, so this
     * one follows. `followSslRedirects(false)` stays: a redirect out of HTTPS is refused on both.
     *
     * Keeping them apart means neither setting can be relaxed for the other's benefit.
     */
    val updateHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .callTimeout(5, TimeUnit.MINUTES)
        .followRedirects(true)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()

    val updateChecker = UpdateChecker(
        client = updateHttpClient,
        preferences = preferences,
        installedVersion = BuildConfig.VERSION_NAME,
    )

    val updateInstaller = UpdateInstaller(
        context = context.applicationContext,
        client = updateHttpClient,
    )

    /**
     * Coil's process-wide loader for channel artwork. Video playback does not use this cache.
     * Both tiers have fixed upper bounds so artwork cannot grow without limit.
     */
    val artworkImageLoader: ImageLoader = ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizeBytes(ARTWORK_MEMORY_CACHE_MAX_BYTES)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("channel_artwork").toOkioPath())
                .maxSizeBytes(ARTWORK_DISK_CACHE_MAX_BYTES)
                .build()
        }
        .build()

    private val remoteDataSource = XtreamRemoteDataSource(
        retrofit = Retrofit.Builder()
            .baseUrl("https://localhost/")
            .client(apiHttpClient)
            .build(),
        failureMapper = NetworkFailureMapper(AndroidNetworkStatus(context)),
    )

    lateinit var sessionRepository: SessionRepository
        private set
    lateinit var liveRepository: LiveRepository
        private set
    lateinit var movieRepository: MovieRepository
        private set
    lateinit var seriesRepository: SeriesRepository
        private set
    lateinit var watchlistRepository: WatchlistRepository
        private set
    lateinit var accountDataCoordinator: AccountDataCoordinator
        private set

    val playerPresentationState = PlayerPresentationState()
    val playerConnection: PlayerConnection by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlayerConnection(context)
    }
    val playbackCoordinator: PlaybackCoordinator by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        PlaybackCoordinator(
            connection = playerConnection,
            sessionRepository = sessionRepository,
            trackLanguages = { preferences.trackLanguages.first() },
        )
    }

    // Deliberately on the application scope: the last checkpoint of a title is written while the
    // player ViewModel is being cleared, when its own scope is already cancelled.
    val watchProgressWriter: WatchProgressWriter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        WatchProgressWriter(applicationScope, movieRepository, seriesRepository)
    }

    // Read-only, so it deliberately does not go through AccountDataCoordinator: an export must not
    // wait behind a library refresh that can run for minutes.
    val userDataExporter: UserDataExporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UserDataExporter(
            dao = database.userDataDao(),
            credentialsFor = { accountId -> sessionRepository.credentialsFor(accountId) },
        )
    }

    // Writes, so unlike the exporter this one goes through the coordinator: an import has to
    // serialize with logout, account replacement and refresh, and land as one transaction.
    val userDataImporter: UserDataImporter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        UserDataImporter(
            dao = database.userDataDao(),
            accountData = accountDataCoordinator,
            credentialsFor = { accountId -> sessionRepository.credentialsFor(accountId) },
        )
    }

    // On the application scope for the same reason: a track picked shortly before leaving the
    // player would otherwise be lost with the ViewModel that observed it.
    val trackLanguageWriter: TrackLanguageWriter by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        TrackLanguageWriter(
            scope = applicationScope,
            load = { preferences.trackLanguages.first() },
            store = { preferences.setTrackLanguages(it) },
        )
    }

    init {
        val accountDao = database.accountDao()
        // Every content area registers its cleanup here instead of owning a private lock, so no
        // late write from one area can recreate data another area already cleared.
        val registeredCleaners = mutableListOf<AccountDataCleaner>()
        val coordinator = AccountDataCoordinator(
            transactions = RoomTransactionRunner(database),
            accountDao = accountDao,
            credentialVault = credentialVault,
            cleaners = { registeredCleaners.toList() },
        )
        accountDataCoordinator = coordinator
        /*
         * One object, two jobs: the sign-in asks it whether an address is a playlist, and the
         * refresh reads the listing through it. Built once so both share the API client's
         * connection pool - a playlist is one request that can legitimately take minutes on a large
         * provider, which is the shape the whole-listing Xtream calls already have.
         */
        val playlist = PlaylistLiveSource(apiHttpClient)
        sessionRepository = DefaultSessionRepository(
            accountDao = accountDao,
            credentialVault = credentialVault,
            remote = remoteDataSource,
            accountData = coordinator,
            applicationScope = applicationScope,
            playlistProbe = playlist,
        )
        val live = DefaultLiveRepository(
            liveDao = database.liveDao(),
            accountDao = accountDao,
            accountData = coordinator,
            sessionRepositoryProvider = { sessionRepository },
            remote = remoteDataSource,
            playlistSource = playlist,
        )
        liveRepository = live
        registeredCleaners += live
        val movies = DefaultMovieRepository(
            movieDao = database.movieDao(),
            accountData = coordinator,
            sessionRepositoryProvider = { sessionRepository },
            remote = remoteDataSource,
        )
        movieRepository = movies
        registeredCleaners += movies
        val seriesLibrary = DefaultSeriesRepository(
            seriesDao = database.seriesDao(),
            accountData = coordinator,
            sessionRepositoryProvider = { sessionRepository },
            remote = remoteDataSource,
        )
        seriesRepository = seriesLibrary
        registeredCleaners += seriesLibrary
        val watchlist = DefaultWatchlistRepository(
            watchlistDao = database.watchlistDao(),
            accountData = coordinator,
        )
        watchlistRepository = watchlist
        registeredCleaners += watchlist
    }

    suspend fun clearArtworkCache() {
        withContext(Dispatchers.IO) {
            artworkImageLoader.memoryCache?.clear()
            artworkImageLoader.diskCache?.clear()
        }
    }

    /**
     * Forgets the remembered track languages.
     *
     * The writer is reset alongside the store, because it suppresses a selection it has already
     * handled. Without that, re-picking the language that was just cleared would be swallowed as a
     * duplicate and never written back.
     */
    suspend fun clearTrackLanguages() {
        preferences.setTrackLanguages(TrackLanguagePreferences())
        trackLanguageWriter.reset()
    }
}
