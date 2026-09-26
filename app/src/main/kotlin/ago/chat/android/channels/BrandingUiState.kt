package ago.chat.android.channels

import ago.chat.android.core.domain.branding.LogoStatus
import ago.chat.android.core.domain.net.NetworkFailure

/**
 * `26-191`/`C4` (`docs/design/tenant-channels-android.md` §3.3): [BrandingViewModel]'s own state — the
 * identical loading/failed/loaded shape this app's other read-then-edit screens already establish
 * ([ago.chat.android.channels.WidgetConfigUiState]), except [Loaded] tracks **two independent writes**
 * (a company-name save, a logo upload) with two independent in-flight/error fields, since
 * [ago.chat.android.core.domain.branding.SiteBrandingApi]'s own doc comment states this screen is two
 * unrelated settings, not one full-DTO round trip.
 */
internal sealed interface BrandingUiState {
    data object Loading : BrandingUiState

    /** The initial read itself failed — retry is the only action. */
    data class Failed(
        val reason: NetworkFailure,
    ) : BrandingUiState

    /**
     * @param brandCompanyName the last name the server confirmed — by the initial `GET`, or by the echo
     *   of the most recent successful save. The name field's own draft is seeded from this.
     * @param logoUrl non-null only once [logoStatus] has reached [LogoStatus.Ready] - a plain,
     *   non-expiring public URL [ago.chat.android.channels.BrandingScreen]'s own `AsyncImage` preview
     *   points at directly.
     * @param logoStatus the logo upload's own lifecycle, re-read from the server on every [refresh] —
     *   this screen never polls; the top-bar refresh action and reopening the screen are the only ways
     *   a `Pending` → `Ready`/`Rejected` transition is observed (`docs/design/tenant-channels-android.md`
     *   §5.4's own edge case).
     * @param logoRejectionReason non-null exactly when [logoStatus] is [LogoStatus.Rejected].
     * @param savingName `true` while a company-name save is in flight.
     * @param nameSavedTick bumped on every successful name save — the identical
     *   [ago.chat.android.channels.WidgetConfigUiState.Loaded.savedTick] shape, driving a "Сохранено"
     *   snackbar even across two consecutive identical saves.
     * @param nameError the most recent name save's own refusal or failure, classified but not yet
     *   worded — [BrandingActionError]'s own doc comment states why turning it into words stays the
     *   composable's job. `null` once a new save starts or one succeeds.
     * @param uploading `true` while a logo upload is in flight - independent of [savingName], since the
     *   two writes have nothing to do with each other.
     * @param uploadError the most recent logo upload's own refusal or failure. A courtesy-validation
     *   rejection (`docs/design/tenant-channels-android.md` §3.4) never reaches this far - it is caught
     *   before [BrandingViewModel.uploadLogo] is ever called, so it is not a value of this type at all
     *   (see [ago.chat.android.channels.LogoValidationProblem]).
     */
    data class Loaded(
        val brandCompanyName: String?,
        val logoUrl: String?,
        val logoStatus: LogoStatus,
        val logoRejectionReason: String?,
        val savingName: Boolean = false,
        val nameSavedTick: Int = 0,
        val nameError: BrandingActionError? = null,
        val uploading: Boolean = false,
        val uploadError: BrandingActionError? = null,
    ) : BrandingUiState
}

/**
 * `26-191`: a branding write's own two honest failure outcomes — the identical split
 * [ago.chat.android.channels.WidgetConfigSaveError] already establishes for the widget-config save,
 * restated here rather than reused since neither port is the other's concern. [ServerRefusal] carries
 * the server's own validation sentence verbatim; [Unavailable] carries a [NetworkFailure] this app's own
 * composable renders through [ago.chat.android.ui.components.networkFailureText].
 */
internal sealed interface BrandingActionError {
    data class ServerRefusal(
        val detail: String,
    ) : BrandingActionError

    data class Unavailable(
        val reason: NetworkFailure,
    ) : BrandingActionError
}
