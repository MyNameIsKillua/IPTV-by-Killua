package dev.killua.iptv.desktop

import java.io.File

/**
 * Writing a local file so that a failure leaves the old one intact.
 *
 * Four things in this client write to disk — the state file, the title cache, the preferences and
 * the artwork store — and every one of them had its own copy of the same four steps: make the
 * directory, write a temporary file, rename it over the target, and on Windows fall back to deleting
 * the target first because a rename onto an existing file is refused there.
 *
 * Four copies of a rule is three chances for one of them to drift, and the one that drifts will be
 * the one nobody notices: a save that silently does nothing looks exactly like an application that
 * forgot. So the rule lives here once.
 *
 * A half-written file is the failure this guards against. The temporary is what takes the damage,
 * and it is removed rather than left behind — a stray `.tmp` beside a state file is the kind of
 * thing that gets found months later and cannot be explained.
 */
internal fun writeAtomically(directory: File, name: String, write: (File) -> Unit): File? =
    runCatching {
        directory.mkdirs()
        val target = File(directory, name)
        val temporary = File(directory, "$name.tmp")
        try {
            write(temporary)
        } catch (failure: Throwable) {
            temporary.delete()
            throw failure
        }
        if (!temporary.renameTo(target)) {
            // Windows refuses a rename onto an existing file. Without this every save after the
            // first is dropped, which is the quietest possible way to lose someone's evening.
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                return@runCatching null
            }
        }
        target
    }.getOrNull()
