package dev.killua.iptv.data.repository

import dev.killua.iptv.core.database.AccountDao
import dev.killua.iptv.core.database.TransactionRunner
import dev.killua.iptv.core.security.CredentialVault
import dev.killua.iptv.domain.model.AppFailure
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A content area that owns account-scoped rows and can be cleared centrally.
 *
 * Implementations are invoked by [AccountDataCoordinator] while it already holds the shared lock
 * and an open transaction. They must therefore perform plain DAO deletes and must never acquire
 * the coordinator lock or open a transaction of their own.
 */
interface AccountDataCleaner {
    suspend fun clearAllAccountData()
    suspend fun clearAllAccountDataExcept(accountId: String)
}

/**
 * Serializes every account-scoped mutation in the application with logout and account
 * replacement, and owns the one place where local account data is deleted.
 *
 * Each content area (Live today, Movies next) registers an [AccountDataCleaner] instead of
 * keeping a private lock. A private per-area lock would let a slow refresh or progress write
 * from one area commit after another area had already cleared the account, silently recreating
 * data the user just logged out of.
 *
 * Locking rules:
 * - The lock is **not** reentrant. Exactly one coordinator call may be active per operation;
 *   never call another coordinator method from inside a [commit] or [commitTransaction] block.
 * - Network downloads must happen *before* [commit]/[commitTransaction] so a long refresh never
 *   blocks logout. Ownership is rechecked under the lock, so a download that outlived its
 *   account is rejected rather than committed.
 * - Lock ordering is session mutex -> coordinator lock. Nothing invoked while the coordinator
 *   lock is held may take the session mutex, so the two cannot deadlock.
 */
class AccountDataCoordinator(
    private val transactions: TransactionRunner,
    private val accountDao: AccountDao,
    private val credentialVault: CredentialVault,
    private val cleaners: () -> List<AccountDataCleaner>,
) {
    private val mutex = Mutex()

    /**
     * Runs a single account-scoped write after confirming that [accountId] still owns the active
     * credential record. Throws [AppFailureException] with [FailureKind.AuthenticationFailed]
     * when the account was logged out or replaced, leaving [block] unexecuted.
     */
    suspend fun <T> commit(accountId: String, block: suspend () -> T): T = mutex.withLock {
        requireActiveAccount(accountId)
        block()
    }

    /** [commit] for a multi-row write that must land as one all-or-nothing transaction. */
    suspend fun <T> commitTransaction(accountId: String, block: suspend () -> T): T = mutex.withLock {
        requireActiveAccount(accountId)
        transactions.inTransaction(block)
    }

    /** Removes every local account, library, and user row. Used by logout and unusable vaults. */
    suspend fun clearAllAccountData() = mutex.withLock {
        transactions.inTransaction {
            cleaners().forEach { it.clearAllAccountData() }
            accountDao.deleteAll()
        }
    }

    /** Removes local data for every account other than the one that just became active. */
    suspend fun clearAllAccountDataExcept(accountId: String) = mutex.withLock {
        transactions.inTransaction {
            cleaners().forEach { it.clearAllAccountDataExcept(accountId) }
            accountDao.deleteAllExcept(accountId)
        }
    }

    private suspend fun requireActiveAccount(accountId: String) {
        val active = credentialVault.load()
        if (active == null || active.accountId != accountId) {
            throw AppFailureException(AppFailure(FailureKind.AuthenticationFailed))
        }
    }
}
