package dev.killua.iptv.core.database

import androidx.room.withTransaction

/**
 * The database transaction boundary used by account-scoped writes.
 *
 * Keeping it behind a plain interface lets ordering and cleanup rules be covered by deterministic
 * JVM tests, while production code still commits through a single real Room transaction.
 */
interface TransactionRunner {
    suspend fun <T> inTransaction(block: suspend () -> T): T
}

class RoomTransactionRunner(private val database: IptvDatabase) : TransactionRunner {
    override suspend fun <T> inTransaction(block: suspend () -> T): T =
        database.withTransaction(block)
}
