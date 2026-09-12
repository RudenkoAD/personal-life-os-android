package com.personallifeos.mobile.data

import com.personallifeos.mobile.model.LifeState

/**
 * The state exposed to the UI and widgets.  [state] is the confirmed server
 * state with queued mutations projected on top of it.
 */
data class MobileSnapshot(
    val state: LifeState? = null,
    val pending: Int = 0,
    val syncing: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
    val lastSyncedAt: String? = null,
    val blocked: Boolean = false,
)
