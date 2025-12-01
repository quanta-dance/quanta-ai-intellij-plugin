// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties

data class SearchResult(val id: String, val score: Double, val metadata: Map<String, String>)

@Service(Service.Level.PROJECT)
class SQLiteVectorStore(private val project: Project) {
    private val dbFile: File
    private val conn: Connection

    init {
        val base = project.basePath ?: project.name
        val dir = File(base, ".idea")
        if (!dir.exists()) dir.mkdirs()
        dbFile = File(dir, "quantadance-embeddings.db")
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            Class.forName("org.sqlite.JDBC")
        } catch (e: Throwable) {
            // Log but continue; if driver missing, DriverManager will throw below
            QDLog.warn(logger, { "SQLite JDBC driver not found on classpath" }, e)
        }
        conn = DriverManager.getConnection(url)
        createTables()
    }

    private fun createTables() {
        conn.createStatement().use { stmt ->
            stmt.execute(
                """
                CREATE TABLE IF NOT EXISTS embeddings (
                    id TEXT PRIMARY KEY,
                    project TEXT,
                    vector BLOB,
                    metadata TEXT,
                    chunk_hash TEXT,
                    created_at INTEGER
                );
                """.trimIndent(),
            )
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_project ON embeddings(project);")
        }
    }

    fun upsert(
        id: String,
        vector: FloatArray,
        metadata: Map<String, String>,
        chunkHash: String? = null,
    ) {
        val metadataJson = mapToJson(metadata)
        val vectorBlob = floatArrayToBytes(vector)
        val sql = "REPLACE INTO embeddings(id, project, vector, metadata, chunk_hash, created_at) VALUES(?,?,?,?,?,?)"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id)
            val projectKey = project.basePath ?: project.name
            ps.setString(2, projectKey)
            ps.setBytes(3, vectorBlob)
            ps.setString(4, metadataJson)
            ps.setString(5, chunkHash)
            ps.setLong(6, System.currentTimeMillis())
            ps.executeUpdate()
        }
    }

    fun getChunkHash(id: String): String? {
        val sql = "SELECT chunk_hash FROM embeddings WHERE id = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, id)
            val rs = ps.executeQuery()
            if (rs.next()) return rs.getString("chunk_hash")
        }
        return null
    }

    fun deleteByProject(projectKey: String) {
        val sql = "DELETE FROM embeddings WHERE project = ?"
        conn.prepareStatement(sql).use { ps ->
            ps.setString(1, projectKey)
            ps.executeUpdate()
        }
    }

    fun search(
        queryVector: FloatArray,
        topK: Int = 10,
        projectKey: String? = null,
    ): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val sql =
            if (projectKey != null) {
                "SELECT id, vector, metadata FROM embeddings WHERE project = ?"
            } else {
                "SELECT id, vector, metadata FROM embeddings"
            }
        conn.prepareStatement(sql).use { ps ->
            if (projectKey != null) ps.setString(1, projectKey)
            val rs = ps.executeQuery()
            while (rs.next()) {
                val id = rs.getString("id")
                val blob = rs.getBytes("vector")
                val stored = bytesToFloatArray(blob)
                val score = cosineSimilarity(queryVector, stored)
                val metadata = jsonToMap(rs.getString("metadata"))
                results.add(SearchResult(id, score, metadata))
            }
        }
        return results.sortedByDescending { it.score }.take(topK)
    }

    private fun mapToJson(map: Map<String, String>): String {
        val props = Properties()
        map.forEach { (k, v) -> props.setProperty(k, v) }
        val out = StringBuilder()
        props.forEach { k, v -> out.append("$k=${v}\\n") }
        return out.toString()
    }

    private fun jsonToMap(s: String?): Map<String, String> {
        if (s == null) return emptyMap()
        val props = Properties()
        props.load(s.byteInputStream())
        val map = mutableMapOf<String, String>()
        props.forEach { k, v -> map[k.toString()] = v.toString() }
        return map
    }

    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val bb = ByteBuffer.allocate(4 * floats.size)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        floats.forEach { bb.putFloat(it) }
        return bb.array()
    }

    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val bb = ByteBuffer.wrap(bytes)
        bb.order(ByteOrder.LITTLE_ENDIAN)
        val fa = FloatArray(bytes.size / 4)
        var i = 0
        while (bb.remaining() >= 4) {
            fa[i++] = bb.getFloat()
        }
        return fa
    }

    private fun cosineSimilarity(
        a: FloatArray,
        b: FloatArray,
    ): Double {
        val len = minOf(a.size, b.size)
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in 0 until len) {
            dot += (a[i] * b[i]).toDouble()
            na += (a[i] * a[i]).toDouble()
            nb += (b[i] * b[i]).toDouble()
        }
        if (na == 0.0 || nb == 0.0) return 0.0
        return dot / (Math.sqrt(na) * Math.sqrt(nb))
    }

    fun computeHash(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(content.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { String.format("%02x", it) }
    }

    companion object {
        private val logger = Logger.getInstance(SQLiteVectorStore::class.java)

        fun getInstance(project: Project): SQLiteVectorStore = project.service()
    }
}
