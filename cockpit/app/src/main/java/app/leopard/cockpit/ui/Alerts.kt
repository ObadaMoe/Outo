package app.leopard.cockpit.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class Role { DEFAULT, CANCEL, DESTRUCTIVE }

data class AlertButton(val label: String, val role: Role = Role.DEFAULT, val action: () -> Unit = {})

/** An Apple-style alert: a title, an optional message, and two or three buttons. */
data class Alert(val title: String, val message: String? = null, val buttons: List<AlertButton>)

object Alerts {
    private val _current = MutableStateFlow<Alert?>(null)
    val current: StateFlow<Alert?> = _current.asStateFlow()

    fun show(alert: Alert): Alert { _current.value = alert; return alert }
    fun dismiss() { _current.value = null }

    /** Takes [alert] away if it's still up (it went stale), without pressing anything. */
    fun dismiss(alert: Alert) { _current.compareAndSet(alert, null) }

    fun confirm(
        title: String,
        message: String?,
        action: String,
        destructive: Boolean = true,
        onCancel: () -> Unit = {},
        onConfirm: () -> Unit,
    ): Alert =
        show(
            Alert(
                title, message,
                listOf(
                    AlertButton("Cancel", Role.CANCEL, onCancel),
                    AlertButton(action, if (destructive) Role.DESTRUCTIVE else Role.DEFAULT, onConfirm),
                ),
            ),
        )
}
