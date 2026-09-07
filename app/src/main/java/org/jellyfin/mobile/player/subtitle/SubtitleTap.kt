package org.jellyfin.mobile.player.subtitle

/** A subtitle glyph tap, with bounds expressed in the subtitle overlay's coordinates. */
data class SubtitleTap(
    val subtitleText: String,
    val characterOffset: Int,
    val characterBounds: PopupAnchorBounds,
)
