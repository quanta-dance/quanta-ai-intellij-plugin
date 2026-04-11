package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.backend.settings.BackendQuantaSettingsState
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

internal class BackendRpcApiProvider : RemoteApiProvider {
    override fun RemoteApiProvider.Sink.remoteApis() {
        remoteApi(remoteApiDescriptor<ChatRepositoryRpcApi>()) {
            BackendChatRepositoryRpcApi()
        }
        remoteApi(remoteApiDescriptor<QuantaBackendApi>()) {
            QuantaBackendRpcApi()
        }
        remoteApi(remoteApiDescriptor<WorkspaceFileRpcApi>()) {
            BackendWorkspaceFileRpcApi()
        }
        remoteApi(remoteApiDescriptor<QuantaSettingsApi>()) {
            BackendSettingsRpcApi()
        }
    }
}
