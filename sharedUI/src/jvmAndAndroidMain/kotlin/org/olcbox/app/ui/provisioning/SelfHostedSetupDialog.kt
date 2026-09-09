package org.olcbox.app.ui.provisioning

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import org.olcbox.app.ui.localization.AppText as Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.olcbox.app.provisioning.SelfHostedProvisioner
import org.olcbox.app.provisioning.SelfHostedServer
import org.olcbox.app.provisioning.SshHostIdentity
import org.olcbox.app.ui.components.SensitiveValueVisibilityButton

@Composable
fun SelfHostedSetupDialog(
    onDismiss: () -> Unit,
    onProvisioned: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val provisioner = remember { SelfHostedProvisioner() }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var expectedFingerprint by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var trustedHost by remember { mutableStateOf<SshHostIdentity?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }

    fun credentials(): SelfHostedServer? {
        val sshPort = port.toIntOrNull()
        if (sshPort == null || sshPort !in 1..65535) {
            error = "SSH port must be between 1 and 65535"
            return null
        }
        return SelfHostedServer(host, sshPort, username, password)
    }

    fun close() {
        operation?.cancel()
        password = ""
        passwordVisible = false
        onDismiss()
    }

    val isWorking = progress != null
    val identity = trustedHost
    AlertDialog(
        onDismissRequest = { if (!isWorking) close() },
        icon = {
            Icon(
                imageVector = if (identity == null) Icons.Outlined.Dns else Icons.Outlined.VerifiedUser,
                contentDescription = null
            )
        },
        title = {
            Text(if (identity == null) "Self-hosted AmneziaWG" else "Verify SSH server")
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    identity != null -> {
                        Text(
                            text = "The SSH host key matches the fingerprint obtained independently from your server console or provider.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        SelectionContainer {
                            Text(
                                text = identity.fingerprint,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp),
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text(
                            text = "The host key was read without authenticating. The client private key stays on this device, and the SSH password is used only after this match.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    else -> {
                        OutlinedTextField(
                            value = host,
                            onValueChange = { host = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isWorking,
                            singleLine = true,
                            label = { Text("IP address or domain") }
                        )
                        OutlinedTextField(
                            value = port,
                            onValueChange = { port = it.filter(Char::isDigit); error = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isWorking,
                            singleLine = true,
                            label = { Text("SSH port") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isWorking,
                            singleLine = true,
                            label = { Text("SSH login") }
                        )
                        OutlinedTextField(
                            value = expectedFingerprint,
                            onValueChange = { expectedFingerprint = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isWorking,
                            singleLine = true,
                            label = { Text("Expected SSH SHA256 fingerprint") },
                            supportingText = {
                                Text("Obtain SHA256:... from the server console or hosting provider, not from this connection.")
                            },
                            textStyle = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it; error = null },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isWorking,
                            singleLine = true,
                            label = { Text("SSH password") },
                            visualTransformation = if (passwordVisible) {
                                VisualTransformation.None
                            } else {
                                PasswordVisualTransformation()
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                SensitiveValueVisibilityButton(
                                    visible = passwordVisible,
                                    onVisibilityChanged = { passwordVisible = it },
                                    valueLabel = "SSH password",
                                    enabled = !isWorking && password.isNotEmpty()
                                )
                            }
                        )
                    }
                }

                progress?.let { message ->
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(message, style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { message ->
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        },
        confirmButton = {
            if (identity == null) {
                Button(
                    enabled = !isWorking && host.isNotBlank() && username.isNotBlank() &&
                        expectedFingerprint.isNotBlank() && password.isNotEmpty(),
                    onClick = {
                        val server = credentials() ?: return@Button
                        val expected = expectedFingerprint.trim()
                        if (!SSH_SHA256_FINGERPRINT.matches(expected)) {
                            error = "Enter a complete SHA256 SSH fingerprint from an independent source"
                            return@Button
                        }
                        error = null
                        progress = "Reading SSH host key"
                        operation = scope.launch {
                            try {
                                val inspected = provisioner.inspectHost(server)
                                if (inspected.fingerprint != expected) {
                                    error = "SSH host fingerprint does not match the trusted value"
                                    trustedHost = null
                                } else {
                                    trustedHost = inspected
                                }
                                passwordVisible = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Could not connect to the SSH server"
                            } finally {
                                progress = null
                            }
                        }
                    }
                ) {
                    if (isWorking) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                            strokeWidth = 2.dp
                        )
                    }
                    Text("Read host key")
                }
            } else {
                Button(
                    enabled = !isWorking,
                    onClick = {
                        val server = credentials() ?: return@Button
                        error = null
                        progress = "Preparing server"
                        operation = scope.launch {
                            try {
                                val config = provisioner.provisionAmneziaWg(server, identity) { stage ->
                                    scope.launch { progress = stage }
                                }
                                password = ""
                                passwordVisible = false
                                onProvisioned(config)
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Server setup failed"
                                trustedHost = null
                            } finally {
                                progress = null
                            }
                        }
                    }
                ) {
                    Text("Trust and install")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    if (!isWorking && identity != null) {
                        trustedHost = null
                        error = null
                    } else {
                        close()
                    }
                }
            ) {
                Text(if (isWorking) "Cancel" else if (identity != null) "Back" else "Close")
            }
        }
    )
}

private val SSH_SHA256_FINGERPRINT = Regex("^SHA256:[A-Za-z0-9+/]{43}$")
