package org.jellyfin.mobile.downloads

enum class DownloadFileType {
    /**
     * The main file for an item (e.g. the video file for episodes/movies, or the audio file for music).
     */
    ITEM,

    /**
     * The primary image for the item.
     */
    IMAGE_PRIMARY,

    /**
     * A Japanese or English text subtitle saved as WebVTT for offline lookup and mining. The file
     * name carries the Jellyfin stream index (see [DownloadedSubtitles]).
     */
    SUBTITLE,
}
