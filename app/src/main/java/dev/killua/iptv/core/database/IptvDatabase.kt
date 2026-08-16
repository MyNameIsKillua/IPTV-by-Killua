package dev.killua.iptv.core.database

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        AccountEntity::class,
        LiveCategoryEntity::class,
        LiveChannelEntity::class,
        RecentChannelEntity::class,
        MovieCategoryEntity::class,
        MovieEntity::class,
        MovieDetailsEntity::class,
        MovieFavoriteEntity::class,
        SeriesCategoryEntity::class,
        SeriesEntity::class,
        SeriesDetailsEntity::class,
        SeriesEpisodeEntity::class,
        SeriesFavoriteEntity::class,
        WatchProgressEntity::class,
        WatchlistEntity::class,
    ],
    version = 9,
    exportSchema = true,
)
abstract class IptvDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun liveDao(): LiveDao
    abstract fun movieDao(): MovieDao
    abstract fun seriesDao(): SeriesDao
    abstract fun watchlistDao(): WatchlistDao
}
