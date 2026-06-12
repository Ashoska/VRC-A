package com.vrca.vrchat

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * VrchatLoginScreen
 *
 * Three inline states (no dialogs - avoids UX confusion):
 *   1. Credentials entry (username + password)
 *   2. Code entry - email OTP (VRChat sends a 6-digit code, expires in 15 min)
 *   3. Code entry - authenticator TOTP (from auth app, no expiry shown)
 *
 * On success, calls onLoginSuccess(userId, displayName).
 */
@Composable
fun VrchatLoginScreen(
    pendingBanId: String?,
    onCancel: (() -> Unit)? = null,
    onLoginSuccess: (userId: String, displayName: String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    // Step 1 state
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // Step 2 state - inline code entry (replaces the form when needed)
    var awaitingCode by remember { mutableStateOf(false) }
    var isEmailCode by remember { mutableStateOf(false) }  // true = email OTP, false = auth app TOTP
    var codeInput by remember { mutableStateOf("") }
    var codeLoading by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf<String?>(null) }

    // VRChat platform status (docs/ui-revamp.md, login polish): the pipeline's
    // status poll only runs post-login, so every login surface does its own
    // one-shot fetch — and re-fetches on a FAILED attempt so a mid-outage user
    // learns "it's not you" reactively. Lives here (not in the onboarding
    // wrapper) so the Settings / VRChat-tab takeovers get it too.
    var statusWarning by remember {
        mutableStateOf<com.vrca.ui.onboarding.VrchatStatusWarning?>(null)
    }
    LaunchedEffect(Unit) { statusWarning = com.vrca.ui.onboarding.fetchVrchatStatusWarning() }

    val codeFocus = remember { FocusRequester() }
    val passwordFocus = remember { FocusRequester() }

    fun doLogin() {
        if (username.isBlank() || password.isBlank()) {
            errorMessage = "Please enter your VRChat username and password."
            return
        }
        isLoading = true
        errorMessage = null
        scope.launch {
            when (val result = VrchatAuthManager.login(ctx, username.trim(), password)) {
                is VrchatAuthManager.AuthResult.Success -> {
                    isLoading = false
                    onLoginSuccess(result.userId, result.displayName)
                }
                is VrchatAuthManager.AuthResult.RequiresEmail2FA -> {
                    isLoading = false
                    isEmailCode = true
                    awaitingCode = true
                    codeInput = ""
                    codeError = null
                }
                is VrchatAuthManager.AuthResult.Requires2FA -> {
                    isLoading = false
                    isEmailCode = false
                    awaitingCode = true
                    codeInput = ""
                    codeError = null
                }
                is VrchatAuthManager.AuthResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                    // A failure during a platform outage isn't the user's fault —
                    // surface/refresh the warning so they know.
                    statusWarning = com.vrca.ui.onboarding.fetchVrchatStatusWarning()
                }
            }
        }
    }

    fun doVerify() {
        if (codeInput.length < 6) { codeError = "Code must be 6 digits."; return }
        codeLoading = true
        codeError = null
        scope.launch {
            when (val result = VrchatAuthManager.verify2FA(ctx, codeInput.trim(), isEmailCode)) {
                is VrchatAuthManager.TwoFaResult.Success -> {
                    codeLoading = false
                    onLoginSuccess(result.userId, result.displayName)
                }
                is VrchatAuthManager.TwoFaResult.Error -> {
                    codeLoading = false
                    codeError = result.message
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            statusWarning?.let { w ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "VRChat is having platform issues right now (${w.description}). " +
                            "Login may fail, it's not you. You can retry later.",
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            if (!awaitingCode) {
                // -- Step 1: Credentials ----------------------------------
                Text(
                    "Sign in to VRChat",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Your VRChat account powers status, notifications, and moderation identity. Credentials are only used to get a session cookie and are never stored anywhere else.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it; errorMessage = null },
                    label = { Text("VRChat username or email") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(onNext = { passwordFocus.requestFocus() }),
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading
                )

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it; errorMessage = null },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) VisualTransformation.None
                                           else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(); doLogin()
                    }),
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                if (passwordVisible) Icons.Filled.VisibilityOff
                                else Icons.Filled.Visibility,
                                contentDescription = null
                            )
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(passwordFocus),
                    enabled = !isLoading
                )

                if (errorMessage != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer)
                    ) {
                        Text(
                            errorMessage ?: "",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                Button(
                    onClick = { focusManager.clearFocus(); doLogin() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isLoading && username.isNotBlank() && password.isNotBlank()
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Sign In")
                    }
                }

            } else {
                // -- Step 2: Code entry ----------------------------------
                Text(
                    if (isEmailCode) "Check your email" else "Authenticator code",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )

                if (isEmailCode) {
                    // Email OTP - explain it was sent + warn about 15 min expiry
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(
                            Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "VRChat sent a 6-digit code to your email address.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                "This code expires in 15 minutes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                } else {
                    Text(
                        "Enter the 6-digit code from your authenticator app.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                Text(
                    "VRChat will remember this device, so you won't be asked again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(4.dp))

                OutlinedTextField(
                    value = codeInput,
                    onValueChange = {
                        codeInput = it.filter { c -> c.isDigit() }.take(6)
                        codeError = null
                    },
                    label = { Text("6-digit code") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        focusManager.clearFocus(); doVerify()
                    }),
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(codeFocus),
                    enabled = !codeLoading,
                    isError = codeError != null,
                    supportingText = if (codeError != null) ({
                        Text(codeError ?: "", color = MaterialTheme.colorScheme.error)
                    }) else null
                )

                Button(
                    onClick = { focusManager.clearFocus(); doVerify() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !codeLoading && codeInput.length == 6
                ) {
                    if (codeLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Verify")
                    }
                }

                // Back - let them re-enter credentials if code expired
                TextButton(
                    onClick = {
                        awaitingCode = false
                        codeInput = ""
                        codeError = null
                        errorMessage = if (isEmailCode)
                            "Code expired? Sign in again to receive a new one."
                        else null
                    }
                ) {
                    Text("<- Back to sign in")
                }
            }

            // Cancel lives INSIDE the screen content (Settings / VRChat-tab
            // re-login takeovers used to pin it to a bar above the screen,
            // which clashed with the app bar). Hidden during onboarding, where
            // the login step is a hard gate (onCancel = null).
            if (onCancel != null) {
                TextButton(onClick = onCancel, modifier = Modifier.fillMaxWidth()) {
                    Text("Cancel")
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "VRC-A is not affiliated with VRChat. This uses the VRChat web API in accordance with community usage guidelines.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }

    // Auto-focus code field when it appears
    LaunchedEffect(awaitingCode) {
        if (awaitingCode) {
            try { codeFocus.requestFocus() } catch (_: Exception) { }
        }
    }
}
