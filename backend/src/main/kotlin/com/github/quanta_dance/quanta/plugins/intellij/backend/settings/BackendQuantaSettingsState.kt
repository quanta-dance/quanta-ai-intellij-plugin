// SPDX-License-Identifier: GPL-3.0-only
// Copyright (c) 2025 Aleksandr Nekrasov (Quanta-Dance)

package com.github.quanta_dance.quanta.plugins.intellij.backend.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * Legacy backend-persisted settings shell retained only for storage compatibility.
 *
 * Frontend-owned configuration now lives in [FrontendQuantaSettingsState] on the user machine and is
 * pushed into [BackendRuntimeSettingsService] for backend execution. Keep this state empty unless a
 * future backend-only persisted concern appears.
 */
@Service(Service.Level.APP)
@State(
    name = "BackendQuantaSettingsState",
    storages = [Storage("quanta.backend.xml")],
)
class BackendQuantaSettingsState : PersistentStateComponent<BackendQuantaSettingsState.State> {
    class State

    companion object {
        val instance: BackendQuantaSettingsState
            get() = ApplicationManager.getApplication().getService(BackendQuantaSettingsState::class.java)
    }

    @Volatile
    private var state: State = State()

    val settings: State
        get() = state

    override fun getState(): State = state

    override fun loadState(state: State) {
        this.state = state
    }
}
