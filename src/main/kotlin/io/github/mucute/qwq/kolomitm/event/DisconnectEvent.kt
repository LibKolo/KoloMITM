package io.github.mucute.qwq.kolomitm.event

class DisconnectEvent(val reason: CharSequence) : KoloEvent {

    override fun toString(): String {
        return "DisconnectEvent(reason=$reason)"
    }

}