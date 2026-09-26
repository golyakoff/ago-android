package ago.chat.android.bookings

import ago.chat.android.core.domain.bookings.BookingsQueueFailure
import ago.chat.android.core.domain.recut.RecutPreview
import ago.chat.android.core.domain.workerschedule.ScheduleKind
import ago.chat.android.core.domain.workerschedule.WorkerSchedule
import ago.chat.android.core.domain.workerschedule.WorkerScheduleDraft
import java.time.LocalDate

/**
 * `26-170` (`26-155` part 2): [WorkerScheduleViewModel]'s whole state for the «График» drill-down — the
 * identical four-arm shape [MastersUiState]/[ago.chat.android.schedule.WorkingHoursUiState] already
 * establish, restated rather than shared because [Loaded] carries a schedule that may not exist yet
 * ([WorkerScheduleResult.None]'s own "create" state, `docs/design/26-155-*.md`'s own quote of the
 * server's `configuration.no_schedule`) plus the minimal re-cut hook (Q2) this slice ships instead of
 * the full «Пересчёт» screen.
 */
internal sealed interface WorkerScheduleUiState {
    data object Loading : WorkerScheduleUiState

    /**
     * @param existing the schedule the server last confirmed, or `null` when this worker has never had
     *   one saved — [WorkerScheduleBody] renders the empty-state note in exactly that case, never an
     *   error (the server's own `configuration.no_schedule` is a real "nothing saved yet" state, not a
     *   failure — [WorkerScheduleApi.fetchSchedule]'s own doc comment).
     * @param form a *copy* the form edits freely, not a reference into [existing] — the identical
     *   "the confirmed value stays what the server last said while the operator types" discipline
     *   [MastersUiState.Loaded.editing]'s own doc comment records.
     * @param recut the minimal re-cut hook's own state (Q2) — a read-only preview reached from this
     *   screen's own note+button when [existing] is non-null; the full three-step «Пересчёт» screen
     *   (decisions, `AlertDialog` confirm, result) is a follow-up slice this hook deliberately does not
     *   build (`docs/design/26-155-*.md`'s own ticket split).
     */
    data class Loaded(
        val existing: WorkerSchedule?,
        val form: WorkerScheduleForm,
        val formBusy: Boolean = false,
        val actionError: BookingActionErrorUi? = null,
        val recut: RecutHookUiState = RecutHookUiState.Idle,
    ) : WorkerScheduleUiState

    data object NotConfigured : WorkerScheduleUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : WorkerScheduleUiState
}

/**
 * `26-170`: the minimal re-cut hook Q2 asks for — a read-only [RecutPreview], with no decisions and no
 * confirm/execute step. [Loaded]/[Refused]/[Failed] mirror [ago.chat.android.core.domain.recut.RecutPreviewResult]'s
 * own three-way split one-for-one; [Idle] is this hook's own resting state (the dialog is closed).
 */
internal sealed interface RecutHookUiState {
    data object Idle : RecutHookUiState

    data object Loading : RecutHookUiState

    data class Loaded(
        val preview: RecutPreview,
    ) : RecutHookUiState

    /** A genuine server refusal (a bounds code named on [ago.chat.android.core.domain.recut.RecutApi.preview]'s
     * own doc comment) — shown verbatim, the identical rule every other refusal in this app follows. */
    data class Refused(
        val detail: String,
    ) : RecutHookUiState

    data class Failed(
        val reason: BookingsQueueFailure,
    ) : RecutHookUiState
}

/**
 * `26-170`: what the «График» form currently holds — the union of the create and the edit form, one
 * type for both (the identical "one draft feeds either write" shape [WorkerForm] and
 * [ago.chat.android.core.domain.workerschedule.WorkerScheduleDraft] both already establish).
 *
 * **Every numeric and time field is a `String`, deliberately** — [ServiceDraft]'s own doc comment states
 * the reason this app already follows everywhere a typed number or a typed `HH:mm` reaches a form: a
 * half-typed value ("" while backspacing, "1" on the way to "18:00") is a real state a data class of
 * `Int?`/parsed time cannot hold, and the one parse happens once, at submit
 * ([toDraftOrNull]) — never here. The two dates ([cycleAnchor]/[materializeFrom]) are the one exception,
 * carried as ISO `yyyy-MM-dd` strings a [android.app.DatePickerDialog] always hands back well-formed, so
 * there is no half-typed date to represent.
 *
 * **The cycle fields are always present, never `null`, even while [kind] is [ScheduleKind.Weekly].** The
 * console's own `WorkerScheduleSection.tsx` keeps its `FormState` exactly this way (`formFrom`'s own
 * `?? today()`/`?? "09:00"` fallbacks) so that switching the `FilterChip` pair back to Cycle restores
 * whatever was last typed rather than blanking it — [toDraftOrNull] is the one place that null out
 * to the server when [kind] is [ScheduleKind.Weekly].
 */
internal data class WorkerScheduleForm(
    val kind: ScheduleKind,
    val cycleAnchor: String,
    val cycleWorkingDays: String,
    val cycleRestDays: String,
    val cycleStartsAt: String,
    val cycleEndsAt: String,
    val slotMinutes: String,
    val bufferMinutes: String,
    val horizonDays: String,
    val materializeFrom: String,
    val buffersCountTowardServiceDuration: Boolean,
) {
    /**
     * `null` when a required number is blank or not a whole number — the submit path refuses locally
     * only here, because there is no `PUT` to send without a number to put in it
     * ([MastersViewModel.submit]'s own identical "no request to make at all" rule). Every other
     * refusal (a horizon over 180 days, `materializeFrom` moving backwards, a missing cycle field the
     * server itself requires) belongs to the server and comes back as its own sentence
     * ([ago.chat.android.core.domain.workerschedule.SaveWorkerScheduleResult.Refused]).
     */
    fun toDraftOrNull(): WorkerScheduleDraft? {
        val slot = slotMinutes.trim().toIntOrNull() ?: return null
        val buffer = bufferMinutes.trim().toIntOrNull() ?: return null
        val horizon = horizonDays.trim().toIntOrNull() ?: return null
        val isCycle = kind == ScheduleKind.Cycle
        val workingDays = if (isCycle) cycleWorkingDays.trim().toIntOrNull() ?: return null else null
        val restDays = if (isCycle) cycleRestDays.trim().toIntOrNull() ?: return null else null

        return WorkerScheduleDraft(
            kind = kind,
            cycleAnchor = if (isCycle) cycleAnchor else null,
            cycleWorkingDays = workingDays,
            cycleRestDays = restDays,
            cycleStartsAt = if (isCycle) cycleStartsAt.trim() else null,
            cycleEndsAt = if (isCycle) cycleEndsAt.trim() else null,
            slotMinutes = slot,
            bufferMinutes = buffer,
            horizonDays = horizon,
            materializeFrom = materializeFrom,
            buffersCountTowardServiceDuration = buffersCountTowardServiceDuration,
        )
    }
}

/** The edit form, prefilled from what the server last said — a copy, never a reference (see
 * [WorkerScheduleUiState.Loaded]'s own doc comment). */
internal fun WorkerSchedule.toForm(): WorkerScheduleForm =
    WorkerScheduleForm(
        kind = kind,
        cycleAnchor = cycleAnchor ?: LocalDate.now().toString(),
        cycleWorkingDays = cycleWorkingDays?.toString() ?: DEFAULT_CYCLE_WORKING_DAYS,
        cycleRestDays = cycleRestDays?.toString() ?: DEFAULT_CYCLE_REST_DAYS,
        cycleStartsAt = cycleStartsAt ?: DEFAULT_CYCLE_STARTS_AT,
        cycleEndsAt = cycleEndsAt ?: DEFAULT_CYCLE_ENDS_AT,
        slotMinutes = slotMinutes.toString(),
        bufferMinutes = bufferMinutes.toString(),
        horizonDays = horizonDays.toString(),
        materializeFrom = materializeFrom,
        buffersCountTowardServiceDuration = buffersCountTowardServiceDuration,
    )

/** A brand-new schedule's own starting point — [ago.chat.android.core.domain.workerschedule.WorkerScheduleResult.None]'s
 * "nothing saved yet" state, given the identical default values the console's own `defaultForm()`
 * (`WorkerScheduleSection.tsx`) opens with, so an operator who has used the console sees the same
 * starting numbers on the phone. */
internal fun blankWorkerScheduleForm(): WorkerScheduleForm {
    val today = LocalDate.now().toString()
    return WorkerScheduleForm(
        kind = ScheduleKind.Weekly,
        cycleAnchor = today,
        cycleWorkingDays = DEFAULT_CYCLE_WORKING_DAYS,
        cycleRestDays = DEFAULT_CYCLE_REST_DAYS,
        cycleStartsAt = DEFAULT_CYCLE_STARTS_AT,
        cycleEndsAt = DEFAULT_CYCLE_ENDS_AT,
        slotMinutes = DEFAULT_SLOT_MINUTES,
        bufferMinutes = DEFAULT_BUFFER_MINUTES,
        horizonDays = DEFAULT_HORIZON_DAYS,
        materializeFrom = today,
        buffersCountTowardServiceDuration = true,
    )
}

/** `Ago.Calendar.Domain.WorkerSchedule.MaxHorizonDays`, mirrored for the caption only — never enforced
 * client-side (`docs/design/26-155-*.md`'s own accepted "cap enforced server-side, not on the client -
 * console decision, kept"). */
internal const val WORKER_SCHEDULE_MAX_HORIZON_DAYS: Int = 180

private const val DEFAULT_CYCLE_WORKING_DAYS = "2"
private const val DEFAULT_CYCLE_REST_DAYS = "2"
private const val DEFAULT_CYCLE_STARTS_AT = "09:00"
private const val DEFAULT_CYCLE_ENDS_AT = "18:00"
private const val DEFAULT_SLOT_MINUTES = "30"
private const val DEFAULT_BUFFER_MINUTES = "0"
private const val DEFAULT_HORIZON_DAYS = "30"
