package ago.chat.android.channels

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.branding.LogoStatus
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.SectionLabel
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.3): Каналы → Почта — edits the site's
 * brand company name and uploads its logo, obtaining its own [BrandingViewModel] via [hiltViewModel],
 * the identical wiring [ago.chat.android.channels.TelegramChannelRoute] already establishes for a
 * drill-in [ago.chat.android.shell.MoreScreen] composes only for an operator holding `site:configure`.
 *
 * **This is the one place in the app that reads a `content://` `Uri`.** [SiteBrandingApi][ago.chat.android.core.domain.branding.SiteBrandingApi]'s
 * own doc comment states why: `ContentResolver` is Android framework, so the read has to happen on this
 * side of the port, in `:app` — here, rather than inside [BrandingViewModel], since a view model with a
 * `Context`/`ContentResolver` dependency would need one injected for a single call site, and the
 * dependency stops at the boundary a plain [ByteArray] and MIME [String] already draw. The bytes are
 * then run through [validateLogoCourtesy] — a **client-side courtesy check that never claims success**
 * (`docs/design/tenant-channels-android.md` §3.4) — before ever reaching [BrandingViewModel.uploadLogo];
 * a rejection here never touches the network and is not a value [BrandingActionError] can even express.
 */
@Composable
internal fun BrandingRoute(
    onBack: () -> Unit,
    viewModel: BrandingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pickProblem by rememberSaveable { mutableStateOf<LogoValidationProblem?>(null) }

    val pickerLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri == null) return@rememberLauncherForActivityResult
            pickProblem = null
            scope.launch {
                val picked =
                    withContext(Dispatchers.IO) {
                        val resolver = context.contentResolver
                        val contentType = resolver.getType(uri)
                        val bytes = resolver.openInputStream(uri)?.use { stream -> stream.readBytes() }
                        if (bytes != null && contentType != null) bytes to contentType else null
                    }
                if (picked == null) {
                    // The picker handed back a `Uri` this resolver could not open or type - as good as
                    // "not an image this app can make sense of", the same reason a genuinely undecodable
                    // file gets below (`LogoValidationProblem.Undecodable`), never a silent no-op.
                    pickProblem = LogoValidationProblem.Undecodable
                    return@launch
                }
                val (bytes, contentType) = picked
                val problem = validateLogoCourtesy(bytes, contentType)
                if (problem != null) {
                    pickProblem = problem
                } else {
                    viewModel.uploadLogo(bytes, contentType)
                }
            }
        }

    BrandingScreen(
        state = state,
        pickProblem = pickProblem,
        onSaveCompanyName = viewModel::saveCompanyName,
        onPickLogo = { pickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        onRefresh = viewModel::refresh,
        onBack = onBack,
    )
}

/**
 * The stateless screen — Route/Screen split, back arrow, no
 * [ago.chat.android.ui.components.AccountAvatarAction] (a drill-in), the identical shape
 * [ago.chat.android.channels.ChannelConnectScreen] already establishes. The top-bar **refresh** action
 * is this screen's own addition over that shape: there is no page reload on a phone, and
 * [BrandingViewModel]'s own doc comment states this is the only way a `Pending` →
 * `Ready`/`Rejected` logo transition is ever observed.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BrandingScreen(
    state: BrandingUiState,
    pickProblem: LogoValidationProblem?,
    onSaveCompanyName: (String?) -> Unit,
    onPickLogo: () -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
) {
    val snackbarHostState = remember { SnackbarHostState() }
    val savedMessage = stringResource(R.string.channels_email_name_saved)
    val nameSavedTick = (state as? BrandingUiState.Loaded)?.nameSavedTick ?: 0
    LaunchedEffect(nameSavedTick) {
        if (nameSavedTick > 0) snackbarHostState.showSnackbar(savedMessage)
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = { Text(text = stringResource(R.string.channels_email_title)) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                    actions = {
                        TextButton(onClick = onRefresh) {
                            Text(text = stringResource(R.string.channels_email_refresh_action))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    BrandingUiState.Loading -> LoadingBody()
                    is BrandingUiState.Failed -> BrandingFailedBody(reason = state.reason, onRetry = onRefresh)
                    is BrandingUiState.Loaded ->
                        BrandingLoadedBody(
                            state = state,
                            pickProblem = pickProblem,
                            onSaveCompanyName = onSaveCompanyName,
                            onPickLogo = onPickLogo,
                        )
                }
            }
        }
    }
}

/** The read itself failed — the identical "title, [networkFailureText], retry" shape
 * [ago.chat.android.channels.ChannelConnectScreen]'s own private status-failed body already
 * establishes, restated here since neither file imports composables from the other. */
@Composable
private fun BrandingFailedBody(
    reason: NetworkFailure,
    onRetry: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = networkFailureText(reason),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Button(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** Company-name section, then a divider, then the logo section
 * (`docs/design/tenant-channels-android.md` §3.3) — the two independent writes drawn one after the
 * other, each with its own in-flight/error state, never sharing a `Button`. */
@Composable
private fun BrandingLoadedBody(
    state: BrandingUiState.Loaded,
    pickProblem: LogoValidationProblem?,
    onSaveCompanyName: (String?) -> Unit,
    onPickLogo: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        CompanyNameSection(state = state, onSave = onSaveCompanyName)
        HorizontalDivider()
        LogoSection(
            state = state,
            pickProblem = pickProblem,
            onPickLogo = onPickLogo,
        )
    }
}

/** `brandCompanyName` — an `OutlinedTextField` seeded from the committed value, a primary Сохранить
 * that PUTs `trimmed.ifBlank { null }`. Keyed on [BrandingUiState.Loaded.brandCompanyName] so a fresh
 * echo from the server (a successful save, or a reload) always re-seeds the draft, the identical
 * `rememberSaveable(committed) { … }` keying [WidgetAppearanceEditor]'s own fields already use. */
@Composable
private fun CompanyNameSection(
    state: BrandingUiState.Loaded,
    onSave: (String?) -> Unit,
) {
    var nameInput by rememberSaveable(state.brandCompanyName) { mutableStateOf(state.brandCompanyName.orEmpty()) }

    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        OutlinedTextField(
            value = nameInput,
            onValueChange = { nameInput = it },
            label = { Text(text = stringResource(R.string.channels_email_company_name_label)) },
            singleLine = true,
            enabled = !state.savingName,
            modifier = Modifier.fillMaxWidth(),
        )
        state.nameError?.let { error -> InlineAlert(text = brandingActionErrorText(error)) }
        Button(
            onClick = { onSave(nameInput.trim().ifBlank { null }) },
            enabled = !state.savingName,
        ) {
            Text(
                text =
                    stringResource(
                        if (state.savingName) R.string.channels_email_action_saving else R.string.channels_email_action_save,
                    ),
            )
        }
    }
}

/** The logo picker, its badge, and — for [LogoStatus.Rejected] — the server's own reason
 * (`docs/design/tenant-channels-android.md` §3.3). [pickProblem] is a purely local, never-network
 * courtesy rejection ([validateLogoCourtesy]); [BrandingUiState.Loaded.uploadError] is the server's own
 * refusal or a transport failure — two different alerts because they are two different truths, never
 * merged into one banner. */
@Composable
private fun LogoSection(
    state: BrandingUiState.Loaded,
    pickProblem: LogoValidationProblem?,
    onPickLogo: () -> Unit,
) {
    Column {
        SectionLabel(text = stringResource(R.string.channels_email_logo_section))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (state.logoUrl != null) {
                AsyncImage(
                    model = state.logoUrl,
                    contentDescription = stringResource(R.string.channels_email_logo_preview_description),
                    modifier = Modifier.size(100.dp),
                )
            }

            LogoStatusBadge(state.logoStatus)

            if (state.logoStatus == LogoStatus.Rejected && state.logoRejectionReason != null) {
                InlineAlert(text = state.logoRejectionReason)
            }

            // A local courtesy rejection and a server-side upload error are mutually exclusive in
            // practice (`BrandingRoute` clears [pickProblem] itself before ever calling
            // `BrandingViewModel.uploadLogo`, and a fresh pick attempt clears it again before the next
            // decode), but both are read defensively rather than assumed - the identical "show whichever
            // is actually set" posture every other independent-error pair in this app already takes.
            pickProblem?.let { problem -> InlineAlert(text = stringResource(logoValidationProblemTextRes(problem))) }
            state.uploadError?.let { error -> InlineAlert(text = brandingActionErrorText(error)) }

            Button(onClick = onPickLogo, enabled = !state.uploading) {
                Text(
                    text =
                        stringResource(
                            if (state.uploading) {
                                R.string.channels_email_logo_uploading_action
                            } else {
                                R.string.channels_email_logo_pick_action
                            },
                        ),
                )
            }
        }
    }
}

/** [LogoStatus.None] draws nothing - there is nothing yet to report. The other three are the mockup's
 * own three-way pill, restated locally rather than reused from [ago.chat.android.channels.ChannelConnectScreen]'s
 * own private `StatusPill` - each screen owns its own small drawing helpers, the same posture that
 * file's own doc comment already states for its identical restatement of `ConversationListScreen`'s. */
@Composable
private fun LogoStatusBadge(status: LogoStatus) {
    when (status) {
        LogoStatus.None -> return
        LogoStatus.Ready ->
            LogoStatusPill(
                text = stringResource(R.string.channels_email_logo_status_ready),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            )

        LogoStatus.Pending ->
            LogoStatusPill(
                text = stringResource(R.string.channels_email_logo_status_pending),
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            )

        LogoStatus.Rejected ->
            LogoStatusPill(
                text = stringResource(R.string.channels_email_logo_status_rejected),
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
            )
    }
}

@Composable
private fun LogoStatusPill(
    text: String,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(color = containerColor, contentColor = contentColor, shape = RoundedCornerShape(percent = 50)) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** An inline banner for a refusal, a rejection reason, or a courtesy-validation message — the identical
 * tonal-danger-surface shape [ago.chat.android.channels.ChannelConnectScreen]'s own private `InlineAlert`
 * already establishes, restated here for the same "neither file imports composables from the other"
 * reason [BrandingFailedBody]'s own doc comment states. */
@Composable
private fun InlineAlert(text: String) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(text = text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
    }
}

/** The one place [BrandingActionError] becomes a sentence — the identical single-call-site discipline
 * [ago.chat.android.channels.ChannelConnectScreen]'s own `channelActionErrorText` already establishes
 * for [ChannelActionError]. */
@Composable
private fun brandingActionErrorText(error: BrandingActionError): String =
    when (error) {
        is BrandingActionError.ServerRefusal -> error.detail
        is BrandingActionError.Unavailable -> networkFailureText(error.reason)
    }

/** [LogoValidationProblem]'s own four reasons, each its own string
 * (`docs/design/tenant-channels-android.md` §3.4) - never a shared "invalid file" catch-all. */
private fun logoValidationProblemTextRes(problem: LogoValidationProblem): Int =
    when (problem) {
        LogoValidationProblem.InvalidFormat -> R.string.channels_email_logo_invalid_format
        LogoValidationProblem.TooLarge -> R.string.channels_email_logo_too_large
        LogoValidationProblem.InvalidDimensions -> R.string.channels_email_logo_invalid_dimensions
        LogoValidationProblem.Undecodable -> R.string.channels_email_logo_undecodable
    }
