package dev.killua.iptv.data.repository

import com.google.common.truth.Truth.assertThat
import dev.killua.iptv.domain.model.AppFailureException
import dev.killua.iptv.domain.model.FailureKind
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Test

class AccountDataCoordinatorTest {
    private val transactions = RecordingTransactionRunner()
    private val accountDao = FakeAccountDao()
    private val vault = FakeCredentialVault(accountId = ACCOUNT)
    private val live = RecordingCleaner(transactions)
    private val movies = RecordingCleaner(transactions)

    private val coordinator = AccountDataCoordinator(
        transactions = transactions,
        accountDao = accountDao,
        credentialVault = vault,
        cleaners = { listOf(live, movies) },
    )

    @Test
    fun `a commit runs while the account still owns the vault record`() = runTest {
        assertThat(coordinator.commit(ACCOUNT) { "written" }).isEqualTo("written")
    }

    @Test
    fun `a commit after logout is rejected and never runs its write`() = runTest {
        vault.accountId = null
        var written = false

        val failure = assertFailure { coordinator.commit(ACCOUNT) { written = true } }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(written).isFalse()
    }

    @Test
    fun `a commit for a replaced account is rejected before any transaction opens`() = runTest {
        vault.accountId = "a-different-account"
        var written = false

        val failure = assertFailure { coordinator.commitTransaction(ACCOUNT) { written = true } }

        assertThat(failure.failure.kind).isEqualTo(FailureKind.AuthenticationFailed)
        assertThat(written).isFalse()
        assertThat(transactions.started).isEqualTo(0)
    }

    @Test
    fun `a transactional commit runs its write inside exactly one transaction`() = runTest {
        coordinator.commitTransaction(ACCOUNT) {
            assertThat(transactions.depth).isEqualTo(1)
        }

        assertThat(transactions.started).isEqualTo(1)
        assertThat(transactions.maximumDepth).isEqualTo(1)
    }

    @Test
    fun `logout clears every registered content area and the account rows together`() = runTest {
        coordinator.clearAllAccountData()

        assertThat(live.clearedAll).isEqualTo(1)
        assertThat(movies.clearedAll).isEqualTo(1)
        assertThat(accountDao.deletedAll).isEqualTo(1)
        assertThat(transactions.started).isEqualTo(1)
        assertThat(live.observedTransactionDepth).containsExactly(1)
        assertThat(movies.observedTransactionDepth).containsExactly(1)
    }

    @Test
    fun `account replacement keeps only the new account across every content area`() = runTest {
        coordinator.clearAllAccountDataExcept(ACCOUNT)

        assertThat(live.keptAccounts).containsExactly(ACCOUNT)
        assertThat(movies.keptAccounts).containsExactly(ACCOUNT)
        assertThat(accountDao.keptAccounts).containsExactly(ACCOUNT)
        assertThat(transactions.started).isEqualTo(1)
    }

    @Test
    fun `logout waits for an in-flight commit instead of interleaving with it`() = runTest {
        val commitReached = CompletableDeferred<Unit>()
        val releaseCommit = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()

        val commit = launch {
            coordinator.commitTransaction(ACCOUNT) {
                order += "commit-started"
                commitReached.complete(Unit)
                releaseCommit.await()
                order += "commit-finished"
            }
        }
        commitReached.await()

        val logout = launch {
            coordinator.clearAllAccountData()
            order += "logout-finished"
        }
        testScheduler.runCurrent()

        // Cleanup must still be waiting on the shared lock while the commit holds it.
        assertThat(order).containsExactly("commit-started")
        assertThat(live.clearedAll).isEqualTo(0)

        releaseCommit.complete(Unit)
        commit.join()
        logout.join()

        assertThat(order)
            .containsExactly("commit-started", "commit-finished", "logout-finished")
            .inOrder()
        assertThat(live.clearedAll).isEqualTo(1)
        assertThat(transactions.maximumDepth).isEqualTo(1)
    }

    @Test
    fun `a download that outlives logout is rejected at its commit point`() = runTest {
        val downloadFinished = CompletableDeferred<Unit>()
        var written = false

        // A refresh downloads outside the lock, so a logout can complete while it is in flight.
        val refresh = launch {
            downloadFinished.await()
            assertFailure { coordinator.commitTransaction(ACCOUNT) { written = true } }
        }

        coordinator.clearAllAccountData()
        vault.accountId = null
        downloadFinished.complete(Unit)
        refresh.join()

        assertThat(written).isFalse()
        assertThat(accountDao.deletedAll).isEqualTo(1)
    }

    private suspend fun assertFailure(block: suspend () -> Unit): AppFailureException {
        try {
            block()
            throw AssertionError("Expected AppFailureException")
        } catch (failure: AppFailureException) {
            return failure
        }
    }

    private class RecordingCleaner(
        private val transactions: RecordingTransactionRunner,
    ) : AccountDataCleaner {
        var clearedAll = 0
            private set
        val keptAccounts = mutableListOf<String>()
        val observedTransactionDepth = mutableListOf<Int>()

        override suspend fun clearAllAccountData() {
            clearedAll++
            observedTransactionDepth += transactions.depth
        }

        override suspend fun clearAllAccountDataExcept(accountId: String) {
            keptAccounts += accountId
            observedTransactionDepth += transactions.depth
        }
    }

    private companion object {
        const val ACCOUNT = "account-under-test"
    }
}
