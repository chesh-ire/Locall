package com.example.locall.voice.research.chunkperf

import android.content.Context
import org.json.JSONArray

/**
 * Loads the FROZEN 105-chunk benchmark corpus from `assets/perf/chunks105.json`.
 *
 * The corpus is pre-split (not re-chunked at runtime) so every run — baseline or
 * optimized, on any device — processes byte-for-byte identical inputs. That is what
 * makes the before/after comparison apples-to-apples.
 */
object ChunkCorpus {

    const val EXPECTED = 105
    private const val ASSET = "perf/chunks105.json"

    @Volatile private var cached: List<String>? = null

    fun load(context: Context): List<String> {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val json = context.applicationContext.assets.open(ASSET)
                .bufferedReader().use { it.readText() }
            val arr = JSONArray(json)
            val list = ArrayList<String>(arr.length())
            for (i in 0 until arr.length()) list.add(arr.getString(i))
            require(list.size == EXPECTED) {
                "chunks105.json must contain exactly $EXPECTED chunks, found ${list.size}"
            }
            cached = list
            return list
        }
    }
}
