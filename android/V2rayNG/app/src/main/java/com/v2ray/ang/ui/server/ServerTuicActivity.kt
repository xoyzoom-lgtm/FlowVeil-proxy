package com.v2ray.ang.ui.server

import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.fmt.TuicFmt
import com.v2ray.ang.ui.compose.FormDropdownField
import com.v2ray.ang.ui.compose.FormTextField
import com.v2ray.ang.ui.compose.SettingsSwitchItem

class ServerTuicActivity : BaseServerActivity() {

    override val serverConfigType: EConfigType = EConfigType.TUIC

    @Composable
    override fun ScreenContent() {
        val uiState = rememberSaveable(saver = ServerUiState.Saver) {
            ServerUiState.from(
                initialConfig = initialConfig
            )
        }.apply {
            configType = EConfigType.TUIC
        }

        ServerEditorScaffold(
            title = serverConfigType.toString(),
            onSaveClick = { saveServer(uiState) }
        ) {
            CommonBasicFields(uiState)
            TuicProtocolFields(uiState)
        }
    }

    override fun validateProtocolConfig(config: ProfileItem): Boolean {
        if (config.username.isNullOrBlank() || config.password.isNullOrBlank()) {
            return false
        }
        config.security = AppConfig.TLS
        if (config.alpn.isNullOrBlank()) config.alpn = "h3"
        if (config.congestionControl.isNullOrBlank()) config.congestionControl = TuicFmt.CONGESTION_CONTROLS.first()
        if (config.udpRelayMode.isNullOrBlank()) config.udpRelayMode = TuicFmt.UDP_RELAY_MODES.first()
        return true
    }

    @Composable
    private fun TuicProtocolFields(state: ServerUiState) {
        FormTextField(
            label = stringResource(R.string.server_lab_id),
            value = state.username,
            onValueChange = { state.username = it }
        )
        FormTextField(
            label = stringResource(R.string.server_lab_id3),
            value = state.password,
            onValueChange = { state.password = it },
            isError = state.isPasswordError
        )
        FormDropdownField(
            stringResource(R.string.server_lab_tuic_congestion),
            state.congestionControl.ifBlank { TuicFmt.CONGESTION_CONTROLS.first() },
            TuicFmt.CONGESTION_CONTROLS,
            { state.congestionControl = it }
        )
        FormDropdownField(
            stringResource(R.string.server_lab_tuic_udp_relay),
            state.udpRelayMode.ifBlank { TuicFmt.UDP_RELAY_MODES.first() },
            TuicFmt.UDP_RELAY_MODES,
            { state.udpRelayMode = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_sni),
            state.sni,
            { state.sni = it }
        )
        FormTextField(
            stringResource(R.string.server_lab_stream_alpn),
            state.alpn,
            { state.alpn = it }
        )
        SettingsSwitchItem(
            title = stringResource(R.string.server_lab_allow_insecure),
            checked = state.allowInsecure,
            onCheckedChange = { state.allowInsecure = it }
        )
    }
}
