package com.v4atu.dopaminesuppressor.guard

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DiagLogTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun log(maxBytes: Long = 1_000L, file: File = File(folder.root, "guard-diag.log")) =
        DiagLog(file, maxBytes) { "12:00:00.000" }

    private val main get() = File(folder.root, "guard-diag.log")
    private val rolled get() = File(folder.root, "guard-diag.log.1")

    @Test
    fun `append crea el archivo con la hora y el mensaje`() {
        log().append("event pkg=a")
        assertEquals(listOf("12:00:00.000 event pkg=a"), main.readLines())
    }

    @Test
    fun `append conserva el orden de las lineas`() {
        val l = log()
        l.append("uno")
        l.append("dos")
        assertEquals(listOf("12:00:00.000 uno", "12:00:00.000 dos"), main.readLines())
    }

    @Test
    fun `al superar el tamano el archivo se rota y el nuevo empieza vacio`() {
        val l = log(maxBytes = 40L)
        l.append("primera linea larga")
        l.append("segunda linea larga")
        l.append("tercera")
        assertTrue(rolled.exists())
        assertEquals(listOf("12:00:00.000 primera linea larga", "12:00:00.000 segunda linea larga"), rolled.readLines())
        assertEquals(listOf("12:00:00.000 tercera"), main.readLines())
    }

    @Test
    fun `solo se conservan dos archivos y la rotacion vieja se descarta`() {
        val l = log(maxBytes = 20L)
        repeat(10) { l.append("linea numero $it") }
        val files = folder.root.listFiles().orEmpty().map { it.name }.sorted()
        assertEquals(listOf("guard-diag.log", "guard-diag.log.1"), files)
        assertTrue(main.readText().contains("linea numero 9"))
        assertFalse((main.readText() + rolled.readText()).contains("linea numero 0"))
    }

    @Test
    fun `un fallo de escritura no lanza excepcion`() {
        val notADir = folder.newFile("bloqueado")
        log(file = File(notADir, "guard-diag.log")).append("no debe romper el servicio")
    }
}
