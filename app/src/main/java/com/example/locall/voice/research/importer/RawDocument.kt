package com.example.locall.voice.research.importer

/**
 * A document as it arrives from a source, before chunking/analysis. Importers produce these; the
 * [com.example.locall.voice.research.pipeline.KnowledgeBuilder] turns them into the persisted
 * knowledge base. [id] must be stable across re-imports so re-importing updates rather than duplicates.
 */
data class RawDocument(
    val id: String,
    val title: String,
    val sourceType: String,
    val sourceRef: String,
    val author: String,
    val createdAtMs: Long,
    val text: String,
)
