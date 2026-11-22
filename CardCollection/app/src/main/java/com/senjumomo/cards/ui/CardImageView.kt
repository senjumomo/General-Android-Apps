package com.senjumomo.cards.ui

import android.content.Context
import android.util.AttributeSet
import com.google.android.material.imageview.ShapeableImageView

/** Image view that keeps the proportions of a trading card (59 × 86 mm) based on its width. */
class CardImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : ShapeableImageView(context, attrs) {

    init {
        scaleType = ScaleType.CENTER_CROP
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = (width * 86f / 59f).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY),
        )
    }
}
