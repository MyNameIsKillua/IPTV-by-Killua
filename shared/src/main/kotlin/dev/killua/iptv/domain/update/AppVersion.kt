package dev.killua.iptv.domain.update

/**
 * A released version, as three numbers and an optional pre-release suffix.
 *
 * This exists because the obvious comparison is wrong. Comparing `"1.0.10"` against `"1.0.9"` as
 * text says the newer one is older, and an updater that gets that backwards either hides a release
 * or offers a downgrade. Both are worse than having no updater.
 *
 * It also has to read this project's own past: every tag through `v0.2.0-alpha.39` carries a
 * pre-release suffix, and an installation still on one of those must be told that `1.0.1` is newer.
 */
data class AppVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    /** `alpha.39` in `0.2.0-alpha.39`, or null for an ordinary release. */
    val preRelease: String? = null,
) : Comparable<AppVersion> {

    override fun compareTo(other: AppVersion): Int {
        (major - other.major).let { if (it != 0) return it }
        (minor - other.minor).let { if (it != 0) return it }
        (patch - other.patch).let { if (it != 0) return it }
        // Semantic versioning's one counter-intuitive rule, and this project depends on it: a
        // pre-release sorts *before* the release it leads to. 1.0.0-alpha.3 is older than 1.0.0.
        return when {
            preRelease == null && other.preRelease == null -> 0
            preRelease == null -> 1
            other.preRelease == null -> -1
            else -> comparePreRelease(preRelease, other.preRelease)
        }
    }

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    companion object {
        /**
         * Reads `1.0.1`, `v1.0.1`, `0.2.0-alpha.39`, or null when it is not a version at all.
         *
         * Null is a real answer and the callers act on it: an unparseable tag means the check says
         * nothing rather than guessing, because a guess here ends in an offered downgrade.
         */
        fun parse(raw: String): AppVersion? {
            val text = raw.trim().removePrefix("v").removePrefix("V")
            if (text.isEmpty()) return null
            val core = text.substringBefore('-')
            val suffix = text.substringAfter('-', missingDelimiterValue = "").ifEmpty { null }
            val parts = core.split('.')
            if (parts.size != 3) return null
            val numbers = parts.map { part ->
                // toIntOrNull accepts a leading sign, which "1.0.+1" would sneak past.
                if (part.isEmpty() || part.any { !it.isDigit() }) return null
                part.toIntOrNull() ?: return null
            }
            return AppVersion(numbers[0], numbers[1], numbers[2], suffix)
        }

        /**
         * Field by field, numbers numerically and everything else as text.
         *
         * Without the numeric half, `alpha.9` would outrank `alpha.10` for the same reason plain
         * string comparison fails on the version itself.
         */
        private fun comparePreRelease(left: String, right: String): Int {
            val a = left.split('.')
            val b = right.split('.')
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrNull(i) ?: return -1
                val y = b.getOrNull(i) ?: return 1
                val xn = x.toIntOrNull()
                val yn = y.toIntOrNull()
                val result = when {
                    xn != null && yn != null -> xn - yn
                    // A numeric identifier ranks below an alphanumeric one, per semver.
                    xn != null -> -1
                    yn != null -> 1
                    else -> x.compareTo(y)
                }
                if (result != 0) return result
            }
            return 0
        }
    }
}
