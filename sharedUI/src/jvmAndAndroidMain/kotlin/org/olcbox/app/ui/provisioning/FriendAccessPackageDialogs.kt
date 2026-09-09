package org.olcbox.app.ui.provisioning

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.olcbox.app.data.share.FriendAccessPackage
import org.olcbox.app.data.share.FriendAccessPackageCodec
import org.olcbox.app.data.share.FriendAccessPackageSecurity
import org.olcbox.app.provisioning.SelfHostedProvisioner
import org.olcbox.app.provisioning.SelfHostedServer
import org.olcbox.app.provisioning.SshHostIdentity
import org.olcbox.app.ui.components.SensitiveValueVisibilityButton
import org.olcbox.app.ui.localization.AppText as LocalizedText
import java.security.MessageDigest

private val sshFingerprintPattern = Regex("^SHA256:[A-Za-z0-9+/]{43}$")

@Composable
fun FriendAccessPackageCreatorDialog(
    onDismiss: () -> Unit,
    onProvisioned: (vlessUri: String, awgConfig: String, packagePassword: String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val provisioner = remember { SelfHostedProvisioner() }
    var vlessUri by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("root") }
    var expectedFingerprint by rememberSaveable { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var packagePassword by remember { mutableStateOf("") }
    var packagePasswordConfirmation by remember { mutableStateOf("") }
    var vlessUriVisible by remember { mutableStateOf(false) }
    var passwordVisible by remember { mutableStateOf(false) }
    var packagePasswordVisible by remember { mutableStateOf(false) }
    var packagePasswordConfirmationVisible by remember { mutableStateOf(false) }
    var verifiedIdentity by remember { mutableStateOf<SshHostIdentity?>(null) }
    var verifiedServer by remember { mutableStateOf<SelfHostedServer?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var operation by remember { mutableStateOf<Job?>(null) }
    val isWorking = progress != null
    val isVerified = verifiedIdentity != null && verifiedServer != null

    fun clearVerification() {
        verifiedIdentity = null
        verifiedServer = null
        error = null
    }

    fun close() {
        operation?.cancel()
        password = ""
        packagePassword = ""
        packagePasswordConfirmation = ""
        verifiedIdentity = null
        verifiedServer = null
        vlessUriVisible = false
        passwordVisible = false
        packagePasswordVisible = false
        packagePasswordConfirmationVisible = false
        onDismiss()
    }

    fun validateCommonInput(): SelfHostedServer? {
        val sshPort = port.toIntOrNull()
        if (sshPort == null || sshPort !in 1..65535) {
            error = "SSH port must be between 1 and 65535"
            return null
        }
        try {
            FriendAccessPackageCodec.validateVlessUri(vlessUri)
        } catch (failure: IllegalArgumentException) {
            error = failure.message ?: "Enter a valid VLESS link"
            return null
        }
        if (packagePassword.length < 12 || packagePassword != packagePasswordConfirmation) {
            error = "Package passwords must match and contain at least 12 characters"
            return null
        }
        if (!sshFingerprintPattern.matches(expectedFingerprint.trim())) {
            error = "Enter the independently verified SHA256 SSH fingerprint"
            return null
        }
        return SelfHostedServer(host, sshPort, username, password)
    }

    AlertDialog(
        onDismissRequest = { if (!isWorking) close() },
        icon = { Icon(Icons.Outlined.Dns, contentDescription = null) },
        title = { LocalizedText("Package for a friend") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LocalizedText(
                    "The package includes your olcRTC profiles, this VLESS link, and a ready AmneziaWG client profile. SSH access is never included.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = vlessUri,
                    onValueChange = { vlessUri = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { LocalizedText("VLESS link for this friend") },
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
                    onValueChange = { host = it; clearVerification() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking && !isVerified,
                    singleLine = true,
                    label = { LocalizedText("Server IP or domain") }
                )
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter(Char::isDigit); clearVerification() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking && !isVerified,
                    singleLine = true,
                    label = { LocalizedText("SSH port") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; clearVerification() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking && !isVerified,
                    singleLine = true,
                    label = { LocalizedText("SSH login") }
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; clearVerification() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking && !isVerified,
                    singleLine = true,
                    label = { LocalizedText("SSH password") },
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
                            enabled = !isWorking && !isVerified && password.isNotEmpty()
                        )
                    }
                )
                OutlinedTextField(
                    value = expectedFingerprint,
                    onValueChange = { expectedFingerprint = it; clearVerification() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking && !isVerified,
                    singleLine = true,
                    label = { LocalizedText("Expected SSH fingerprint") },
                    textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                )
                LocalizedText(
                    "Obtain this SHA256 fingerprint independently from the server administrator before continuing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                verifiedIdentity?.let { identity ->
                    LocalizedText(
                        "SSH fingerprint matched",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        identity.fingerprint,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    TextButton(
                        enabled = !isWorking,
                        onClick = ::clearVerification
                    ) {
                        LocalizedText("Change server")
                    }
                }
                OutlinedTextField(
                    value = packagePassword,
                    onValueChange = { packagePassword = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { LocalizedText("Package password (12+ characters)") },
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
                    label = { LocalizedText("Repeat package password") },
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
                    LocalizedText(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    LocalizedText(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                LocalizedText(
                    "Send the encrypted package and its password through different private channels.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            if (!isVerified) {
                Button(
                    enabled = !isWorking && vlessUri.isNotBlank() && host.isNotBlank() &&
                        username.isNotBlank() && password.isNotEmpty() &&
                        sshFingerprintPattern.matches(expectedFingerprint.trim()) &&
                        packagePassword.length >= 12 && packagePassword == packagePasswordConfirmation,
                    onClick = {
                        val sshServer = validateCommonInput() ?: return@Button
                        progress = "Verifying SSH server"
                        error = null
                        operation = scope.launch {
                            try {
                                val identity = provisioner.inspectHost(sshServer)
                                val expected = expectedFingerprint.trim().encodeToByteArray()
                                val actual = identity.fingerprint.encodeToByteArray()
                                val matches = MessageDigest.isEqual(expected, actual)
                                expected.fill(0)
                                actual.fill(0)
                                require(matches) { "SSH fingerprint does not match the expected value" }
                                verifiedIdentity = identity
                                verifiedServer = sshServer
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                verifiedIdentity = null
                                verifiedServer = null
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
                    LocalizedText("Verify SSH server")
                }
            } else {
                Button(
                    enabled = !isWorking && vlessUri.trim().startsWith("vless://", ignoreCase = true) &&
                        packagePassword.length >= 12 && packagePassword == packagePasswordConfirmation,
                    onClick = {
                        validateCommonInput() ?: return@Button
                        val sshServer = verifiedServer ?: return@Button
                        val identity = verifiedIdentity ?: return@Button
                        progress = "Preparing AmneziaWG"
                        error = null
                        operation = scope.launch {
                            try {
                                val config = provisioner.provisionAmneziaWg(
                                    server = sshServer,
                                    trustedHost = identity
                                ) { stage ->
                                    scope.launch { progress = stage }
                                }
                                onProvisioned(vlessUri.trim(), config, packagePassword)
                                password = ""
                                packagePassword = ""
                                packagePasswordConfirmation = ""
                                verifiedIdentity = null
                                verifiedServer = null
                                vlessUriVisible = false
                                passwordVisible = false
                                packagePasswordVisible = false
                                packagePasswordConfirmationVisible = false
                            } catch (cancelled: CancellationException) {
                                throw cancelled
                            } catch (failure: Exception) {
                                error = failure.message ?: "Could not prepare AmneziaWG"
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
                    LocalizedText("Trust and create")
                }
            }
        },
        dismissButton = {
            TextButton(onClick = ::close) { LocalizedText(if (isWorking) "Cancel" else "Close") }
        }
    )
}

@Composable
fun FriendAccessPackageInstallDialog(
    encryptedPackage: String,
    onDismiss: () -> Unit,
    onDecoded: (FriendAccessPackage) -> Unit
) {
    val scope = rememberCoroutineScope()
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
        title = { LocalizedText("Encrypted friend package") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LocalizedText(
                    "Enter the password received through a separate private channel. Unified VPN only decrypts and imports the included profiles.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                OutlinedTextField(
                    value = packagePassword,
                    onValueChange = { packagePassword = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isWorking,
                    singleLine = true,
                    label = { LocalizedText("Package password") },
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
                LocalizedText(
                    "The package contains ready olcRTC, VLESS, and AmneziaWG client profiles and never performs SSH setup on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                progress?.let {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    LocalizedText(it, style = MaterialTheme.typography.bodySmall)
                }
                error?.let {
                    LocalizedText(
                        it,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
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
                            val packageValue = withContext(Dispatchers.Default) {
                                FriendAccessPackageCodec.decodeOrNull(
                                    FriendAccessPackageSecurity.decrypt(encryptedPackage, packagePassword)
                                )
                            } ?: error("Friend package is damaged or unsupported")
                            packagePassword = ""
                            packagePasswordVisible = false
                            onDecoded(packageValue)
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
                LocalizedText("Decrypt and import")
            }
        },
        dismissButton = {
            TextButton(onClick = ::close) { LocalizedText(if (isWorking) "Cancel" else "Close") }
        }
    )
}
