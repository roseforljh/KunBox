package com.kunk.singbox.core

import android.util.Log
import io.raybox.raybox.ConnectionOwner
import io.raybox.raybox.StringIterator

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

    fun isNaiveQuicSupported(): Boolean = com.kunk.singbox.BuildConfig.RAYBOX_NAIVE_QUIC_SUPPORTED

    private fun stringIterator(values: List<String>): StringIterator {
        return object : StringIterator {
            private var index = 0

            override fun hasNext(): Boolean = index < values.size

            override fun len(): Int = values.size

            override fun next(): String = values[index++]
        }
    }
}
