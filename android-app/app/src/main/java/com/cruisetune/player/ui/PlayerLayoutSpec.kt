package com.cruisetune.player.ui

/** Layout decisions use logical window dimensions, never the physical screen's pixel count. */
data class PlayerLayoutSpec(val landscape: Boolean, val compact: Boolean, val showModes: Boolean, val showCover: Boolean) {
    companion object {
        fun forWindow(widthDp: Int, heightDp: Int, fontScale: Float): PlayerLayoutSpec {
            val landscape = widthDp > heightDp
            val compact = heightDp < 600 || (landscape && fontScale > 1.3f)
            return PlayerLayoutSpec(landscape, compact, landscape && widthDp >= 900 && fontScale <= 1.3f,
                (!compact || landscape) && fontScale <= 1.3f)
        }
    }
}
