package com.kunk.singbox.core

import android.util.Log
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.StringIterator

object LibboxCompat {
    private const val TAG = "LibboxCompat"

    fun setConnectionOwnerPackageName(owner: ConnectionOwner, packageName: String) {
        setConnectionOwnerPackageNames(owner, listOf(packageName))
    }

    fun setConnectionOwnerPackageNames(owner: ConnectionOwner, packageNames: Collection<String>) {
        val normalized = packageNames.map(String::trim).filter(String::isNotEmpty).distinct()
        if (normalized.isEmpty()) return
        runCatching {
            owner.setAndroidPackageNames(stringIterator(normalized))
        }.onFailure {
            Log.w(TAG, "Failed to set android package names", it)
        }
    }

    fun isNaiveQuicSupported(): Boolean {
        val normalized = getVersion().removePrefix("v")
        val parts = normalized.substringBefore('-').split(".")
        val major = parts.getOrNull(0)?.toIntOrNull()
        val minor = parts.getOrNull(1)?.toIntOrNull()

        return parts.size < 3 || major == null || minor == null ||
            major > 1 || (major == 1 && minor >= 13)
    }

    private fun getVersion(): String {
        return runCatching { Libbox.version() }.getOrDefault("unknown")
    }

    private fun stringIterator(values: List<String>): StringIterator {
        return object : StringIterator {
            private var index = 0

            override fun hasNext(): Boolean = index < values.size

            override fun len(): Int = values.size

            override fun next(): String = values[index++]
        }
    }
}
