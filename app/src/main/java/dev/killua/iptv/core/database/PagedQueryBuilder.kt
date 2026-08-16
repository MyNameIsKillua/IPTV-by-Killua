package dev.killua.iptv.core.database

/** A prepared statement plus its bind arguments, in the order SQLite expects them. */
data class PagedQuery(val sql: String, val arguments: List<Any?>)

/**
 * Shared assembly for the paged browsing statements.
 *
 * Declaring one Room `@Query` per filter and sort combination would need dozens of near-identical
 * methods, so Live and Movie browsing both assemble their statement here and run it through
 * `@RawQuery`. Only fixed fragments — enum-derived ordering and flag-selected joins — are ever
 * concatenated; every caller-supplied value is bound as an argument, so no provider or user string
 * can alter the statement.
 *
 * Join arguments are collected separately from condition arguments on purpose. SQLite binds `?`
 * positionally and every join placeholder precedes every `WHERE` placeholder in the finished
 * statement, so appending both to one list in call order silently mismatches them.
 *
 * SQL fragments and column names passed in are code constants, never provider or user text.
 */
class PagedQueryBuilder(private val select: String) {
    private val joins = StringBuilder()
    private val joinArguments = mutableListOf<Any?>()
    private val conditions = mutableListOf<String>()
    private val conditionArguments = mutableListOf<Any?>()

    fun join(sql: String, vararg arguments: Any?) = apply {
        joins.append(' ').append(sql)
        joinArguments.addAll(arguments)
    }

    fun where(condition: String, vararg arguments: Any?) = apply {
        conditions += condition
        conditionArguments.addAll(arguments)
    }

    /**
     * Adds a contains match against the pre-normalized `sortName` column, or nothing at all when
     * [term] holds nothing searchable — that must mean "no search" rather than "match everything".
     *
     * The term is normalized the same way the column was written, so punctuation the viewer did or
     * did not type makes no difference. LIKE wildcards are escaped afterwards, so a search for
     * `100%` cannot match every row.
     */
    fun whereContains(column: String, term: String?) = apply {
        val normalized = LikeSearchTerm.normalize(term).takeIf(String::isNotEmpty) ?: return@apply
        where(
            "$column LIKE ? ESCAPE '${LikeSearchTerm.ESCAPE}'",
            LikeSearchTerm.containsPattern(normalized),
        )
    }

    /**
     * @param orderBy fixed ordering fragment; callers append a unique column so paging stays
     * deterministic when the primary sort key ties.
     */
    fun build(orderBy: String): PagedQuery {
        val sql = buildString {
            append(select)
            append(joins)
            if (conditions.isNotEmpty()) {
                append(" WHERE ")
                append(conditions.joinToString(" AND "))
            }
            append(" ORDER BY ")
            append(orderBy)
        }
        val arguments = joinArguments + conditionArguments
        // A mismatch here means a fragment and its arguments drifted apart, which SQLite would
        // otherwise accept and answer with silently wrong rows.
        check(sql.count { it == '?' } == arguments.size) {
            "Assembled statement has ${sql.count { it == '?' }} placeholders for " +
                "${arguments.size} arguments"
        }
        return PagedQuery(sql, arguments)
    }

}
