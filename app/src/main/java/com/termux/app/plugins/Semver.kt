package com.termux.app.plugins

/**
 * Strict Semver 2.0.0 value. Build metadata is parsed but ignored for precedence,
 * per the spec. Used for plugin versions, dependency floors and minCoreVersion.
 */
data class Semver(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val prerelease: String?,
    val raw: String
) : Comparable<Semver> {

    override fun compareTo(other: Semver): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (patch != other.patch) return patch.compareTo(other.patch)
        // A version without prerelease has higher precedence than one with it.
        if (prerelease == null && other.prerelease == null) return 0
        if (prerelease == null) return 1
        if (other.prerelease == null) return -1
        val a = prerelease.split('.')
        val b = other.prerelease.split('.')
        for (i in 0 until minOf(a.size, b.size)) {
            val na = a[i].toIntOrNull()
            val nb = b[i].toIntOrNull()
            val cmp = when {
                na != null && nb != null -> na.compareTo(nb)
                na != null -> -1 // numeric identifiers sort below alphanumeric
                nb != null -> 1
                else -> a[i].compareTo(b[i])
            }
            if (cmp != 0) return cmp
        }
        return a.size.compareTo(b.size)
    }

    override fun toString(): String = raw

    companion object {
        private const val MAX_COMPONENT = 999_999_999

        private val REGEX = Regex(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)" +
                "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)" +
                "(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?" +
                "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$"
        )

        /** Returns null when [value] is not a valid Semver 2.0.0 string. */
        fun parse(value: String?): Semver? {
            if (value == null || value.length > 64) return null
            val m = REGEX.matchEntire(value) ?: return null
            val major = m.groupValues[1].toIntOrNull() ?: return null
            val minor = m.groupValues[2].toIntOrNull() ?: return null
            val patch = m.groupValues[3].toIntOrNull() ?: return null
            if (major > MAX_COMPONENT || minor > MAX_COMPONENT || patch > MAX_COMPONENT) return null
            val prerelease = m.groupValues[4].ifEmpty { null }
            return Semver(major, minor, patch, prerelease, value)
        }

        fun parseOrThrow(value: String?, field: String): Semver =
            parse(value) ?: throw PluginException("$field must be a valid Semver 2.0.0 version, got \"$value\"")
    }
}
