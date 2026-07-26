package com.example.stayer.pathnet.model

/**
 * Полный локальный граф пользователя.
 * Full editable local graph stored by the editor.
 */
data class PathGraph(
    val nodes: Map<String, PathNode> = emptyMap(),
    val edges: Map<String, PathEdge> = emptyMap(),
)
