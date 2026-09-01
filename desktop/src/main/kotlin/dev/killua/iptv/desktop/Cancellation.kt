package dev.killua.iptv.desktop

import kotlinx.coroutines.CancellationException

/**
 * `runCatching`, except that a cancellation is not a failure to report.
 *
 * `runCatching` catches `Throwable`, and a cancelled coroutine is delivered as one. Around a request
 * that is going to be abandoned — a category still loading when the viewer clicks a different
 * section — that turns "this was called off" into "this went wrong", with two consequences and
 * neither of them small.
 *
 * The first is what a viewer sees. The abandoned coroutine wakes up, finds an exception, and writes
 * a failure banner and an empty list — over whatever the *new* request has since put there, because
 * cancellation arrives whenever the old coroutine is next scheduled and not before. Clicking through
 * the rail quickly could therefore answer "that library could not be loaded" about a library that
 * was loading perfectly well.
 *
 * The second is that the coroutine does not stop. Swallowing the cancellation means the body carries
 * on past it, which is the thing structured concurrency exists to prevent.
 *
 * The Android app has caught this by hand at every one of its twenty-odd boundaries since it had
 * them. The desktop client used bare `runCatching` throughout and never inherited the rule; this is
 * that rule, once, where the call sites can reach it.
 *
 * Only for blocks that can actually suspend. Around a file read there is no suspension point for a
 * cancellation to arrive at, and `runCatching` there is saying something true.
 */
internal inline fun <T> catchingExceptCancellation(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (cancellation: CancellationException) {
        // Rethrown rather than recorded: this coroutine is meant to end here.
        throw cancellation
    } catch (failure: Throwable) {
        Result.failure(failure)
    }
