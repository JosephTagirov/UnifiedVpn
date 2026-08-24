package org.olcbox.app.ui.provisioning

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import org.olcbox.app.data.share.FriendAccessPackage
import org.olcbox.app.data.share.FriendAccessPackageCodec
import org.olcbox.app.data.share.FriendAccessPackageSecurity
import org.olcbox.app.data.share.FriendAmneziaServer
import org.olcbox.app.provisioning.SelfHostedProvisioner
import org.olcbox.app.provisioning.SelfHostedServer
import org.olcbox.app.provisioning.SshHostIdentity
import org.olcbox.app.ui.components.SensitiveValueVisibilityButton

@Composable
fun FriendAccessPackageCreatorDialog(
    onDismiss: () -> Unit,
    onVerified: (vlessUri: String, server: FriendAmneziaServer, packagePassword: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val provisioner = remember { SelfHostedProvisioner() }
    var vlessUri by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("root") }
    var password by remember { mutableStateOf("") }
    var packagePassword by remember { mutableStateOf("") }
    var packagePasswordConfirmation by remember { mutableStateOf("") }
    var vlessUriVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var packagePasswordVisible by remember { mutableStateOf(false) }
    var packagePasswordConfirmationVisible by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val isWorking = progress != null

    fun close() {
        operation?.cancel()
        password = ""
        packagePassword = ""
        packagePasswordConfirmation = ""
        vlessUriVisible = false
        passwordVisible = false
        packagePasswordVisible = false
        packagePasswordConfirmationVisible = false
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!isWorking) close() },
        icon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
        title = { Text("Package for a friend") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "The package includes your olcRTC profiles, this VLESS link, and SSH access for issuing a new AmneziaWG peer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = vlessUri,
                    onValueChange = { vlessUri = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { Text("VLESS link for this friend") },
                    visualTransformation = if (vlessUriVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        SensitiveValueVisibilityButton(
                            visible = vlessUriVisible,
                            onVisibilityChanged = { vlessUriVisible = it },
                            valueLabel = "VLESS link",
                            enabled = !isWorking && vlessUri.isNotEmpty()
                        )
                    }
                )
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { Text("Server IP or domain") }
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
                OutlinedTextField(
                    value = packagePassword,
                    onValueChange = { packagePassword = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { Text("Package password (12+ characters)") },
                    visualTransformation = if (packagePasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        SensitiveValueVisibilityButton(
                            visible = packagePasswordVisible,
                            onVisibilityChanged = { packagePasswordVisible = it },
                            valueLabel = "package password",
                            enabled = !isWorking && packagePassword.isNotEmpty()
                        )
                    }
                )
                OutlinedTextField(
                    value = packagePasswordConfirmation,
                    onValueChange = { packagePasswordConfirmation = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { Text("Repeat package password") },
                    visualTransformation = if (packagePasswordConfirmationVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        SensitiveValueVisibilityButton(
                            visible = packagePasswordConfirmationVisible,
                            onVisibilityChanged = { packagePasswordConfirmationVisible = it },
                            valueLabel = "repeated package password",
                            enabled = !isWorking && packagePasswordConfirmation.isNotEmpty()
                        )
                    }
                )
                progress?.let {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    "Send the encrypted package and its password through different private channels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                enabled = !isWorking && vlessUri.isNotBlank() && host.isNotBlank() &&
                    username.isNotBlank() && password.isNotEmpty() && packagePassword.length >= 12 &&
                    packagePassword == packagePasswordConfirmation,
                onClick = {
                    val sshPort = port.toIntOrNull()
                    if (sshPort == null || sshPort !in 1..65535) {
                        error = "SSH port must be between 1 and 65535"
                        return@Button
                    }
                    if (!vlessUri.trim().startsWith("vless://", ignoreCase = true)) {
                        error = "Enter a valid VLESS link"
                        return@Button
                    }
                    if (packagePassword.length < 12 || packagePassword != packagePasswordConfirmation) {
                        error = "Package passwords must match and contain at least 12 characters"
                        return@Button
                    }
                    val sshServer = SelfHostedServer(host, sshPort, username, password)
                    progress = "Verifying SSH server"
                    error = null
                    operation = scope.launch {
                        try {
                            val identity = provisioner.inspectHost(sshServer)
                            onVerified(
                                vlessUri.trim(),
                                FriendAmneziaServer(
                                    host = sshServer.host,
                                    port = sshServer.port,
                                    username = sshServer.username,
                                    password = sshServer.password,
                                    hostKeyAlgorithm = identity.algorithm,
                                    hostPublicKey = identity.publicKeyBase64,
                                    hostFingerprint = identity.fingerprint
                                ),
                                packagePassword
                            )
                            password = ""
                            packagePassword = ""
                            packagePasswordConfirmation = ""
                            vlessUriVisible = false
                            passwordVisible = false
                            packagePasswordVisible = false
                            packagePasswordConfirmationVisible = false
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not verify the SSH server"
                        } finally {
                            progress = null
                        }
                    }
                }
            ) {
                if (isWorking) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).size(18.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text("Verify and create")
            }
        },
        dismissButton = {
            TextButton(onClick = ::close) { Text(if (isWorking) "Cancel" else "Close") }
        }
    )
}

@Composable
fun FriendAccessPackageInstallDialog(
    encryptedPackage: String,
    onDismiss: () -> Unit,
    onProvisioned: (FriendAccessPackage, String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val provisioner = remember { SelfHostedProvisioner() }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    var packagePassword by remember { mutableStateOf("") }
    var packagePasswordVisible by remember { mutableStateOf(false) }
    val isWorking = progress != null

    fun close() {
        operation?.cancel()
        packagePassword = ""
        packagePasswordVisible = false
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = { if (!isWorking) close() },
        icon = { Icon(Icons.Outlined.VerifiedUser, contentDescription = null) },
        title = { Text("Encrypted friend package") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Enter the password received through a separate private channel. One action will decrypt the package, verify the SSH key, and create a separate AmneziaWG peer.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = packagePassword,
                    onValueChange = { packagePassword = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { Text("Package password") },
                    visualTransformation = if (packagePasswordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        SensitiveValueVisibilityButton(
                            visible = packagePasswordVisible,
                            onVisibilityChanged = { packagePasswordVisible = it },
                            valueLabel = "package password",
                            enabled = !isWorking && packagePassword.isNotEmpty()
                        )
                    }
                )
                Text(
                    "A separate AmneziaWG key and address will be created for this device. The SSH password is not saved after setup.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                progress?.let {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !isWorking && packagePassword.isNotEmpty(),
                onClick = {
                    error = null
                    progress = "Decrypting package"
                    operation = scope.launch {
                        try {
                            val packageValue = FriendAccessPackageCodec.decodeOrNull(
                                FriendAccessPackageSecurity.decrypt(encryptedPackage, packagePassword)
                            ) ?: error("Friend package is damaged or unsupported")
                            packagePassword = ""
                            packagePasswordVisible = false
                            val server = packageValue.amnezia
                            progress = "Preparing AmneziaWG"
                            val config = provisioner.provisionAmneziaWg(
                                server = SelfHostedServer(
                                    host = server.host,
                                    port = server.port,
                                    username = server.username,
                                    password = server.password
                                ),
                                trustedHost = SshHostIdentity(
                                    algorithm = server.hostKeyAlgorithm,
                                    publicKeyBase64 = server.hostPublicKey,
                                    fingerprint = server.hostFingerprint
                                )
                            ) { stage ->
                                scope.launch { progress = stage }
                            }
                            onProvisioned(packageValue, config)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            error = failure.message ?: "Could not decrypt or install the friend package"
                        } finally {
                            packagePassword = ""
                            packagePasswordVisible = false
                            progress = null
                        }
                    }
                }
            ) {
                Text("Set up and connect")
            }
        },
        dismissButton = {
            TextButton(onClick = ::close) { Text(if (isWorking) "Cancel" else "Close") }
        }
    )
}
