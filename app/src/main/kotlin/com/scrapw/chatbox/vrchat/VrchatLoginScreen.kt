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
 * Required setup step — shown when the user has not yet authenticated with VRChat.
 * Handles username/password entry, 2FA (TOTP and email OTP), and surfaces errors.
 *
 * On success, calls onLoginSuccess(userId, displayName) so the caller can:
 *  1. Run Phase 2 ban check (cross-check VRChat ID against bannedIdentifiers)
 *  2. Store vrchatUserId in Firestore user doc
 *  3. Start VrchatPipelineService
 *  4. Proceed to main app
 */
@Composable
fun VrchatLoginScreen(
    pendingBanId: String?,           // non-null if Phase 1 already flagged this device
    onLoginSuccess: (userId: String, displayName: String) -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    // 2FA state
    var show2FADialog by remember { mutableStateOf(false) }
    var is2FAEmail by remember { mutableStateOf(false) }
    var twoFACode by remember { mutableStateOf("") }
    var twoFALoading by remember { mutableStateOf(false) }
    var twoFAError by remember { mutableStateOf<String?>(null) }

    val focusManager = LocalFocusManager.current
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
                is VrchatAuthManager.AuthResult.Requires2FA -> {
                    isLoading = false
                    is2FAEmail = false
                    show2FADialog = true
                }
                is VrchatAuthManager.AuthResult.RequiresEmail2FA -> {
                    isLoading = false
                    is2FAEmail = true
                    show2FADialog = true
                }
                is VrchatAuthManager.AuthResult.Error -> {
                    isLoading = false
                    errorMessage = result.message
                }
            }
        }
    }

    fun do2FA() {
        if (twoFACode.isBlank()) { twoFAError = "Please enter the code."; return }
        twoFALoading = true
        twoFAError = null
        scope.launch {
            when (val result = VrchatAuthManager.verify2FA(ctx, twoFACode.trim(), is2FAEmail)) {
                is VrchatAuthManager.TwoFaResult.Success -> {
                    twoFALoading = false
                    show2FADialog = false
                    onLoginSuccess(result.userId, result.displayName)
                }
                is VrchatAuthManager.TwoFaResult.Error -> {
                    twoFALoading = false
                    twoFAError = result.message
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(Modifier.height(16.dp))

            Text(
                "Sign in to VRChat",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )

            Text(
                "VRC-A uses your VRChat account to show your status, detect notifications, and identify you in the system. Your credentials are only used to get a session cookie — they are never stored or sent anywhere other than VRChat's servers.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(8.dp))

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
                keyboardActions = KeyboardActions(onDone = { focusManager.clearFocus(); doLogin() }),
                trailingIcon = {
                    IconButton(onClick = { passwordVisible = !passwordVisible }) {
                        Icon(
                            if (passwordVisible) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (passwordVisible) "Hide password" else "Show password"
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
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
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

            Text(
                "VRC-A is not affiliated with VRChat. This uses the VRChat web API in accordance with community usage guidelines.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }

    // 2FA dialog
    if (show2FADialog) {
        AlertDialog(
            onDismissRequest = { /* Non-dismissible — user must complete or restart */ },
            title = {
                Text(if (is2FAEmail) "Email Verification" else "Two-Factor Authentication")
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        if (is2FAEmail)
                            "Enter the code sent to your email address."
                        else
                            "Enter the 6-digit code from your authenticator app.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    OutlinedTextField(
                        value = twoFACode,
                        onValueChange = { twoFACode = it.filter { c -> c.isDigit() }.take(6); twoFAError = null },
                        label = { Text("Verification code") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.NumberPassword,
                            imeAction = ImeAction.Done
                        ),
                        keyboardActions = KeyboardActions(onDone = { do2FA() }),
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !twoFALoading
                    )
                    if (twoFAError != null) {
                        Text(
                            twoFAError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { do2FA() },
                    enabled = !twoFALoading && twoFACode.length >= 6
                ) {
                    if (twoFALoading) CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("Verify")
                }
            },
            dismissButton = {
                TextButton(onClick = { show2FADialog = false; twoFACode = ""; twoFAError = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
