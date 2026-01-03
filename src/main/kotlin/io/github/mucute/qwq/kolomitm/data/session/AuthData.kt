package io.github.mucute.qwq.kolomitm.data.session

import java.util.UUID

data class AuthData(
    var displayName: String,
    var identity: UUID,
    var xuid: String
)