package dev.killua.iptv.core.text

/**
 * The three invisible bytes that make a valid JSON document unparseable.
 *
 * A UTF-8 byte order mark is `EF BB BF` on disk and a single `﻿` once decoded. JSON parsers
 * reject it, because the specification has no room for a character before the opening brace - so a
 * file that opens correctly in every editor fails to load, with nothing on screen to explain why.
 *
 * This project already strips it from everything a provider sends: [dev.killua.iptv.core.network]
 * and the playlist parser all do, and there is a test asserting that a marked JSON array still
 * parses. It never did so for its **own** files, which is how a settings file rewritten by a
 * PowerShell one-liner - `Set-Content -Encoding utf8` adds a mark in Windows PowerShell - silently
 * reset every preference on the next launch.
 *
 * Windows is where this matters. Notepad wrote marks for decades, plenty of editors still do, and
 * the person who lost their settings did nothing wrong.
 */
private const val BYTE_ORDER_MARK = '﻿'

/**
 * The same text without a leading byte order mark, and unchanged when there is none.
 *
 * Only the leading one. A `﻿` in the middle of a document is not a mark, it is content - and
 * quietly deleting content is a different and worse bug than the one this fixes.
 */
fun String.withoutByteOrderMark(): String =
    if (startsWith(BYTE_ORDER_MARK)) substring(1) else this
