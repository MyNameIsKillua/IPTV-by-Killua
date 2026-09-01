package dev.killua.iptv.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Three invisible bytes that cost somebody every setting they had.
 *
 * On 1 September 2026 this project's own settings file was rewritten by a PowerShell one-liner.
 * `Set-Content -Encoding utf8` adds a byte order mark in Windows PowerShell, the JSON parser
 * refused the result, the loader fell back to defaults, and the next launch looked like a client
 * that had forgotten everything. Nothing said why, because from the outside nothing was wrong.
 */
class ByteOrderMarkTest {

    @Test
    fun `a leading mark is removed`() {
        assertThat("﻿{\"a\":1}".withoutByteOrderMark()).isEqualTo("{\"a\":1}")
    }

    @Test
    fun `text without one is returned unchanged`() {
        assertThat("{\"a\":1}".withoutByteOrderMark()).isEqualTo("{\"a\":1}")
        assertThat("".withoutByteOrderMark()).isEqualTo("")
    }

    @Test
    fun `only the leading one, because further in it is content`() {
        // Deleting a character from the middle of somebody's data would be a worse bug than the
        // one this exists to fix, and a much harder one to notice.
        assertThat("{\"a\":\"x﻿y\"}".withoutByteOrderMark()).isEqualTo("{\"a\":\"x﻿y\"}")
    }

    @Test
    fun `a second mark survives, because two is not a mark plus a document`() {
        // Stripping until none are left would quietly accept a file that is malformed in a way
        // worth failing on.
        assertThat("﻿﻿{}".withoutByteOrderMark()).isEqualTo("﻿{}")
    }
}
