package com.minifile.explorer.core.update

/**
 * Semantic version (`major.minor.patch[-preRelease]`) per `ARCHITECTURE.md` §11.1.
 */
data class SemanticVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val preRelease: String? = null,
) : Comparable<SemanticVersion> {

    override fun compareTo(other: SemanticVersion): Int {
        major.compareTo(other.major).let { if (it != 0) return it }
        minor.compareTo(other.minor).let { if (it != 0) return it }
        patch.compareTo(other.patch).let { if (it != 0) return it }
        return comparePreRelease(preRelease, other.preRelease)
    }

    override fun toString(): String =
        "$major.$minor.$patch" + (preRelease?.let { "-$it" } ?: "")

    val isPreRelease: Boolean
        get() = preRelease != null

    companion object {
        private val PATTERN = Regex("""^(\d+)\.(\d+)(?:\.(\d+))?(?:-([0-9A-Za-z.\-]+))?$""")

        fun stripDebugSuffix(v: String?): String? {
            val trimmed = v?.trim() ?: return null
            return when {
                trimmed.endsWith("-DEBUG", ignoreCase = true) -> trimmed.dropLast(6)
                else -> trimmed
            }
        }

        fun parse(s: String?): SemanticVersion? {
            var clean = s?.trim() ?: return null
            if (clean.isEmpty()) return null
            if (clean.startsWith("v") || clean.startsWith("V")) {
                clean = clean.substring(1)
            }
            clean = clean.substringBefore('+')
            val match = PATTERN.matchEntire(clean) ?: return null
            val major = match.groupValues[1].toIntOrNull() ?: return null
            val minor = match.groupValues[2].toIntOrNull() ?: return null
            val patch = match.groupValues[3].ifEmpty { "0" }.toIntOrNull() ?: return null
            val pre = match.groupValues[4].ifEmpty { null }
            if (pre != null && pre.split('.').any { it.isEmpty() }) return null
            return SemanticVersion(major, minor, patch, pre)
        }

        fun isNewerVersion(current: String?, candidate: String?): Boolean {
            val baseCurrent = parse(stripDebugSuffix(current)) ?: return false
            val parsedCandidate = parse(candidate) ?: return false
            return parsedCandidate > baseCurrent
        }

        private fun comparePreRelease(a: String?, b: String?): Int {
            if (a == b) return 0
            if (a == null) return 1
            if (b == null) return -1
            val pa = a.split('.')
            val pb = b.split('.')
            for (i in 0 until minOf(pa.size, pb.size)) {
                val x = pa[i]
                val y = pb[i]
                val nx = x.toBigIntegerOrNull()?.takeIf { x.all(Char::isDigit) }
                val ny = y.toBigIntegerOrNull()?.takeIf { y.all(Char::isDigit) }
                val cmp = when {
                    nx != null && ny != null -> nx.compareTo(ny)
                    nx != null -> -1
                    ny != null -> 1
                    else -> x.compareTo(y)
                }
                if (cmp != 0) return cmp
            }
            return pa.size.compareTo(pb.size)
        }
    }
}
