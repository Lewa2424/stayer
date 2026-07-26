package com.example.stayer.pathnet.data

import com.example.stayer.pathnet.model.LoadedArea
import com.example.stayer.pathnet.model.ImportedPathGraph
import com.example.stayer.pathnet.model.PathGraph

/**
 * Репозиторий пользовательской сети маршрутов.
 * Repository boundary for persisted user path graphs.
 */
interface PathNetworkRepository {
    /**
     * Загружает текущий сохраненный граф.
     * Loads the currently saved graph.
     */
    suspend fun loadGraph(): PathGraph

    /**
     * Загружает области, отмеченные как загруженные.
     * Loads persisted loaded-area metadata.
     */
    suspend fun loadLoadedAreas(): List<LoadedArea>

    /**
     * Загружает сохраненные распознанные тропинки.
     * Loads persisted imported paths.
     */
    suspend fun loadImportedGraph(): ImportedPathGraph

    /**
     * Сохраняет текущий граф.
     * Persists the current draft graph.
     */
    suspend fun saveGraph(graph: PathGraph)

    /**
     * Сохраняет распознанные тропинки отдельно от пользовательской сети.
     * Persists imported paths separately from the user graph.
     */
    suspend fun saveImportedGraph(graph: ImportedPathGraph)

    /**
     * Сохраняет сведения о загруженных областях.
     * Persists viewport metadata.
     */
    suspend fun saveLoadedAreas(areas: List<LoadedArea>)
}
