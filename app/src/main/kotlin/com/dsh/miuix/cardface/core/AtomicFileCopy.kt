package com.dsh.miuix.cardface.core

/** Shell program shared by app-side Root sync and its regression tests. */
object AtomicFileCopy {
    fun script(sourcePath: String, destDir: String, fileName: String, mode: String = "644"): String {
        require(fileName.isNotBlank() && !fileName.contains('/') && !fileName.contains('\\') && fileName !in setOf(".", ".."))
        require(mode.matches(Regex("[0-7]{3}")))
        fun q(value: String) = "'" + value.replace("'", "'\\''") + "'"
        val target = "${destDir.trimEnd('/')}/$fileName"
        val walletRoot = when {
            destDir.contains("/data/data/") -> destDir.substringBefore("/files")
            destDir.contains("/data/user/") -> destDir.substringBefore("/files")
            else -> null
        }
        val prepare = if (walletRoot != null) """
            owner=${'$'}(stat -c %u:%g ${q(walletRoot)}) || exit 14
            chown "${'$'}owner" "${'$'}tmp" || exit 15
            label=${'$'}(stat -c %C ${q(walletRoot)}) || exit 16
            chcon "${'$'}label" "${'$'}tmp" || exit 17
        """.trimIndent() else ""
        return """
            set -eu
            src=${q(sourcePath)}
            dir=${q(destDir.trimEnd('/'))}
            dst=${q(target)}
            [ -f "${'$'}src" ] && [ -s "${'$'}src" ] || exit 10
            mkdir -p "${'$'}dir"
            ${if (walletRoot != null) "owner=${'$'}(stat -c %u:%g ${q(walletRoot)}) && label=${'$'}(stat -c %C ${q(walletRoot)}) && chown \"${'$'}owner\" \"${'$'}dir\" && chcon \"${'$'}label\" \"${'$'}dir\"" else ":"}
            expected=${'$'}(sha256sum "${'$'}src"); expected=${'$'}{expected%% *}
            if [ -f "${'$'}dst" ] && [ -s "${'$'}dst" ]; then
                actual=${'$'}(sha256sum "${'$'}dst"); actual=${'$'}{actual%% *}
                if [ "${'$'}expected" = "${'$'}actual" ]; then
                    chmod $mode "${'$'}dst"
                    ${if (walletRoot != null) "chown \"${'$'}owner\" \"${'$'}dst\" && chcon \"${'$'}label\" \"${'$'}dst\" || exit 18" else ":"}
                    ${if (walletRoot != null) "[ \"${'$'}(stat -c %u:%g \"${'$'}dst\")\" = \"${'$'}owner\" ] && [ \"${'$'}(stat -c %C \"${'$'}dst\")\" = \"${'$'}label\" ] || exit 19" else ":"}
                    exit 0
                fi
            fi
            tmp=${'$'}(mktemp "${'$'}dir/.cardface-sync.XXXXXX")
            trap 'rm -f "${'$'}tmp"' EXIT
            cp "${'$'}src" "${'$'}tmp"
            [ -s "${'$'}tmp" ] || exit 11
            copied=${'$'}(sha256sum "${'$'}tmp"); copied=${'$'}{copied%% *}
            [ "${'$'}copied" = "${'$'}expected" ] || exit 12
            chmod $mode "${'$'}tmp"
            $prepare
            mv -f "${'$'}tmp" "${'$'}dst"
            trap - EXIT
            ${if (walletRoot != null) "[ \"${'$'}(stat -c %u:%g \"${'$'}dst\")\" = \"${'$'}owner\" ] && [ \"${'$'}(stat -c %C \"${'$'}dst\")\" = \"${'$'}label\" ] || exit 19" else ":"}
            final=${'$'}(sha256sum "${'$'}dst"); final=${'$'}{final%% *}
            [ "${'$'}final" = "${'$'}expected" ] || exit 13
        """.trimIndent()
    }
}
