package io.github.mucute.qwq.kolomitm.event

import net.kyori.adventure.text.Component

class DisconnectEvent(val reason: Component) : KoloEvent {

    override fun toString(): String {
        return "DisconnectEvent(reason=$reason)"
    }

}