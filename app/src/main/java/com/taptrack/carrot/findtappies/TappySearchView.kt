package com.taptrack.carrot.findtappies

import android.content.Context
import android.hardware.usb.UsbDevice
import androidx.annotation.UiThread
import androidx.recyclerview.widget.DiffUtil
import androidx.appcompat.widget.AppCompatImageView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewManager
import android.widget.TextView
import com.taptrack.carrot.R
import com.taptrack.carrot.utils.getHostActivity
import com.taptrack.tcmptappy2.ble.TappyBleDeviceDefinition
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import org.jetbrains.anko.custom.ankoView
import org.jetbrains.anko.find

interface ChoosableTappy {
    val id: String
    val name: String
    val description: String
}

data class ChoosableTappyBle(
        override val id: String,
        override val name: String,
        override val description: String,
        val definition: TappyBleDeviceDefinition) : ChoosableTappy

data class ChoosableTappyUsb(
        override val id: String,
        override val name: String,
        override val description: String,
        val device: UsbDevice) : ChoosableTappy


interface TappySearchViewModel {
    fun getChoosableTappies(): Observable<Collection<ChoosableTappy>>

    fun tappyBleSelected(definition: TappyBleDeviceDefinition)
    fun tappyUsbSelected(device: UsbDevice)
}

interface TappySelectionListener {
    fun tappyBleSelected(definition: TappyBleDeviceDefinition)
    fun tappyUsbSelected(device: UsbDevice)
}

interface TappySearchViewModelProvider {
    fun provideTappySearchViewModel(): TappySearchViewModel
}

fun ViewManager.tappySearchView() = tappySearchView { }

inline fun ViewManager.tappySearchView(init: TappySearchView.() -> Unit): TappySearchView {
    return ankoView({ TappySearchView(it) }, theme = 0, init = init)
}

class TappySearchView : RecyclerView {
    private lateinit var adapter: TappySearchAdapter
    var currentTappies: Collection<ChoosableTappy> = emptySet()
        set(value) {
            field = value
            reset()
        }

    private var vm: TappySearchViewModel? = null
    private var disposable: Disposable? = null

    private val deviceSorter = Comparator<ChoosableTappy> { o1, o2 ->
        if (o1 is ChoosableTappyUsb && o2 !is ChoosableTappyUsb) {
            1
        } else if (o1 !is ChoosableTappyUsb && o2 is ChoosableTappyUsb) {
            -1
        } else {
            o1.name.compareTo(o2.name)
        }
    }

    constructor(context: Context) : super(context) {
        initTappySearchView(context)
    }

    constructor(context: Context,
                attrs: AttributeSet?) : super(context, attrs) {
        initTappySearchView(context)
    }

    constructor(context: Context, attrs: AttributeSet?, defStyle: Int) : super(context, attrs, defStyle) {
        initTappySearchView(context)
    }

    private fun initTappySearchView(context: Context) {
        layoutManager = LinearLayoutManager(context)
        adapter = TappySearchAdapter()
        adapter.setTappySelectionListener(object : TappySelectionListener {
            override fun tappyBleSelected(definition: TappyBleDeviceDefinition) {
                vm?.tappyBleSelected(definition)
            }

            override fun tappyUsbSelected(device: UsbDevice) {
                vm?.tappyUsbSelected(device)
            }
        })
        setAdapter(adapter)
        currentTappies = emptySet()
    }

    @UiThread
    private fun reset() {
        val deviceList = currentTappies.toList().sortedWith(deviceSorter)
        adapter.setDevices(deviceList)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        vm = (getHostActivity() as? TappySearchViewModelProvider)?.provideTappySearchViewModel()
        disposable = vm?.getChoosableTappies()?.subscribe {
            post { currentTappies = it }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        disposable?.dispose()
    }
}

private class TappySearchAdapter : RecyclerView.Adapter<TappySearchAdapter.VH>() {
    private var tappySelectionListener: TappySelectionListener? = null
    private var deviceList: List<ChoosableTappy>

    class TappyDeviceDiffCb(private val newTappies: List<ChoosableTappy>, private val oldTappies: List<ChoosableTappy>) : DiffUtil.Callback() {

        override fun getOldListSize(): Int {
            return oldTappies.size
        }

        override fun getNewListSize(): Int {
            return newTappies.size
        }

        override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return newTappies[newItemPosition].id == oldTappies[oldItemPosition].id
        }

        override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean {
            return newTappies[newItemPosition] == oldTappies[oldItemPosition]
        }
    }

    init {
        deviceList = emptyList()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val inflater = LayoutInflater.from(parent.context)
        return VH(inflater.inflate(R.layout.search_tappy_control, parent, false))
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val tappy = deviceList[position]
        holder.bind(tappy)
    }

    override fun getItemCount(): Int {
        return deviceList.size
    }

    fun setTappySelectionListener(tappySelectionListener: TappySelectionListener?) {
        this.tappySelectionListener = tappySelectionListener
    }

    fun setDevices(newList: List<ChoosableTappy>) {
        val result = DiffUtil.calculateDiff(TappyDeviceDiffCb(newList, this.deviceList))
        this.deviceList = newList
        result.dispatchUpdatesTo(this)
    }

    inner class VH(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: AppCompatImageView = itemView.find<AppCompatImageView>(R.id.iv_icon)
        private val tappyTitleView: TextView = itemView.find<TextView>(R.id.tv_title)
        private val tappySubtitleView: TextView = itemView.find<TextView>(R.id.tv_subtitle)

        private var currentDevice: ChoosableTappy? = null

        init {
            itemView.setOnClickListener {
                val device = currentDevice
                if (device != null && tappySelectionListener != null) {
                    when (device) {
                        is ChoosableTappyBle -> tappySelectionListener?.tappyBleSelected(device.definition)
                        is ChoosableTappyUsb -> tappySelectionListener?.tappyUsbSelected(device.device)
                    }
                }
            }
        }

        fun bind(device: ChoosableTappy) {
            tappyTitleView.text = device.name
            tappySubtitleView.text = device.description
            currentDevice = device
            when (device) {
                is ChoosableTappyBle -> iconView.setImageResource(R.drawable.ic_bluetooth_black_24dp)
                is ChoosableTappyUsb -> iconView.setImageResource(R.drawable.ic_usb_black_24dp)
            }
        }
    }
}
