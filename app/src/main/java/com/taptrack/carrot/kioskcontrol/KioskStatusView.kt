package com.taptrack.carrot.kioskcontrol

import android.animation.ValueAnimator
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Build
import android.support.annotation.AnyThread
import android.support.annotation.RequiresApi
import android.support.annotation.UiThread
import android.support.v4.content.ContextCompat
import android.support.v4.widget.ImageViewCompat
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import com.taptrack.carrot.R
import org.jetbrains.anko.image
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference


class KioskStatusView : FrameLayout {
    private var currentState: KioskStatus? = null
    private val nextStateRef = AtomicReference<KioskStatus>(null)

    private val lastHeartbeatRef = AtomicLong(0)

    private lateinit var statusIcon: ImageView
    private lateinit var loadingIndicator: ProgressBar

    private var heartbeatAnimator: ValueAnimator? = null

    constructor(context: Context) : this(context,null)
    constructor(context: Context, attrs: AttributeSet?) : this(context, attrs, 0)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        initialize(context)
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    constructor(context: Context, attrs: AttributeSet?, defStyleAttr: Int, defStyleRes: Int) : super(context, attrs, defStyleAttr, defStyleRes) {
        initialize(context)
    }

    private fun initialize(context:Context) {
        LayoutInflater.from(context).inflate(R.layout.view_tappy_status,this)
        statusIcon = findViewById(R.id.iv_status_icon)
        loadingIndicator = findViewById(R.id.pb_connecting_indicator)
    }

    @AnyThread
    fun setStatus(nextState: KioskStatus) {
        nextStateRef.set(nextState)
        post {
            update()
        }
    }

    @AnyThread
    fun setLastHeartbeat(timestamp: Long) {
        lastHeartbeatRef.set(timestamp)
        post {
            update()
        }
    }

    fun clearHeartbeat() {
        lastHeartbeatRef.set(0)
        post {
            update()
        }
    }

    @UiThread
    private fun update() {
        val localNext = nextStateRef.get()
        if (localNext != currentState) {
            var loadingVis = View.INVISIBLE
            var iconVis = View.INVISIBLE
            when(localNext) {
                KioskStatus.Connecting, KioskStatus.Disconnecting -> {
                    loadingVis = View.VISIBLE
                }
                KioskStatus.HeartbeatGood -> {
                    iconVis = View.VISIBLE
                    statusIcon.image = ContextCompat.getDrawable(context,R.drawable.ic_heartbeat_good_black_24dp)
                }
                KioskStatus.HeartbeatFailure -> {
                    iconVis = View.VISIBLE
                    statusIcon.image = ContextCompat.getDrawable(context,R.drawable.ic_heartbeat_off_black_24px)
                }
                KioskStatus.Closed -> {
                    iconVis = View.VISIBLE
                    statusIcon.image = ContextCompat.getDrawable(context,R.drawable.ic_cloud_off_black_24dp)
                }
                KioskStatus.Unknown -> {
                    iconVis = View.VISIBLE
                    statusIcon.image = ContextCompat.getDrawable(context,R.drawable.ic_help_outline_black_24dp)
                }
            }

            statusIcon.visibility = iconVis
            loadingIndicator.visibility = loadingVis

            currentState = localNext
        }

        if (currentState == KioskStatus.HeartbeatGood) {
            val timestamp = lastHeartbeatRef.get()
            val currentTime = System.currentTimeMillis()
            val diff = currentTime - timestamp
            if (diff < 0 || diff > HEARTBEAT_RIPPLE_DURATION_MS ) {
                heartbeatAnimator?.cancel()
                ImageViewCompat.setImageTintList(
                        statusIcon,
                        BLACK_COLOR_STATE_LIST
                )
                return
            } else {
                heartbeatAnimator?.cancel()

                val startPoint = (1.0f - (diff.toFloat() / HEARTBEAT_RIPPLE_DURATION_MS.toFloat()))
                val scaledDuration = HEARTBEAT_RIPPLE_DURATION_MS - diff
                val animator = ValueAnimator.ofFloat(startPoint,0f)
                        .setDuration(scaledDuration)

                animator.addUpdateListener {
                    val lightness = it.animatedValue as Float
                    ImageViewCompat.setImageTintList(
                            statusIcon,
                            ColorStateList.valueOf(Color.HSVToColor(floatArrayOf(0f,1f,lightness)))
                    )
                }
                animator.start()
                heartbeatAnimator = animator
            }
        } else {
            heartbeatAnimator?.cancel()
            ImageViewCompat.setImageTintList(
                    statusIcon,
                    BLACK_COLOR_STATE_LIST
            )
        }
    }

    companion object {
        val HEARTBEAT_RIPPLE_DURATION_MS = 2000L

        val BLACK_COLOR_STATE_LIST =
                ColorStateList.valueOf(Color.HSVToColor(floatArrayOf(0f,1f,0f)))
    }
}