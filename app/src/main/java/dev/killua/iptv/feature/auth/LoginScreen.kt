package dev.killua.iptv.feature.auth

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Label
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.sensitiveContent
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.killua.iptv.ui.theme.Night
import dev.killua.iptv.ui.theme.Violet

@Composable
fun LoginRoute(viewModel: LoginViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LoginScreen(
        state = state,
        onLoginMethodChange = viewModel::selectLoginMethod,
        onPlaylistNameChange = viewModel::setPlaylistName,
        onServerChange = viewModel::setServer,
        onUsernameChange = viewModel::setUsername,
        onPasswordChange = viewModel::setPassword,
        onM3uUrlChange = viewModel::setProviderLink,
        onPlaylistUrlChange = viewModel::setPlaylistUrl,
        onTogglePassword = viewModel::togglePasswordVisibility,
        onToggleM3uUrl = viewModel::toggleProviderLinkVisibility,
        onTestConnection = viewModel::testConnection,
        // A playlist has nothing to authenticate, so it takes its own path rather than being
        // squeezed through the one that turns fields into server-plus-credentials.
        onConnect = {
            if (state.loginMethod == LoginMethod.Playlist) {
                viewModel.connectPlaylist()
            } else {
                viewModel.connect()
            }
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    state: LoginUiState,
    onLoginMethodChange: (LoginMethod) -> Unit,
    onPlaylistNameChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onM3uUrlChange: (String) -> Unit,
    onPlaylistUrlChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
    onToggleM3uUrl: () -> Unit,
    onTestConnection: () -> Unit,
    onConnect: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    colors = listOf(Violet.copy(alpha = 0.28f), Night, Night),
                    radius = 1_100f,
                ),
            ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .navigationBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 30.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Surface(
                modifier = Modifier.padding(bottom = 22.dp),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.padding(15.dp),
                )
            }
            Text(
                text = "Your television,\nbeautifully organized.",
                color = Color.White,
                style = MaterialTheme.typography.displaySmall.copy(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.95f),
                        offset = Offset(0f, 3f),
                        blurRadius = 10f,
                    ),
                ),
            )
            Spacer(Modifier.height(10.dp))
            Text(
                text = "Connect your legally authorized Xtream account with credentials or an Xtream M3U link. Credentials are stored only on this device and sent only to your configured provider.",
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFFE3DFEC),
            )
            Spacer(Modifier.height(24.dp))

            Text(
                text = "Sign in with",
                style = MaterialTheme.typography.labelLarge,
                color = Color.White,
            )
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = state.loginMethod == LoginMethod.Credentials,
                    onClick = { onLoginMethodChange(LoginMethod.Credentials) },
                    enabled = !state.isConnecting,
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    label = { Text("Account") },
                )
                SegmentedButton(
                    selected = state.loginMethod == LoginMethod.ProviderLink,
                    onClick = { onLoginMethodChange(LoginMethod.ProviderLink) },
                    enabled = !state.isConnecting,
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    // Was "M3U URL", which is what it serves and not what it is. It means the
                    // `get.php` line a provider issues, and the old name sent an actual `.m3u`
                    // address into the wrong field.
                    label = { Text("Provider link") },
                )
                SegmentedButton(
                    selected = state.loginMethod == LoginMethod.Playlist,
                    onClick = { onLoginMethodChange(LoginMethod.Playlist) },
                    enabled = !state.isConnecting,
                    shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    label = { Text("Playlist") },
                )
            }
            Spacer(Modifier.height(18.dp))

            when (state.loginMethod) {
                LoginMethod.Credentials -> CredentialFields(
                    state = state,
                    onPlaylistNameChange = onPlaylistNameChange,
                    onServerChange = onServerChange,
                    onUsernameChange = onUsernameChange,
                    onPasswordChange = onPasswordChange,
                    onTogglePassword = onTogglePassword,
                )
                LoginMethod.ProviderLink -> M3uUrlField(
                    state = state,
                    onM3uUrlChange = onM3uUrlChange,
                    onToggleM3uUrl = onToggleM3uUrl,
                )
                // Not masked, unlike the provider link above. A public playlist address is not a
                // secret, and hiding it makes the one thing that goes wrong here - a typo - harder
                // to spot. A provider's own playlist address *is* a credential, and the field
                // beside this one is where that belongs.
                LoginMethod.Playlist -> OutlinedTextField(
                    value = state.playlistUrl,
                    onValueChange = onPlaylistUrlChange,
                    label = { Text("Playlist address") },
                    supportingText = {
                        Text(
                            "An .m3u address. Channels only - a playlist has no films, series or " +
                                "guide, so those stay away while one is open.",
                        )
                    },
                    singleLine = true,
                    enabled = !state.isConnecting,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.isCleartext) {
                StatusCard(
                    text = "This provider uses unencrypted HTTP. Your credentials can be visible on the network.",
                    color = MaterialTheme.colorScheme.error,
                )
            }
            state.errorMessage?.let {
                StatusCard(text = it, color = MaterialTheme.colorScheme.error)
            }
            if (state.connectionTested) {
                StatusCard(
                    text = "Connection verified${state.accountSummary?.let { summary -> " · $summary" }.orEmpty()}",
                    color = Color(0xFF34D399),
                    success = true,
                )
            }

            Spacer(Modifier.height(22.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Not offered for a playlist. Test exists so an HTTP account can be acknowledged
                // before its credentials travel in the clear; a playlist has no credentials, and
                // signing in opens the address anyway - so the button would either do nothing or
                // do the same thing twice.
                if (state.loginMethod != LoginMethod.Playlist) {
                OutlinedButton(
                    onClick = onTestConnection,
                    enabled = state.canSubmit,
                    modifier = Modifier.weight(1f),
                ) {
                    if (state.isTesting) {
                        CircularProgressIndicator(modifier = Modifier.height(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Test")
                    }
                }
                }
                Button(
                    onClick = onConnect,
                    enabled = state.canSubmit,
                    modifier = Modifier.weight(1.4f),
                ) {
                    if (state.isConnecting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Connect")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            LoginHelp()
            Spacer(Modifier.height(24.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Lock,
                    contentDescription = null,
                    tint = Color(0xFFE3DFEC),
                )
                Text(
                    text = "  Credentials are encrypted with Android Keystore. No analytics, ads, or cloud account.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE3DFEC),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun CredentialFields(
    state: LoginUiState,
    onPlaylistNameChange: (String) -> Unit,
    onServerChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onTogglePassword: () -> Unit,
) {
    // First, because it is the one field that is about the viewer rather than the provider, and
    // the only one they can leave empty.
    OutlinedTextField(
        value = state.playlistName,
        onValueChange = onPlaylistNameChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isConnecting,
        label = { Text("Playlist name (optional)") },
        placeholder = { Text("Living room") },
        supportingText = { Text("Shown on the home screen. Your user name is used if you leave it empty.") },
        leadingIcon = { Icon(Icons.Outlined.Label, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.server,
        onValueChange = onServerChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isConnecting,
        label = { Text("Server URL") },
        placeholder = { Text("https://provider.example:8080") },
        leadingIcon = { Icon(Icons.Outlined.Language, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Next,
        ),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.username,
        onValueChange = onUsernameChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isConnecting,
        label = { Text("Username") },
        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
    )
    Spacer(Modifier.height(12.dp))
    OutlinedTextField(
        value = state.password,
        onValueChange = onPasswordChange,
        modifier = Modifier.fillMaxWidth(),
        enabled = !state.isConnecting,
        label = { Text("Password") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onTogglePassword, enabled = !state.isConnecting) {
                Icon(
                    imageVector = if (state.passwordVisible) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (state.passwordVisible) "Hide password" else "Show password",
                )
            }
        },
        visualTransformation = if (state.passwordVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
private fun M3uUrlField(
    state: LoginUiState,
    onM3uUrlChange: (String) -> Unit,
    onToggleM3uUrl: () -> Unit,
) {
    OutlinedTextField(
        value = state.providerLink,
        onValueChange = onM3uUrlChange,
        modifier = Modifier
            .fillMaxWidth()
            .sensitiveContent(),
        enabled = !state.isConnecting,
        label = { Text("Xtream M3U URL") },
        placeholder = { Text("https://provider.example/get.php?…") },
        supportingText = {
            Text(
                "Supports Xtream get.php or player_api.php links containing username and password. Generic M3U playlists are not supported yet.",
            )
        },
        leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null) },
        trailingIcon = {
            IconButton(onClick = onToggleM3uUrl, enabled = !state.isConnecting) {
                Icon(
                    imageVector = if (state.providerLinkVisible) {
                        Icons.Default.VisibilityOff
                    } else {
                        Icons.Default.Visibility
                    },
                    contentDescription = if (state.providerLinkVisible) "Hide M3U URL" else "Show M3U URL",
                )
            }
        },
        visualTransformation = if (state.providerLinkVisible) {
            VisualTransformation.None
        } else {
            PasswordVisualTransformation()
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Password,
            imeAction = ImeAction.Done,
        ),
    )
}

@Composable
private fun StatusCard(text: String, color: Color, success: Boolean = false) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp),
        color = color.copy(alpha = 0.12f),
        contentColor = color,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (success) {
                Icon(Icons.Default.CheckCircle, contentDescription = null)
                Spacer(Modifier.padding(horizontal = 4.dp))
            }
            Text(text = text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        }
    }
}

/**
 * What to type here, for someone who has never signed into an Xtream account.
 *
 * Collapsed by default: it is genuinely useful the first time and pure noise afterwards, and the
 * sign-in form should not be pushed off the screen by an explanation of itself.
 */
@Composable
private fun LoginHelp() {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
        shape = MaterialTheme.shapes.medium,
        color = Color.White.copy(alpha = 0.06f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Outlined.HelpOutline,
                    contentDescription = null,
                    tint = Color(0xFFE3DFEC),
                )
                Text(
                    text = "  Where do I get these details?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFE3DFEC),
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = if (expanded) {
                        Icons.Default.ExpandLess
                    } else {
                        Icons.Default.ExpandMore
                    },
                    contentDescription = if (expanded) "Hide help" else "Show help",
                    tint = Color(0xFFE3DFEC),
                )
            }
            if (expanded) {
                Text(
                    text = "Your provider gives you a server address, a user name and a " +
                        "password when you sign up, usually by email and often as one long " +
                        "link. If you have the link, switch to M3U URL above and paste it " +
                        "whole: the server, user name and password are read out of it.\n\n" +
                        "Playlist name is yours to choose and can be left empty. Test checks " +
                        "the details without saving anything. Connect stores them encrypted " +
                        "on this device and downloads your library once, which takes a while " +
                        "on a large account.\n\n" +
                        "This app has no account of its own and no content of its own. It " +
                        "only plays an account you already pay for.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFFCFC9DD),
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
