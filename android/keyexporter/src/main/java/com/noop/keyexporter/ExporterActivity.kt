package com.noop.keyexporter

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import com.noop.ingest.OuraKeyXml
import java.util.concurrent.TimeUnit

/** Explicit, local-only root export; the source app and ring are never modified. */
class ExporterActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var source: EditText
    private lateinit var export: Button
    private var pending: String? = null
    @Volatile private var runningProcess: Process? = null
    @Volatile private var cancelled = false

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val padding = obtainStyledAttributes(intArrayOf(android.R.attr.listPreferredItemPaddingStart)).let {
            try { it.getDimensionPixelSize(0, 0) } finally { it.recycle() }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        fun text(id: Int) = TextView(this).apply {
            setText(id)
            setTextAppearance(android.R.style.TextAppearance_Material_Body1)
            column.addView(this)
        }
        text(R.string.app_name).setTextAppearance(android.R.style.TextAppearance_Material_Headline)
        text(R.string.explanation)
        text(R.string.package_label)
        source = EditText(this).apply {
            val installed = listOf("com.noop.whoop", "com.noop.whoop.debug", "com.noop.whoop.staging")
                .firstOrNull { runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess }
            setText(installed ?: "com.noop.whoop")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine(true)
            column.addView(this)
        }
        text(R.string.sensitive)
        export = Button(this).apply {
            setText(R.string.export)
            setOnClickListener { beginExport() }
            column.addView(this)
        }
        status = text(R.string.package_label).apply { text = "" }
        setContentView(ScrollView(this).apply { addView(column) })
    }

    private fun beginExport() {
        val packageName = source.text.toString().trim()
        if (!packageName.matches(Regex("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z0-9_]+)+"))) {
            status.setText(R.string.failed)
            return
        }
        export.isEnabled = false
        status.setText(R.string.working)
        Thread {
            val xml = runCatching {
                val info = packageManager.getApplicationInfo(packageName, 0)
                val prefs = "${info.dataDir}/shared_prefs/${OuraKeyXml.FILE_NAME}"
                fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
                val command = "CLASSPATH=${quote(applicationInfo.sourceDir)} /system/bin/app_process /system/bin " +
                    "com.noop.keyexporter.RootKeyExportMain ${quote(prefs)} ${info.uid}"
                check(!cancelled)
                val process = ProcessBuilder("su", "-c", command).start()
                runningProcess = process
                if (cancelled) { process.destroyForcibly(); error("Export cancelled") }
                // Drain both bounded pipes concurrently so root errors cannot deadlock the UI.
                val errors = Thread {
                    process.errorStream.bufferedReader().useLines { lines ->
                        lines.take(16).forEach { line ->
                            // Only our fixed diagnostic vocabulary is eligible for logging.
                            if (line.matches(Regex("Oura key export failed at (arguments|read_preferences|source_identity|keystore_provider|keystore_access|decode_preferences) \\([A-Za-z0-9]+\\)")))
                                android.util.Log.e("OuraKeyExporter", line)
                            else if (line.trim() == "Permission denied")
                                android.util.Log.e("OuraKeyExporter", "Root permission denied")
                        }
                    }
                }.apply { start() }
                val killer = Thread {
                    if (!process.waitFor(90, TimeUnit.SECONDS)) process.destroyForcibly()
                }.apply { start() }
                val entries = process.inputStream.use { OuraKeyXml.read(it) }
                require(process.waitFor() == 0)
                errors.join()
                killer.join()
                OuraKeyXml.write(entries)
            }.also {
                runningProcess?.destroyForcibly()
                runningProcess = null
            }.getOrNull()
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                export.isEnabled = true
                if (xml == null) status.setText(R.string.failed)
                else {
                    pending = xml
                    startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "application/xml"
                        putExtra(Intent.EXTRA_TITLE, OuraKeyXml.FILE_NAME)
                    }, 1)
                }
            }
        }.start()
    }

    override fun onDestroy() {
        cancelled = true
        runningProcess?.destroyForcibly()
        runningProcess = null
        pending = null
        super.onDestroy()
    }

    @Deprecated("Platform document result callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        val xml = pending
        pending = null
        if (resultCode != RESULT_OK || data?.data == null) {
            status.setText(R.string.cancelled)
            return
        }
        val uri = data.data!!
        Thread {
            val saved = runCatching {
                checkNotNull(xml)
                checkNotNull(contentResolver.openOutputStream(uri, "wt")).use { it.write(xml.toByteArray(Charsets.UTF_8)) }
            }.isSuccess
            runOnUiThread { if (!isDestroyed) status.setText(if (saved) R.string.saved else R.string.save_failed) }
        }.start()
    }
}
