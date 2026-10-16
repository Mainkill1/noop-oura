package com.noop.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.google.crypto.tink.integration.android.AndroidKeystoreKmsClient
import com.noop.R
import com.noop.ble.OuraInstallKeyStore
import com.noop.ingest.OuraKeyXml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Document-picker credential import, shared by dashboard transfer and Advanced ring setup. */
@Composable
internal fun OuraKeyImportButton(onImported: (List<OuraKeyXml.Entry>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val currentCallback by rememberUpdatedState(onImported)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            error = null
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val master = runCatching {
                        AndroidKeystoreKmsClient().getAead("android-keystore://_androidx_security_master_key_")
                    }.getOrNull()
                    checkNotNull(context.contentResolver.openInputStream(uri)).use { OuraKeyXml.read(it, master) }
                }
            }
            busy = false
            result.onSuccess(currentCallback).onFailure {
                error = if ((it as? OuraKeyXml.Failure)?.problem == OuraKeyXml.Problem.ENCRYPTED_UNAVAILABLE)
                    R.string.oura_keys_encrypted else R.string.oura_keys_invalid
            }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
        NoopButton(stringResource(R.string.oura_keys_import), kind = NoopButtonKind.Secondary,
            enabled = !busy, onClick = { picker.launch(arrayOf("*/*")) })
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
        error?.let { Text(stringResource(it), style = NoopType.caption, color = Palette.statusCritical) }
    }
}

/** Keys stay in memory until the user confirms; document output is the only portable cleartext copy. */
@Composable
internal fun OuraKeyTransferDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var imported by remember { mutableStateOf<List<OuraKeyXml.Entry>>(emptyList()) }
    var exported by remember { mutableStateOf<List<OuraKeyXml.Entry>>(emptyList()) }
    var status by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/xml")) { uri ->
        val keys = exported
        exported = emptyList()
        if (uri != null && keys.isNotEmpty()) scope.launch {
            busy = true
            val success = withContext(Dispatchers.IO) {
                runCatching {
                    checkNotNull(context.contentResolver.openOutputStream(uri, "wt")).use {
                        it.write(OuraKeyXml.write(keys).toByteArray(Charsets.UTF_8))
                    }
                }.isSuccess
            }
            status = if (success) R.string.oura_keys_saved else R.string.oura_keys_failed
            busy = false
        }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() },
        title = { Text(stringResource(R.string.oura_keys_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(stringResource(R.string.oura_keys_description), style = NoopType.body, color = Palette.textSecondary)
                Text(stringResource(R.string.oura_keys_sensitive), style = NoopType.caption, color = Palette.textSecondary)
                if (!busy) OuraKeyImportButton { imported = it; status = null }
                if (imported.isNotEmpty()) {
                    Text(stringResource(R.string.oura_keys_review), style = NoopType.caption, color = Palette.textSecondary)
                    imported.forEach { Text(it.deviceId, style = NoopType.bodyNumber, color = Palette.textPrimary) }
                    NoopButton(stringResource(R.string.oura_keys_restore), enabled = !busy, onClick = {
                        scope.launch {
                            busy = true
                            val success = withContext(Dispatchers.IO) {
                                runCatching { OuraInstallKeyStore.importKeys(context, imported) }.getOrDefault(false)
                            }
                            status = if (success) R.string.oura_keys_restored else R.string.oura_keys_failed
                            if (success) imported = emptyList()
                            busy = false
                        }
                    })
                }
                NoopButton(stringResource(R.string.oura_keys_export), kind = NoopButtonKind.Tertiary, enabled = !busy, onClick = {
                    scope.launch {
                        busy = true
                        val keys = withContext(Dispatchers.IO) { runCatching { OuraInstallKeyStore.exportKeys(context) }.getOrNull() }
                        busy = false
                        if (keys == null) status = R.string.oura_keys_failed
                        else if (keys.isEmpty()) status = R.string.oura_keys_empty
                        else { exported = keys; save.launch(OuraKeyXml.FILE_NAME) }
                    }
                })
                if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = Palette.accent)
                status?.let { Text(stringResource(it), style = NoopType.caption, color = Palette.textSecondary) }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(stringResource(R.string.oura_keys_done)) } })
}
