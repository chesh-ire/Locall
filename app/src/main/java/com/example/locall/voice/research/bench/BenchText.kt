package com.example.locall.voice.research.bench

import com.example.locall.voice.research.data.RetrievableChunk

object BenchText {

    fun estimateTokens(text: String): Int = (text.length + BenchConfig.CHARS_PER_TOKEN - 1) / BenchConfig.CHARS_PER_TOKEN

    fun render(chunk: RetrievableChunk, maxChars: Int): String =
        "${chunk.citation}\n${chunk.text.take(maxChars).trim()}"

    fun <T> groupsOf(items: List<T>, groupSize: Int): List<List<T>> {
        if (groupSize <= 0) return listOf(items)
        return items.chunked(groupSize)
    }

    fun packByChars(items: List<String>, budgetChars: Int): List<List<String>> {
        val groups = ArrayList<List<String>>()
        var cur = ArrayList<String>()
        var len = 0
        for (it in items) {
            if (cur.isNotEmpty() && len + it.length > budgetChars) {
                groups.add(cur); cur = ArrayList(); len = 0
            }
            cur.add(it); len += it.length + 1
        }
        if (cur.isNotEmpty()) groups.add(cur)
        return groups
    }

    fun joinToBudget(items: List<String>, budgetChars: Int): String {
        val sb = StringBuilder()
        for (it in items) {
            if (sb.length + it.length + 2 > budgetChars) break
            if (sb.isNotEmpty()) sb.append("\n\n")
            sb.append(it)
        }
        return sb.toString()
    }
}
