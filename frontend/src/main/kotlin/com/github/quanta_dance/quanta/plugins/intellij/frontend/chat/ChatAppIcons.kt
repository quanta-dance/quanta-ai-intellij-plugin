package com.github.quanta_dance.quanta.plugins.intellij.frontend.chat

import org.jetbrains.jewel.ui.icon.PathIconKey
import org.jetbrains.jewel.ui.icons.AllIconsKeys

/**
 * Centralized icon keys used by the Chat sample UI.
 * Grouped by feature area to keep call-sites tidy and consistent.
 */
object ChatAppIcons {
    object Header {
        val search = AllIconsKeys.Actions.Find
        val settings = AllIconsKeys.Actions.InlayGear
        val close = AllIconsKeys.Actions.Cancel
        val micActive = PathIconKey("/icons/cwmMicOnAir.svg", ChatAppIcons::class.java)
        val micOn = AllIconsKeys.CodeWithMe.CwmMicOn
        val micOff = AllIconsKeys.CodeWithMe.CwmMicOff
        val speakerOn = PathIconKey("/icons/speakerOn.svg", ChatAppIcons::class.java)
        val speakerOff = PathIconKey("/icons/speakerOff.svg", ChatAppIcons::class.java)
    }

    object Prompt {
        val send = AllIconsKeys.RunConfigurations.TestState.Run
        val stop = AllIconsKeys.Run.Stop
    }
}