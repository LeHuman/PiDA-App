package com.pida.app.ui

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * Minimal wrap layout for chip-like children (no flexbox dependency):
 * children flow left-to-right and wrap onto a new line when the next one
 * would overflow. A child wider than the remaining space starts a new line;
 * a child wider than the whole line is measured down to the line width, so
 * text children with maxLines+ellipsize truncate gracefully instead of
 * clipping.
 *
 * Children must use [android.view.ViewGroup.MarginLayoutParams] (the default
 * for LinearLayout.LayoutParams).
 */
class FlowRowLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {

    private val childLeft = mutableMapOf<Int, Int>()
    private val childTop = mutableMapOf<Int, Int>()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val available = (widthSize - paddingLeft - paddingRight).coerceAtLeast(0)

        childLeft.clear()
        childTop.clear()

        var x = paddingLeft
        var y = paddingTop
        var rowHeight = 0
        var contentWidth = paddingLeft + paddingRight

        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val lp = child.layoutParams as MarginLayoutParams
            val verticalPadding = paddingTop + paddingBottom + lp.topMargin + lp.bottomMargin

            // Measure the child at its desired (unconstrained) width first.
            child.measure(
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                ViewGroup.getChildMeasureSpec(heightMeasureSpec, verticalPadding, lp.height)
            )
            var childWidth = child.measuredWidth
            val maxChildWidth = available - lp.leftMargin - lp.rightMargin
            if (childWidth > maxChildWidth && maxChildWidth > 0) {
                // Cap so singleLine+ellipsize can engage.
                child.measure(
                    MeasureSpec.makeMeasureSpec(maxChildWidth, MeasureSpec.AT_MOST),
                    ViewGroup.getChildMeasureSpec(heightMeasureSpec, verticalPadding, lp.height)
                )
                childWidth = child.measuredWidth
            }
            val childHeight = child.measuredHeight
            val advance = childWidth + lp.leftMargin + lp.rightMargin

            if (x > paddingLeft && x - paddingLeft + advance > available) {
                x = paddingLeft
                y += rowHeight
                rowHeight = 0
            }

            childLeft[i] = x + lp.leftMargin
            childTop[i] = y + lp.topMargin
            x += advance
            rowHeight = maxOf(rowHeight, childHeight + lp.topMargin + lp.bottomMargin)
            contentWidth = maxOf(contentWidth, x)
        }

        setMeasuredDimension(
            getDefaultSize(suggestedMinimumWidth, widthMeasureSpec),
            resolveSize(y + rowHeight + paddingBottom, heightMeasureSpec)
        )
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            val cl = childLeft[i] ?: continue
            val ct = childTop[i] ?: continue
            child.layout(cl, ct, cl + child.measuredWidth, ct + child.measuredHeight)
        }
    }
}
