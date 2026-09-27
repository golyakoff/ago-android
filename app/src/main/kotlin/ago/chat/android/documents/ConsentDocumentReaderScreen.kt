package ago.chat.android.documents

import ago.chat.android.R
import ago.chat.android.bookings.LoadingBody
import ago.chat.android.core.domain.documents.PublishedDocument
import ago.chat.android.core.domain.net.NetworkFailure
import ago.chat.android.ui.components.networkFailureText
import ago.chat.android.ui.icons.AgoIcons
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §3.3): «Просмотр документа» — the deferred half of
 * `26-226`'s own Документы согласий area: a stateless, read-only render of one published version's full
 * text, reached from a version row's own «Открыть» action on [ConsentDocumentsScreen]. Obtains its own
 * [ConsentDocumentReaderViewModel] via [hiltViewModel] - a *second*, independent view model from
 * [ConsentDocumentsViewModel], because this reads a different port
 * ([ago.chat.android.core.domain.documents.PublishedDocumentApi], anonymous) against a different,
 * per-open `(documentKey, version)` pair, not the tenant-scoped overview.
 *
 * **This route is deliberately reusable with no session at all** — `tenant-consent-android.md` §3.3's own
 * closing remark: the future `…/policies/{key}` deep-link reader (`navigation.md`) is meant to open the
 * identical [PublishedDocumentApi]/screen pairing from outside this app's authenticated shell. Nothing
 * here reads `site:configure`, an active site, or a bearer token - only the two strings a caller hands it.
 */
@Composable
internal fun ConsentDocumentReaderRoute(
    documentKey: String,
    version: String?,
    title: String,
    onBack: () -> Unit,
    viewModel: ConsentDocumentReaderViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(documentKey, version) {
        viewModel.load(documentKey, version)
    }

    ConsentDocumentReaderScreen(
        title = title,
        state = state,
        onRetry = viewModel::retry,
        onBack = onBack,
    )
}

/** The stateless half - Route/Screen split, back arrow, top bar title = the version's own title (the
 * caller's, passed down rather than re-derived from a still-loading state -
 * `tenant-consent-android.md` §3.3's own table: "Top bar title = the version title"). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ConsentDocumentReaderScreen(
    title: String,
    state: ConsentDocumentReaderUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(text = title) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(imageVector = AgoIcons.Back, contentDescription = stringResource(R.string.action_back))
                        }
                    },
                )
            },
        ) { padding ->
            Box(modifier = Modifier.fillMaxSize().padding(padding)) {
                when (state) {
                    ConsentDocumentReaderUiState.Loading -> LoadingBody()
                    is ConsentDocumentReaderUiState.Failed -> ConsentDocumentReaderFailedBody(reason = state.reason, onRetry = onRetry)
                    ConsentDocumentReaderUiState.NotFound -> ConsentDocumentReaderNotFoundBody()
                    is ConsentDocumentReaderUiState.Loaded -> ConsentDocumentReaderLoadedBody(document = state.document)
                }
            }
        }
    }
}

/** The read itself failed - the identical "message, retry" shape
 * [ago.chat.android.documents.ConsentDocumentsScreen]'s own private failed body already establishes,
 * restated here since neither file imports composables from the other. */
@Composable
private fun ConsentDocumentReaderFailedBody(
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
        TextButton(onClick = onRetry, modifier = Modifier.padding(top = 16.dp)) {
            Text(text = stringResource(R.string.action_retry))
        }
    }
}

/** `Document.NotFound` - a terminal state, no retry (`ConsentDocumentReaderUiState.NotFound`'s own doc
 * comment: a retry on a genuine 404 can only fail identically again). */
@Composable
private fun ConsentDocumentReaderNotFoundBody() {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(
            text = stringResource(R.string.consent_reader_not_found),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** The document's own text - a header line (version + device-zone date), then the body as selectable,
 * scrollable plain text (`tenant-consent-android.md` §3.3: "Body is plain text ... so no Markdown
 * rendering"). [SelectionContainer] lets an operator copy a clause out to quote it elsewhere - the same
 * affordance reading any long text on a phone benefits from, and nothing here needed to withhold it. */
@Composable
private fun ConsentDocumentReaderLoadedBody(document: PublishedDocument) {
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            // The identical "version · device-zone date" interpunct idiom
            // [ago.chat.android.documents.ConsentDocumentsScreen]'s own `ConsentVersionRow` already
            // uses - not a string resource, since the separator itself carries no translatable words,
            // the same reasoning that sibling row's own inline template already rests on.
            text = "${document.version} · ${consentReaderDate(document.publishedAt)}",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SelectionContainer {
            Text(
                text = document.body,
                style = MaterialTheme.typography.bodyLarge.copy(lineHeight = MaterialTheme.typography.bodyLarge.lineHeight * 1.4),
            )
        }
    }
}

/** [PublishedDocument.publishedAt] rendered in the operator's own zone (CLAUDE.md rule 11), a date only -
 * the identical device-zone idiom [ago.chat.android.documents.ConsentDocumentsScreen]'s own
 * `consentVersionDate` already establishes, restated here since neither file imports composables from the
 * other. */
@Composable
private fun consentReaderDate(instant: Instant): String {
    val locale = LocalConfiguration.current.locales[0]
    return DateTimeFormatter.ofPattern("d MMMM yyyy", locale).format(instant.atZone(ZoneId.systemDefault()))
}
