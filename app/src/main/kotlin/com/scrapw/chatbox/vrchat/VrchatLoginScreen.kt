package com.scrapw.chatbox.vrchat

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
 * Three inline states (no dialogs Ã¢â‚¬â€ avoids UX confusion):
 *   1. Credentials entry (username + password)
 *   2. Code entry Ã¢â‚¬â€ email OTP (VRChat sends a 6-digit code, expires in 15 min)
 *   3. Code entry Ã¢â‚¬â€ authenticator TOTP (from auth app, no expiry shown)
 *
 * On success, calls onLoginSuccess(userId, displayName).
 */
@Composable
fun VrchatLoginScreen(
    pendingBanId: String?,
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

    // Step 2 state Ã¢â‚¬â€ inline code entry (replaces the form when needed)
    var awaitingCode by remember { mutableStateOf(false) }
    var isEmailCode by remember { mutableStateOf(false) }  // true = email OTP, false = auth app TOTP
    var codeInput by remember { mutableStateOf("") }
    var codeLoading by remember { mutableStateOf(false) }
    var codeError by remember { mutableStateOf<String?>(null) }

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

            if (!awaitingCode) {
                // Ã¢â€â‚¬Ã¢â€â‚¬ Step 1: Credentials Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
                Text(
                    "Sign in to VRChat",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )
                Text(
                    "VRC-A uses your VRChat account to show your status, detect notifications, and identify you in the moderation system. Your credentials are only used to obtain a session cookie from VRChat's servers - they are never stored elsewhere.",
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
                // Ã¢â€â‚¬Ã¢â€â‚¬ Step 2: Code entry Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬Ã¢â€â‚¬
                Text(
                    if (isEmailCode) "Check your email" else "Authenticator code",
                    style = MaterialTheme.typography.headlineMedium,
                    textAlign = TextAlign.Center
                )

                if (isEmailCode) {
                    // Email OTP Ã¢â‚¬â€ explain it was sent + warn about 15 min expiry
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
                                "Ã¢ÂÂ± This code expires in 15 minutes.",
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

                // Back Ã¢â‚¬â€ let them re-enter credentials if code expired
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
                    Text("Ã¢â€ Â Back to sign in")
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
