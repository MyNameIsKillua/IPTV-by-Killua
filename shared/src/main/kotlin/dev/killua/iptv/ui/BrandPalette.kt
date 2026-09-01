package dev.killua.iptv.ui

/**
 * The one place the product's colours are defined.
 *
 * Plain ARGB longs rather than Compose `Color`, because `:shared` has no Compose runtime — its
 * artifact is an AAR a JVM library cannot consume. Each client turns these into its own `Color`.
 *
 * They live here so the Android app and the desktop client cannot drift into being two products
 * that merely resemble each other. A colour changed for one is changed for both, which is the whole
 * argument for a shared module applied to something a viewer can actually see.
 */
object BrandPalette {
    /** The deepest background. Almost black, with enough violet in it to not read as grey. */
    const val NIGHT = 0xFF09090EL

    /** Cards and raised surfaces. */
    const val NIGHT_RAISED = 0xFF14131DL

    /** Inputs, hovered rows, anything that needs to lift off the background by one step. */
    const val NIGHT_SOFT = 0xFF1D1B29L

    const val VIOLET = 0xFF8B5CF6L
    const val VIOLET_BRIGHT = 0xFFA78BFAL
    const val VIOLET_DEEP = 0xFF3B256AL
    const val ON_VIOLET = 0xFF1B1039L
    const val ON_VIOLET_CONTAINER = 0xFFE9DDFFL

    const val CYAN = 0xFF38BDF8L
    const val ON_CYAN = 0xFF002B3AL

    const val SUCCESS = 0xFF34D399L
    const val WARNING = 0xFFFBBF24L
    const val ERROR = 0xFFFB7185L

    /** Body text on a dark surface: not pure white, faintly violet, easier to read for long. */
    const val INK = 0xFFF5F3FFL
    const val INK_MUTED = 0xFFAAA6B9L
}
