package com.example.locall.voice.research.pipeline

/**
 * Parser for the LLM's labelled block output (SUMMARY:/ENTITIES:/DECISIONS: …). Small on-device
 * models reliably emit labelled sections but not clean JSON, so the pipeline asks for labels and
 * parses them deterministically here. Shared by the chunk analyzer and the agent.
 */
object LabeledSections {

    /** Text after `LABEL:` up to the next of [stops] (case-insensitive). Null if the label is absent. */
    fun section(text: String, label: String, vararg stops: String): String? {
        val start = indexOfLabel(text, label) ?: return null
        val from = start + label.length
        val after = text.indexOf(':', from).let { if (it in from..(from + 2)) it + 1 else from }
        var end = text.length
        for (s in stops) indexOfLabel(text, s)?.let { if (it in after until end) end = it }
        return text.substring(after, end).trim()
    }

    /** The list items under [label] (one per line, bullet markers stripped, "none"/"n/a" dropped). */
    fun listSection(text: String, label: String, vararg stops: String): List<String> {
        val body = section(text, label, *stops) ?: return emptyList()
        return body.split('\n')
            .map { it.trim().removePrefix("-").removePrefix("•").removePrefix("*").trim() }
            .filter {
                it.isNotBlank() && !it.equals("none", ignoreCase = true) && !it.equals("n/a", ignoreCase = true)
            }
    }

    private fun indexOfLabel(text: String, label: String): Int? =
        text.indexOf(label, ignoreCase = true).let { if (it < 0) null else it }
}
