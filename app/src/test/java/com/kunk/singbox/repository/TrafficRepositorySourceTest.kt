package com.kunk.singbox.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TrafficRepositorySourceTest {

    @Test
    fun trafficStatsPersistenceUsesNioAtomicMove() {
        val source = File("src/main/java/com/kunk/singbox/repository/TrafficRepository.kt").readText()

        assertTrue(source.contains("Build.VERSION.SDK_INT < Build.VERSION_CODES.O"))
        assertTrue(source.contains("moveTempFileWithNio(tempFile, targetFile, atomic = true)"))
        assertTrue(source.contains("moveTempFileWithFileApi(tempFile, targetFile)"))
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"))
        assertTrue(source.contains("Files.move("))
        assertFalse(source.contains(".copyTo(statsFile"))
        assertFalse(source.contains(".copyTo(dailyFile"))
    }

    @Test
    fun trafficSummaryConsumersCanReuseSingleAggregationPass() {
        val source = File("src/main/java/com/kunk/singbox/repository/TrafficRepository.kt").readText()

        assertTrue(source.contains("fun getTopNodes(summary: TrafficSummary, limit: Int = 10)"))
        assertTrue(source.contains("return getTopNodes(getTrafficSummary(period), limit)"))
        assertTrue(source.contains("fun getNodeTrafficPercentages(summary: TrafficSummary)"))
        assertTrue(source.contains("return getNodeTrafficPercentages(getTrafficSummary(period))"))
        assertFalse(source.contains("} catch (_: Exception) {}"))
    }
}
