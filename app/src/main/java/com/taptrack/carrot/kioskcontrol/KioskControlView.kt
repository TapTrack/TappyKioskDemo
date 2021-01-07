package com.taptrack.carrot.kioskcontrol

import android.content.Context
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.ViewManager
import android.widget.ImageButton
import android.widget.TextView
import com.taptrack.carrot.R
import com.taptrack.carrot.utils.getHostActivity
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import org.jetbrains.anko.custom.ankoView

interface KioskControlViewModel {
    fun getKioskControlEntries(): Observable<List<TappyKiosk>>
}

interface KioskControlViewModelProvider {
    fun provideKioskControlViewModel(): KioskControlViewModel
}

fun ViewManager.kioskControlView() = kioskControlView { }

inline fun ViewManager.kioskControlView(init: KioskControlView.() -> Unit): KioskControlView {
    return ankoView({ KioskControlView(it) }, theme = 0, init = init)
}

class KioskControlView : RecyclerView {
    private lateinit var kioskAdapter: KioskControlAdapter

    private var vm: KioskControlViewModel? = null
    private var disposable: Disposable? = null

    constructor(context: Context) : super(context) {
        initTappyControlView(context)
    }

    constructor(context: Context,
                attrs: AttributeSet?) : super(context, attrs) {
        initTappyControlView(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        initTappyControlView(context)
    }

    private fun initTappyControlView(context: Context) {
        layoutManager = LinearLayoutManager(context)
        kioskAdapter = KioskControlAdapter()
        adapter = kioskAdapter
    }

    fun setKioskEntries(kioskEntries: List<TappyKiosk>) {
        kioskAdapter.updateEntries(kioskEntries.sortedBy {
            it.getName()+it.getId()
        })
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vm = (getHostActivity() as? KioskControlViewModelProvider)?.provideKioskControlViewModel()
        disposable = vm?.getKioskControlEntries()?.subscribe {
            post { setKioskEntries(it) }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        disposable?.dispose()
    }
}

private class DiffutilCb(
        private val oldEntries: List<TappyKiosk>,
        private val newEntries: List<TappyKiosk>
): DiffUtil.Callback() {
    override fun areItemsTheSame(oldIdx: Int, newIdx: Int): Boolean =
            oldEntries[oldIdx].getId() == newEntries[newIdx].getId()
    override fun getOldListSize(): Int = oldEntries.size
    override fun getNewListSize(): Int = newEntries.size
    override fun areContentsTheSame(oldIdx: Int, newIdx: Int): Boolean =
            oldEntries[oldIdx].getName() == newEntries[newIdx].getName()
}

private class KioskControlAdapter : RecyclerView.Adapter<KioskControlAdapter.VH>() {
    private var tappies: List<TappyKiosk> = emptyList()

    fun updateEntries(newEntries: List<TappyKiosk>) {
        val oldEntries = tappies
        val diffResult = DiffUtil.calculateDiff(DiffutilCb(oldEntries,newEntries))
        tappies = newEntries
        diffResult.dispatchUpdatesTo(this)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tappy = tappies[position]
        holder.bind(tappy)
    }

    override fun onViewAttachedToWindow(holder: VH) {
        super.onViewAttachedToWindow(holder)
        holder.subscribe()
    }

    override fun onViewDetachedFromWindow(holder: VH) {
        super.onViewDetachedFromWindow(holder)
        holder.unsubscribe()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        return VH(LayoutInflater.from(parent.context!!).inflate(R.layout.list_kiosk_control,parent,false) as ViewGroup)
    }

    override fun getItemCount(): Int = tappies.size

    class VH(root: ViewGroup): RecyclerView.ViewHolder(root) {
        val titleView = root.findViewById<TextView>(R.id.tv_title)!!
        val statusView = root.findViewById<KioskStatusView>(R.id.ksv_tappy_status)!!
        val removeBtn = root.findViewById<ImageButton>(R.id.ib_secondary_icon)!!

        var tappyKioskControl: TappyKiosk? = null

        val statusListener = object : KioskStatusListener {
            override fun onKioskStatusUpdate(status: KioskStatus) {
                statusView.setStatus(status)
            }
        }

        val heartbeatListener = object: KioskHeartbeatListener {
            override fun onHeartbeatReceived(timeReceived: Long) {
                statusView.setLastHeartbeat(timeReceived)
            }
        }

        init {
            removeBtn.setOnClickListener {
                tappyKioskControl?.requestClose()
            }
        }

        fun bind(entry: TappyKiosk) {
            unsubscribe()
            tappyKioskControl = entry
            titleView.text = entry.getName()
            statusView.clearHeartbeat()
        }

        fun subscribe() {
            tappyKioskControl?.addStatusListener(statusListener)
            tappyKioskControl?.addHeartbeatListener(heartbeatListener)
        }

        fun unsubscribe() {
            tappyKioskControl?.removeHeartbeatListener(heartbeatListener)
            tappyKioskControl?.removeStatusListener(statusListener)
        }
    }
}
