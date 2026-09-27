package ago.chat.android.documents

import ago.chat.android.core.domain.documents.PublishedDocument
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-228` (`docs/design/tenant-consent-android.md` §3.3): [ConsentDocumentReaderViewModel]'s own state —
 * a stateless read of one version's text, the identical loading/failed/loaded shape this app's other
 * read-only screens already establish, plus one more arm: [NotFound] is a real terminal state of its own
 * (the version vanished between the overview read and this one), never folded into [Failed] — a retry on
 * a genuine `404` can only fail the identical way again, so this screen never offers one for it.
 */
internal sealed interface ConsentDocumentReaderUiState {
    data object Loading : ConsentDocumentReaderUiState

    /** The read itself failed - transport or an unclassified server error. Retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : ConsentDocumentReaderUiState

    /** `404`/`Document.NotFound` - the version existed when the overview listed it but is gone by read
     * time. Terminal: no retry, only a back affordance. */
    data object NotFound : ConsentDocumentReaderUiState

    data class Loaded(
        val document: PublishedDocument,
    ) : ConsentDocumentReaderUiState
}
