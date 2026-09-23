package ago.chat.android.ui.components

import ago.chat.android.R
import ago.chat.android.core.domain.net.NetworkFailure
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/**
 * `26-59`: the one place [NetworkFailure] becomes a Russian sentence — the conversation list's own
 * load-error banner, a claim attempt that failed without a genuine server refusal to show, the thread
 * screen's join/load-older failures, and the shell's permissions-load failure all read this rather than
 * each growing its own copy. `:core:domain` classifies; only this function, in `:app`, knows any words —
 * the identical split [ago.chat.android.core.domain.conversations.ClaimResult.Refused]'s own `detail`
 * field draws for a genuine server refusal (a sentence the *server* wrote, shown verbatim, never one
 * this function invents for it).
 */
@Composable
public fun networkFailureText(failure: NetworkFailure): String =
    when (failure) {
        NetworkFailure.NoConnection -> stringResource(R.string.network_failure_no_connection)
        is NetworkFailure.ServerError -> stringResource(R.string.network_failure_server_status, failure.status)
        NetworkFailure.Unexpected -> stringResource(R.string.network_failure_unexpected)
    }
