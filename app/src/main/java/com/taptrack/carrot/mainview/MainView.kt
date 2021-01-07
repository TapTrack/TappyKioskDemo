package com.taptrack.carrot.mainview

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.widget.NestedScrollView
import com.taptrack.carrot.R
import com.taptrack.carrot.findtappies.TappySearchView
import com.taptrack.carrot.findtappies.tappySearchView
import com.taptrack.carrot.kioskcontrol.KioskControlView
import com.taptrack.carrot.kioskcontrol.kioskControlView
import com.taptrack.carrot.utils.setTextAppearanceCompat
import org.jetbrains.anko.*
import org.jetbrains.anko.custom.ankoView

fun ViewManager.chooseTappiesView() = chooseTappiesView { }

inline fun ViewManager.chooseTappiesView(init: ChooseTappiesView.() -> Unit): ChooseTappiesView {
    return ankoView({ ChooseTappiesView(it) }, theme = 0, init = init)
}

class ChooseTappiesView : NestedScrollView {

    private lateinit var tappyControlView: KioskControlView
    private lateinit var tappySearchView: TappySearchView
    private lateinit var loadingIndicator: ProgressBar
    private lateinit var activeHeadingView: TextView
    private lateinit var searchHeadingView: TextView

    constructor(context: Context) :
            super(context) {
        init(context)
    }
    constructor(context: Context, attrs: AttributeSet?) :
            super(context, attrs) {
        init(context)
    }
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) :
            super(context, attrs, defStyleAttr) {
        init(context)
    }

    private fun init(context: Context) {

        val llLayoutParams = MarginLayoutParams(matchParent, matchParent)
        linearLayout {
            orientation = LinearLayout.VERTICAL

            activeHeadingView = themedTextView {
                text = context.getString(R.string.active_devices_heading)
                layoutParams = LayoutParams(matchParent, wrapContent)
            }
            activeHeadingView.setTextAppearanceCompat(R.style.TextAppearance_AppCompat_Medium)

            tappyControlView = kioskControlView {

            }.lparams(matchParent, wrapContent)

            searchHeadingView = themedTextView() {
                textResource = R.string.select_tappy_text
            }.lparams(matchParent, wrapContent)

            searchHeadingView.setTextAppearanceCompat(R.style.TextAppearance_AppCompat_Medium)

            tappySearchView = tappySearchView {

            }.lparams(matchParent, wrapContent)

            loadingIndicator = progressBar {
                isIndeterminate = true
            }.lparams(width = wrapContent, height = wrapContent) {
                topMargin = dip(16)
                gravity = Gravity.CENTER_HORIZONTAL
            }

            padding = dip(16)
            layoutParams = llLayoutParams
        }

        reset()
    }

    private fun reset() {
    }



    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
    }
}
