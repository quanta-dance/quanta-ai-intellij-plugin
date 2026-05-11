package com.github.quanta_dance.quanta.plugins.intellij.backend.rpc

import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.ChatRepositoryRpcApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaBackendApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.QuantaSettingsApi
import com.github.quanta_dance.quanta.plugins.intellij.shared.rpc.WorkspaceFileRpcApi
import com.intellij.platform.rpc.backend.RemoteApiProvider
import fleet.rpc.remoteApiDescriptor

/**
 * Registers backend implementations for the shared RPC interfaces used by the split plugin.
 *
 * This provider is the backend-side bridge that exposes chat, workspace, settings, and general
 * backend APIs to frontend callers in remote or split-mode environments.
 */
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
