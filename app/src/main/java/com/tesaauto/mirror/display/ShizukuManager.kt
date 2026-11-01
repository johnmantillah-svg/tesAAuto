package com.tesaauto.mirror.display

import android.content.pm.PackageManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.lang.reflect.Method

object ShizukuManager {

    private const val TAG = "ShizukuManager"
    const val SHIZUKU_PERMISSION_REQUEST_CODE = 2001

    data class ShellResult(
        val exitCode: Int,
        val output: String,
        val error: String
    ) {
        val isSuccess: Boolean get() = exitCode == 0
    }

    /**
     * Comprueba si el binder del servicio Shizuku está activo y respondiendo.
     */
    fun isServiceRunning(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (e: Exception) {
            Log.w(TAG, "Error comprobando pingBinder de Shizuku", e)
            false
        }
    }

    /**
     * Comprueba si la aplicación cuenta con los permisos de elevación otorgados por Shizuku.
     */
    fun hasPermission(): Boolean {
        if (!isServiceRunning()) return false
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            Log.w(TAG, "Error comprobando permisos de Shizuku", e)
            false
        }
    }

    /**
     * Solicita la autorización de Shizuku al usuario.
     */
    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_REQUEST_CODE) {
        if (!isServiceRunning()) {
            Log.w(TAG, "No se puede solicitar permisos: Shizuku no está en ejecución")
            return
        }
        try {
            Shizuku.requestPermission(requestCode)
        } catch (e: Exception) {
            Log.e(TAG, "Excepción solicitando permisos a Shizuku", e)
        }
    }

    /**
     * Ejecuta un comando en la shell del sistema con privilegios ADB (UID 2000) a través
     * del mecanismo de ejecución de procesos de Shizuku.
     */
    suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        if (!hasPermission()) {
            return@withContext ShellResult(-1, "", "Permisos de Shizuku no concedidos")
        }

        try {
            // Invocar el método newProcess interno mediante reflexión segura
            val newProcessMethod: Method = Shizuku::class.java.getDeclaredMethod(
                "newProcess",
                Array<String>::class.java,
                Array<String>::class.java,
                String::class.java
            ).apply {
                isAccessible = true
            }

            val cmdArray = arrayOf("sh", "-c", command)
            val process = newProcessMethod.invoke(null, cmdArray, null, null) as Process

            val outputReader = BufferedReader(InputStreamReader(process.inputStream))
            val errorReader = BufferedReader(InputStreamReader(process.errorStream))

            val output = StringBuilder()
            val error = StringBuilder()

            var line: String?
            while (outputReader.readLine().also { line = it } != null) {
                output.append(line).append("\n")
            }
            while (errorReader.readLine().also { line = it } != null) {
                error.append(line).append("\n")
            }

            val exitCode = process.waitFor()
            ShellResult(exitCode, output.toString().trim(), error.toString().trim())
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando comando shell en Shizuku: $command", e)
            ShellResult(-1, "", e.message ?: "Excepción desconocida")
        }
    }

    /**
     * Habilita configuraciones globales en Android para permitir que cualquier aplicación
     * (Google Maps, Waze, etc.) pueda redimensionarse y lanzarse en un display secundario.
     */
    suspend fun configureMultiDisplaySupport(): Boolean {
        val r1 = executeCommand("settings put global force_resizable_activities 1")
        val r2 = executeCommand("settings put global enable_freeform_support 1")
        val r3 = executeCommand("settings put global force_desktop_mode_on_external_displays 1")
        val r4 = executeCommand("settings put global enable_non_resizable_multi_window 1")
        executeCommand("settings put global development_enable_non_resizable_multi_window 1")
        executeCommand("settings put global development_force_resizable_activities 1")
        executeCommand("settings put global stay_on_while_plugged_in 3")
        executeCommand("svc power stayon true")
        executeCommand("wm dismiss-keyguard")
        Log.i(TAG, "Configuración multi-display aplicada: force_resizable=${r1.isSuccess}, freeform=${r2.isSuccess}, desktop_mode=${r3.isSuccess}, non_resizable=${r4.isSuccess}")
        return r1.isSuccess && r2.isSuccess
    }
}
