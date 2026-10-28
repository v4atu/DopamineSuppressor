package com.v4atu.dopaminesuppressor.ui

import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.v4atu.dopaminesuppressor.R
import com.v4atu.dopaminesuppressor.data.AppLimit
import com.v4atu.dopaminesuppressor.data.LimitsRepository
import com.v4atu.dopaminesuppressor.data.UsageSeeder
import java.time.LocalDate
import java.time.ZoneId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : AppCompatActivity() {

    private class InstalledApp(val packageName: String, val label: String)

    private lateinit var repository: LimitsRepository
    private lateinit var adapter: AppsAdapter

    private var apps: List<InstalledApp> = emptyList()
    private var limits: Map<String, AppLimit> = emptyMap()
    private var usedToday: Map<String, Long>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        repository = LimitsRepository(this)
        adapter = AppsAdapter(packageManager, lifecycleScope, ::editLimit)
        findViewById<RecyclerView>(R.id.apps_list).also {
            it.layoutManager = LinearLayoutManager(this)
            it.adapter = adapter
        }

        setUpPermissionRows()

        lifecycleScope.launch {
            apps = withContext(Dispatchers.IO) { loadLauncherApps() }
            render()
            loadUsage()
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                repository.limits.collect {
                    limits = it
                    render()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionRows()
        if (apps.isNotEmpty()) lifecycleScope.launch { loadUsage() }
    }

    private fun setUpPermissionRows() {
        bindPermissionRow(R.id.perm_usage, R.string.perm_usage_title, Settings.ACTION_USAGE_ACCESS_SETTINGS)
        bindPermissionRow(R.id.perm_a11y, R.string.perm_a11y_title, Settings.ACTION_ACCESSIBILITY_SETTINGS)
        bindPermissionRow(
            R.id.perm_battery,
            R.string.perm_battery_title,
            Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS,
        )
    }

    private fun bindPermissionRow(rowId: Int, titleRes: Int, settingsAction: String) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.perm_title).setText(titleRes)
        row.findViewById<Button>(R.id.perm_button).setOnClickListener {
            startActivity(Intent(settingsAction))
        }
    }

    private fun refreshPermissionRows() {
        val usage = Permissions.hasUsageAccess(this)
        val a11y = Permissions.isAccessibilityEnabled(this)
        setRowStatus(R.id.perm_usage, usage)
        setRowStatus(R.id.perm_a11y, a11y)
        setRowStatus(R.id.perm_battery, Permissions.isIgnoringBatteryOptimizations(this))
        // La ayuda sobre «configuración restringida» solo importa mientras falte la accesibilidad.
        findViewById<View>(R.id.perm_hint).visibility = if (a11y) View.GONE else View.VISIBLE
    }

    private fun setRowStatus(rowId: Int, granted: Boolean) {
        val row = findViewById<View>(rowId)
        row.findViewById<TextView>(R.id.perm_status)
            .setText(if (granted) R.string.perm_granted else R.string.perm_missing)
        row.findViewById<View>(R.id.perm_button).visibility = if (granted) View.GONE else View.VISIBLE
    }

    @Suppress("DEPRECATION") // La sobrecarga con ResolveInfoFlags solo existe desde API 33.
    private fun loadLauncherApps(): List<InstalledApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            packageManager.queryIntentActivities(intent, 0)
        }
        return resolved
            .map { InstalledApp(it.activityInfo.packageName, it.loadLabel(packageManager).toString()) }
            .filter { it.packageName != packageName }
            .distinctBy { it.packageName }
    }

    /** Uso de hoy de todas las apps de la lista; solo si ya se concedió el acceso a datos de uso. */
    private suspend fun loadUsage() {
        if (!Permissions.hasUsageAccess(this)) {
            usedToday = null
            render()
            return
        }
        val usm = getSystemService(UsageStatsManager::class.java)
        val all = apps.map { it.packageName }.toSet()
        usedToday = withContext(Dispatchers.IO) {
            val begin = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
            UsageSeeder(usm).read(begin, System.currentTimeMillis(), all).totals
        }
        render()
    }

    /** Las apps con límite van primero; dentro de cada grupo, orden alfabético. */
    private fun render() {
        val used = usedToday
        val rows = apps
            // UsageSeeder solo devuelve apps con uso: si ya se leyó, una app ausente usó 0.
            .map { AppRow(it.packageName, it.label, limits[it.packageName], used?.let { u -> u[it.packageName] ?: 0L }) }
            .sortedWith(compareBy({ it.limit == null }, { it.label.lowercase() }))
        adapter.submitList(rows)
    }

    private fun editLimit(row: AppRow) {
        LimitEditorDialog.show(
            context = this,
            row = row,
            onSave = { limit -> lifecycleScope.launch { repository.save(limit) } },
            onRemove = { lifecycleScope.launch { repository.remove(row.packageName) } },
        )
    }
}
