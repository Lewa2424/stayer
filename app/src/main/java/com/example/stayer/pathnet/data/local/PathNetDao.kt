package com.example.stayer.pathnet.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * DAO локальной сети.
 * DAO for the editable path network database.
 */
@Dao
interface PathNetDao {
    /**
     * Загружает все узлы сети.
     * Returns all stored nodes.
     */
    @Query("SELECT * FROM path_nodes")
    suspend fun getNodes(): List<PathNodeEntity>

    /**
     * Загружает все сегменты сети.
     * Returns all stored edges.
     */
    @Query("SELECT * FROM path_edges")
    suspend fun getEdges(): List<PathEdgeEntity>

    /**
     * Загружает метаданные подгруженных областей.
     * Returns stored loaded-area records.
     */
    @Query("SELECT * FROM loaded_areas")
    suspend fun getLoadedAreas(): List<LoadedAreaEntity>

    /**
     * Загружает узлы импортированных тропинок.
     * Returns stored imported path nodes.
     */
    @Query("SELECT * FROM imported_path_nodes")
    suspend fun getImportedPathNodes(): List<ImportedPathNodeEntity>

    /**
     * Загружает импортированные тропинки.
     * Returns stored imported path ways.
     */
    @Query("SELECT * FROM imported_path_ways")
    suspend fun getImportedPathWays(): List<ImportedWayEntity>

    /**
     * Полностью заменяет граф.
     * Replaces the persisted graph contents.
     */
    @Transaction
    suspend fun replaceGraph(
        nodes: List<PathNodeEntity>,
        edges: List<PathEdgeEntity>,
    ) {
        clearNodes()
        clearEdges()
        insertNodes(nodes)
        insertEdges(edges)
    }

    /**
     * Полностью заменяет кэш импортированных тропинок.
     * Replaces the persisted imported-path cache.
     */
    @Transaction
    suspend fun replaceImportedGraph(
        nodes: List<ImportedPathNodeEntity>,
        ways: List<ImportedWayEntity>,
    ) {
        clearImportedPathNodes()
        clearImportedPathWays()
        insertImportedPathNodes(nodes)
        insertImportedPathWays(ways)
    }

    /**
     * Сохраняет области загрузки.
     * Inserts or updates loaded areas.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLoadedAreas(areas: List<LoadedAreaEntity>)

    /**
     * Сохраняет узлы импортированных тропинок.
     * Inserts imported path nodes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedPathNodes(nodes: List<ImportedPathNodeEntity>)

    /**
     * Сохраняет импортированные тропинки.
     * Inserts imported path ways.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertImportedPathWays(ways: List<ImportedWayEntity>)

    /**
     * Сохраняет узлы сети.
     * Inserts path nodes.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNodes(nodes: List<PathNodeEntity>)

    /**
     * Сохраняет сегменты сети.
     * Inserts path edges.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEdges(edges: List<PathEdgeEntity>)

    /**
     * Удаляет все узлы.
     * Deletes all nodes.
     */
    @Query("DELETE FROM path_nodes")
    suspend fun clearNodes()

    /**
     * Удаляет все сегменты.
     * Deletes all edges.
     */
    @Query("DELETE FROM path_edges")
    suspend fun clearEdges()

    /**
     * Удаляет все области.
     * Deletes all loaded-area metadata.
     */
    @Query("DELETE FROM loaded_areas")
    suspend fun clearLoadedAreas()

    /**
     * Удаляет все узлы импортированных тропинок.
     * Deletes all imported path nodes.
     */
    @Query("DELETE FROM imported_path_nodes")
    suspend fun clearImportedPathNodes()

    /**
     * Удаляет все импортированные тропинки.
     * Deletes all imported path ways.
     */
    @Query("DELETE FROM imported_path_ways")
    suspend fun clearImportedPathWays()
}
