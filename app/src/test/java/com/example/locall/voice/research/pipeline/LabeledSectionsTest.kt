package com.example.locall.voice.research.pipeline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the chunk-extraction parsing contract. The `stop_on_blank` fix allows the model to emit all
 * five labelled sections separated by blank lines; these tests confirm every section survives that
 * blank-line separation, and that a truncated (summary-only) output degrades as expected.
 */
class LabeledSectionsTest {

    /** A realistic analysis block: five sections, each separated by a blank line (the shape the model
     * emits once generation is no longer cut off at the first "\n\n"). */
    private val fiveSections = """
        SUMMARY: Acme signed the Q3 supply contract after legal review concluded.
        The deal makes Acme the sole supplier for the next two quarters.

        ENTITIES: Acme, Q3 contract, Legal, Priya

        DECISIONS:
        - Proceed with Acme as the sole supplier
        - Defer the secondary vendor evaluation to Q1

        DATES:
        - 2026-09-30: contract start date
        - 2026-12-31: first review checkpoint

        COMMITMENTS:
        - Priya to send the signed PDF by Friday
        - Legal to file the executed copy next week
    """.trimIndent()

    @Test fun `summary section survives blank-line separation`() {
        val summary = LabeledSections.section(
            fiveSections, "SUMMARY", "ENTITIES", "DECISIONS", "DATES", "COMMITMENTS",
        )
        assertNotNull(summary)
        assertTrue(summary!!.contains("Q3 supply contract"))
        // Must stop at the ENTITIES label, not bleed into later sections.
        assertTrue(!summary.contains("Acme, Q3 contract"))
    }

    @Test fun `entities parse as a comma-separated list`() {
        val entities = LabeledSections.section(
            fiveSections, "ENTITIES", "DECISIONS", "DATES", "COMMITMENTS",
        )!!.split(',').map { it.trim() }.filter { it.isNotBlank() }
        assertEquals(listOf("Acme", "Q3 contract", "Legal", "Priya"), entities)
    }

    @Test fun `decisions parse as bullet list across blank lines`() {
        assertEquals(
            listOf(
                "Proceed with Acme as the sole supplier",
                "Defer the secondary vendor evaluation to Q1",
            ),
            LabeledSections.listSection(fiveSections, "DECISIONS", "DATES", "COMMITMENTS"),
        )
    }

    @Test fun `dates parse as bullet list`() {
        assertEquals(
            listOf("2026-09-30: contract start date", "2026-12-31: first review checkpoint"),
            LabeledSections.listSection(fiveSections, "DATES", "COMMITMENTS"),
        )
    }

    @Test fun `commitments (final section) parse fully and are not truncated`() {
        assertEquals(
            listOf(
                "Priya to send the signed PDF by Friday",
                "Legal to file the executed copy next week",
            ),
            LabeledSections.listSection(fiveSections, "COMMITMENTS"),
        )
    }

    @Test fun `none and n_a list entries are dropped`() {
        val text = """
            DECISIONS:
            - None

            DATES:
            - n/a
        """.trimIndent()
        assertTrue(LabeledSections.listSection(text, "DECISIONS", "DATES", "COMMITMENTS").isEmpty())
        assertTrue(LabeledSections.listSection(text, "DATES", "COMMITMENTS").isEmpty())
    }

    @Test fun `summary-only truncation yields empty trailing sections`() {
        // The pre-fix failure shape: generation stopped at the first blank line after SUMMARY.
        val truncated = "SUMMARY: Acme signed the Q3 contract."
        assertTrue(LabeledSections.listSection(truncated, "DECISIONS", "DATES", "COMMITMENTS").isEmpty())
        assertTrue(LabeledSections.listSection(truncated, "DATES", "COMMITMENTS").isEmpty())
        assertTrue(LabeledSections.listSection(truncated, "COMMITMENTS").isEmpty())
    }

    @Test fun `absent label returns null section`() {
        assertEquals(null, LabeledSections.section("SUMMARY: only this", "ENTITIES", "DECISIONS"))
    }
}
