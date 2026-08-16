package dev.killua.iptv.core.database

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface AccountDao {
    @Upsert
    suspend fun upsert(account: AccountEntity)

    @Query("SELECT * FROM accounts WHERE accountId = :accountId LIMIT 1")
    suspend fun get(accountId: String): AccountEntity?

    @Query("SELECT * FROM accounts WHERE accountId = :accountId LIMIT 1")
    fun observe(accountId: String): Flow<AccountEntity?>

    @Query("UPDATE accounts SET displayName = :displayName WHERE accountId = :accountId")
    suspend fun setDisplayName(accountId: String, displayName: String?)

    @Query("UPDATE accounts SET lastLiveSyncAtEpochMillis = :timestamp WHERE accountId = :accountId")
    suspend fun setLastLiveSync(accountId: String, timestamp: Long)

    @Query("DELETE FROM accounts WHERE accountId = :accountId")
    suspend fun delete(accountId: String)

    @Query("DELETE FROM accounts")
    suspend fun deleteAll()

    @Query("DELETE FROM accounts WHERE accountId != :accountId")
    suspend fun deleteAllExcept(accountId: String)
}
