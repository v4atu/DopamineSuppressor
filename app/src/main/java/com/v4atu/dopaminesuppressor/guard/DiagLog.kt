package com.v4atu.dopaminesuppressor.guard

import java.io.File

/**
 * TEMP-DIAG: registro de diagnóstico en archivo, para leerlo con adb cuando el logcat ya rotó.
 * Sin APIs de Android. Al llegar a `maxBytes` el archivo pasa a `<nombre>.1` (se descarta el anterior),
 * así que nunca hay más de dos archivos. Un fallo de escritura se ignora: el diagnóstico no debe tumbar el servicio.
 */
class DiagLog(
    private val file: File,
    private val maxBytes: Long,
    private val stamp: () -> String,
) {
    @Synchronized
    fun append(line: String) {
        runCatching {
            file.parentFile?.mkdirs()
            if (file.length() >= maxBytes) {
                val rolled = File(file.path + ".1")
                rolled.delete()
                file.renameTo(rolled)
            }
            file.appendText("${stamp()} $line\n")
        }
    }
}
