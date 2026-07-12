package com.kunk.singbox.core

import io.nekohasekai.libbox.StringIterator

/** libbox StringIterator 的最小实现，供平台适配复用。 */
internal class StringIteratorImpl(private val list: List<String>) : StringIterator {
    private var index = 0
    override fun hasNext(): Boolean = index < list.size
    override fun next(): String = list[index++]
    override fun len(): Int = list.size
}
