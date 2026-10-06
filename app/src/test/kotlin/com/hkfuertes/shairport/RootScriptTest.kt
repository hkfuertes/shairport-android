package com.hkfuertes.shairport

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Real shell lifetimes, with fake Wi-Fi/NQPTP commands: never changes the host's radio. */
class RootScriptTest {
    @Test(timeout = 20000)
    fun automaticWifiAndSerializedCleanup() {
        val directory = Files.createTempDirectory("shairport-root-").toFile()
        val bin = directory.resolve("bin").apply { mkdirs() }
        val calls = directory.resolve("wifi-calls")
        fun executable(name: String, body: String) = bin.resolve(name).apply {
            writeText("#!/bin/sh\n$body\n")
            setExecutable(true)
        }
        executable("cmd", "echo \"${'$'}2 ${'$'}3\" >> \"${'$'}TEST_DIRECTORY/wifi-calls\"")
        executable("pidof", "exit 1") // never signal another test or a real daemon
        // Emulate Android mksh's close-on-exec private FDs (caught on the real POCO).
        executable("flock", "exec 4<&- 9>&-; exec /usr/bin/flock \"${'$'}@\"")
        val nqptp = executable("libnqptp.so", "echo ready > \"${'$'}NQPTP_SHM_DIRECTORY/nqptp\"; exec sleep 60")
        val processes = mutableListOf<Process>()
        fun start(airplay2: Boolean): Process = ProcessBuilder(
            "sh", "-c", rootScript(nqptp.path, directory.path, airplay2),
        ).redirectErrorStream(true).apply {
            environment()["PATH"] = bin.path + ":" + environment()["PATH"]
            environment()["TEST_DIRECTORY"] = directory.path
        }.start().also { processes.add(it) }
        fun ready(process: Process): List<String> {
            val lines = mutableListOf<String>()
            val reader = process.inputStream.bufferedReader()
            while (true) {
                val line = checkNotNull(reader.readLine()) { lines.toString() }
                lines.add(line)
                if (line == ROOT_READY) return lines
            }
        }
        fun stop(process: Process) {
            process.outputStream.close()
            assertTrue(process.waitFor(4, TimeUnit.SECONDS))
            assertEquals(0, process.exitValue())
        }
        try {
            // Classic AirPlay/Snapcast automatically protect Wi-Fi without starting NQPTP.
            val wifiOnly = start(false)
            assertTrue(ready(wifiOnly).contains("$WIFI_MARKER up"))
            assertFalse(directory.resolve("nqptp").exists())
            stop(wifiOnly)
            assertEquals(listOf("force-low-latency-mode enabled", "force-low-latency-mode disabled"), calls.readLines())
            calls.delete()

            // AirPlay 2 adds PTP but uses the same automatic Wi-Fi protection.
            val ptp = start(true)
            val markers = ready(ptp)
            assertTrue(markers.contains("$NQPTP_MARKER up"))
            assertTrue(markers.contains("$WIFI_MARKER up"))
            stop(ptp)
            assertEquals(listOf("force-low-latency-mode enabled", "force-low-latency-mode disabled"), calls.readLines())
            calls.delete()

            // A replacement helper must not enable Wi-Fi until the old cleanup finishes.
            val old = start(true)
            ready(old)
            val replacement = start(false)
            stop(old)
            ready(replacement)
            assertEquals(listOf("force-low-latency-mode enabled", "force-low-latency-mode disabled", "force-low-latency-mode enabled"), calls.readLines())
            stop(replacement)

            // Older Androids can fall back to high-performance mode; restore the mode used.
            executable("cmd", "[ \"${'$'}2\" = force-low-latency-mode ] && exit 1; echo \"${'$'}2 ${'$'}3\" >> \"${'$'}TEST_DIRECTORY/wifi-calls\"")
            calls.delete()
            val older = start(false)
            assertTrue(ready(older).contains("$WIFI_MARKER up"))
            stop(older)
            assertEquals(listOf("force-hi-perf-mode enabled", "force-hi-perf-mode disabled"), calls.readLines())
        } finally {
            processes.forEach {
                runCatching { it.outputStream.close() }
                if (!it.waitFor(4, TimeUnit.SECONDS)) it.destroyForcibly()
            }
            directory.deleteRecursively()
        }
    }
}
