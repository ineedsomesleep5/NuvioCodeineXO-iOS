package com.nuvio.app.features.settings

// Discord Rich Presence needs the desktop Discord client's local IPC socket, which iOS
// apps cannot reach. isSupported=false hides every Discord control on iOS.
internal actual object DiscordRichPresencePlatform {
    actual val isSupported: Boolean = false
}

internal actual object DiscordRichPresenceStorage {
    actual fun loadEnabled(): Boolean? = null
    actual fun saveEnabled(enabled: Boolean) = Unit
    actual fun loadActivityMode(): DiscordActivityMode? = null
    actual fun saveActivityMode(mode: DiscordActivityMode) = Unit
    actual fun loadActivityType(): DiscordActivityType? = null
    actual fun saveActivityType(type: DiscordActivityType) = Unit
    actual fun loadEpisodeSmallImageEnabled(): Boolean? = null
    actual fun saveEpisodeSmallImageEnabled(enabled: Boolean) = Unit
    actual fun loadEpisodeNameEnabled(): Boolean? = null
    actual fun saveEpisodeNameEnabled(enabled: Boolean) = Unit
    actual fun loadFullPosterEnabled(): Boolean? = null
    actual fun saveFullPosterEnabled(enabled: Boolean) = Unit
}
