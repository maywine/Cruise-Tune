package com.cruisetune.player.ui

/** Layout decisions use logical window dimensions, never the physical screen's pixel count. */
data class PlayerLayoutSpec(val landscape: Boolean, val compact: Boolean, val showCover: Boolean, val inlineDetails: Boolean = false) {
    companion object {
        fun forWindow(widthDp: Int, heightDp: Int, fontScale: Float): PlayerLayoutSpec {
            val landscape = widthDp > heightDp
            // Reserve two list rows before opting into the expanded portrait card.
            val expandedInfo = maxOf(80f, (68f + 24f + 22f) * fontScale + 16f)
            val expandedCard = 36f + 44f + 12f + expandedInfo + 64f + 24f * fontScale + if(heightDp >= 700) 88f else 0f
            val expandedMinimum = 48f + 14f + 80f + 84f + 64f + 38f * fontScale + expandedCard + 168f
            val compact = heightDp < 600 || (landscape && fontScale > 1.3f) || (!landscape && heightDp < expandedMinimum)
            val inlineDetails = if(landscape) heightDp >= 480 + (fontScale - 1f).coerceAtLeast(0f) * 180
                else heightDp >= 900 + (fontScale - 1f).coerceAtLeast(0f) * 300
            return PlayerLayoutSpec(landscape, compact, (!compact || landscape) && fontScale <= 1.3f, inlineDetails)
        }
    }
}
