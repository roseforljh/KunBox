package com.kunk.singbox.utils

import android.util.Log

/**
 *
 *
 *
 *
 */
object AppLogger {

    /**
     *
     */
    enum class Level(val priority: Int) {
        VERBOSE(Log.VERBOSE),
        DEBUG(Log.DEBUG),
        INFO(Log.INFO),
        WARN(Log.WARN),
        ERROR(Log.ERROR),
        NONE(Int.MAX_VALUE)
    }

    /**
     */
    @Volatile
    var minLevel: Level = Level.INFO

    /**
     */
    @Volatile
    var enabled: Boolean = true

    @PublishedApi
    internal fun shouldLog(level: Level): Boolean {
        return enabled && level.priority >= minLevel.priority
    }

    /**
     *
     */
    inline fun v(tag: String, message: () -> String) {
        if (shouldLog(Level.VERBOSE)) {
            Log.v(tag, message())
        }
    }

    /**
     *
     */
    inline fun d(tag: String, message: () -> String) {
        if (shouldLog(Level.DEBUG)) {
            Log.d(tag, message())
        }
    }

    /**
     *
     */
    inline fun i(tag: String, message: () -> String) {
        if (shouldLog(Level.INFO)) {
            Log.i(tag, message())
        }
    }

    /**
     *
     */
    inline fun w(tag: String, message: () -> String) {
        if (shouldLog(Level.WARN)) {
            Log.w(tag, message())
        }
    }

    /**
     */
    inline fun w(tag: String, throwable: Throwable?, message: () -> String) {
        if (shouldLog(Level.WARN)) {
            Log.w(tag, message(), throwable)
        }
    }

    /**
     *
     */
    inline fun e(tag: String, message: () -> String) {
        if (shouldLog(Level.ERROR)) {
            Log.e(tag, message())
        }
    }

    /**
     */
    inline fun e(tag: String, throwable: Throwable?, message: () -> String) {
        if (shouldLog(Level.ERROR)) {
            Log.e(tag, message(), throwable)
        }
    }

    /**
     */
    fun v(tag: String, message: String) {
        if (shouldLog(Level.VERBOSE)) Log.v(tag, message)
    }

    fun d(tag: String, message: String) {
        if (shouldLog(Level.DEBUG)) Log.d(tag, message)
    }

    fun i(tag: String, message: String) {
        if (shouldLog(Level.INFO)) Log.i(tag, message)
    }

    fun w(tag: String, message: String) {
        if (shouldLog(Level.WARN)) Log.w(tag, message)
    }

    fun w(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(Level.WARN)) Log.w(tag, message, throwable)
    }

    fun e(tag: String, message: String) {
        if (shouldLog(Level.ERROR)) Log.e(tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable?) {
        if (shouldLog(Level.ERROR)) Log.e(tag, message, throwable)
    }
}
