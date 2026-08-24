package com.batz.tvlauncher.util

import android.graphics.Color
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target

/**
 * No third-party artwork ships with this app. Every icon/card image is either:
 *  1. loaded at runtime from the `iconUrl` you supply in JSON, or
 *  2. rendered as a deterministic letter-avatar (first initial on a flat color) when
 *     no URL is given, or the network load fails — so the UI is always fully populated
 *     even with an empty/offline JSON config.
 */
object ImageLoader {

    private val fallbackPalette = listOf(
        "#5C6BC0", "#26A69A", "#7E57C2", "#EF6C00", "#00897B", "#C2185B", "#3949AB", "#00ACC1"
    )

    fun initialFor(label: String): String =
        label.trim().firstOrNull()?.uppercaseChar()?.toString() ?: "•"

    fun colorFor(hex: String?, seed: String): Int {
        if (!hex.isNullOrBlank()) {
            return try {
                Color.parseColor(hex)
            } catch (e: IllegalArgumentException) {
                colorFromSeed(seed)
            }
        }
        return colorFromSeed(seed)
    }

    private fun colorFromSeed(seed: String): Int {
        val index = (seed.hashCode().let { if (it < 0) -it else it }) % fallbackPalette.size
        return Color.parseColor(fallbackPalette[index])
    }

    /**
     * Loads [url] into [imageView]; while loading/on failure, [initialView] (a letter avatar)
     * stays visible behind it so there's never a blank tile.
     */
    fun load(
        imageView: ImageView,
        initialView: TextView?,
        url: String?,
        label: String,
        placeholderColorHex: String?
    ) {
        val bgColor = colorFor(placeholderColorHex, seed = label.ifBlank { url ?: "x" })
        initialView?.text = initialFor(label)
        initialView?.setBackgroundColor(bgColor)
        initialView?.visibility = android.view.View.VISIBLE

        if (url.isNullOrBlank()) {
            imageView.setImageDrawable(null)
            return
        }

        Glide.with(imageView.context)
            .load(url)
            .listener(object : RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<android.graphics.drawable.Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    // keep the letter-avatar visible, nothing further to do
                    return false
                }

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable,
                    model: Any,
                    target: Target<android.graphics.drawable.Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    initialView?.visibility = android.view.View.GONE
                    return false
                }
            })
            .into(imageView)
    }
}
