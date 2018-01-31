package com.taptrack.carrot

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import android.support.design.widget.Snackbar
import android.support.v7.app.AppCompatDelegate
import android.view.ViewGroup
import android.widget.ImageView
import com.jakewharton.rxrelay2.BehaviorRelay
import com.jakewharton.rxrelay2.PublishRelay
import com.karumi.dexter.Dexter
import com.karumi.dexter.PermissionToken
import com.karumi.dexter.listener.PermissionDeniedResponse
import com.karumi.dexter.listener.PermissionGrantedResponse
import com.karumi.dexter.listener.PermissionRequest
import com.karumi.dexter.listener.single.CompositePermissionListener
import com.karumi.dexter.listener.single.PermissionListener
import com.karumi.dexter.listener.single.SnackbarOnDeniedPermissionListener
import com.taptrack.carrot.findtappies.*
import com.taptrack.carrot.kioskcontrol.*
import com.taptrack.carrot.utils.getColorResTintedDrawable
import com.taptrack.tcmptappy2.ble.TappyBleDeviceDefinition
import com.taptrack.tcmptappy2.usb.UsbPermissionDelegate
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import org.jetbrains.anko.contentView
import timber.log.Timber


class MainActivity :
        android.support.v7.app.AppCompatActivity(),
        TappySearchViewModelProvider,
        KioskControlViewModelProvider {


    private val TAG = MainActivity::class.java.name

    private lateinit var permissionDelegate: UsbPermissionDelegate
    private lateinit var searchManager: SearchManagementDelegate

    private val handler = android.os.Handler(Looper.getMainLooper())

    private var preferencesDisposable: Disposable? = null

    private var isAutolaunchingEnabled: Boolean = false

    private val recreateRunnable = Runnable {
        recreate()
    }

    private var serviceBinder: TappyKioskService.TappyKioskServiceBinder? = null
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceDisconnected(name: ComponentName?) {
            serviceBinder = null
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            serviceBinder = service as? TappyKioskService.TappyKioskServiceBinder
            registerWithService()
        }

    }

    private var usbPermissionListener: UsbPermissionDelegate.PermissionListener = object : UsbPermissionDelegate.PermissionListener {
        override fun permissionDenied(device: UsbDevice) {
            Timber.i("Permission denied")
        }

        override fun permissionGranted(device: UsbDevice) {
            Timber.i(TAG, "Permission granted")
            connectToUsbDevice(device)
        }
    }

    private val searchViewModel = object: TappySearchViewModel {
        private var state = emptyList<ChoosableTappy>()
        private var stateRelay: BehaviorRelay<Collection<ChoosableTappy>> = BehaviorRelay.createDefault(state)
        private val stateMutationLock: Any = Any()

        override fun getChoosableTappies(): Observable<Collection<ChoosableTappy>> {
            return stateRelay
        }

        fun setSearchResults(bleDevices: Collection<TappyBleDeviceDefinition>, usbDevices: Collection<UsbDevice>) {
            synchronized(stateMutationLock) {
                val choosableBleDevices: List<ChoosableTappy> = bleDevices.map { ChoosableTappyBle(
                        id = it.address,
                        name = it.name,
                        description = it.address,
                        definition = it
                ) }

                val choosableUsbDevices: List<ChoosableTappy> = usbDevices.map {
                    ChoosableTappyUsb(
                            id = it.deviceId.toString(),
                            name = "TappyUSB",
                            description = it.deviceName,
                            device = it
                    )
                }

                val devices = choosableBleDevices.plus(choosableUsbDevices)
                state = devices
                stateRelay.accept(devices)
            }
        }

        override fun tappyBleSelected(definition: TappyBleDeviceDefinition) {
            this@MainActivity.connectToBleDevice(definition)
        }

        override fun tappyUsbSelected(device: UsbDevice) {
            permissionDelegate.requestPermission(device)
        }
    }

    override fun provideTappySearchViewModel(): TappySearchViewModel = searchViewModel


    private val kioskControlViewModel = object : KioskControlViewModel, ControlledKiosksListener {
        private val relay: PublishRelay<Collection<TappyKiosk>> = PublishRelay.create()

        override fun controlledKiosksChanged(newCollection: Collection<TappyKiosk>) {
            relay.accept(newCollection)
        }

        override fun getKioskControlEntries(): Observable<List<TappyKiosk>> = relay.map {
            it.toList()
        }
    }

    override fun provideKioskControlViewModel(): KioskControlViewModel = kioskControlViewModel

    private val coarseLocationListener = object: PermissionListener {
        override fun onPermissionGranted(response: PermissionGrantedResponse?) {
            searchManager.coarseLocationRequestResult(true)
        }

        override fun onPermissionRationaleShouldBeShown(permission: PermissionRequest?, token: PermissionToken?) {
        }

        override fun onPermissionDenied(response: PermissionDeniedResponse?) {
            searchManager.coarseLocationRequestResult(false)
        }
    }

    private var openUrlsButton: ImageView? = null

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(R.style.AppTheme)
        setContentView(R.layout.activity_main)

        val roaringApp = getCarrotApplication()

        openUrlsButton = findViewById(R.id.ib_launch_urls)
        openUrlsButton?.setOnClickListener({
            val localButtonCopy = openUrlsButton
            val shouldBeEnabled = !isAutolaunchingEnabled
            roaringApp.setAutolaunchEnabled(shouldBeEnabled)
            if (localButtonCopy != null) {
                if (shouldBeEnabled) {
                    Snackbar.make(localButtonCopy,R.string.automatic_url_launching_enabled,Snackbar.LENGTH_SHORT).show()
                } else {
                    Snackbar.make(localButtonCopy,R.string.automatic_url_launching_disabled,Snackbar.LENGTH_SHORT).show()
                }
            }
        })

        permissionDelegate = UsbPermissionDelegate(this, usbPermissionListener)
        searchManager = SearchManagementDelegate(this,object : SearchResultsListener {
            override fun searchResultsUpdated(
                    bleDevices: Collection<TappyBleDeviceDefinition>, usbDevices: Collection<UsbDevice>) {
                searchViewModel.setSearchResults(bleDevices,usbDevices)
            }
        })

        if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val snackbarPermissionListener = SnackbarOnDeniedPermissionListener.Builder
                    .with(contentView as ViewGroup, R.string.coarse_location_needed_rationale)
                    .withOpenSettingsButton(R.string.settings)
                    .build()
            Dexter.withActivity(this)
                    .withPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
                    .withListener(CompositePermissionListener(snackbarPermissionListener, coarseLocationListener))
                    .check()
        } else {
            searchManager.coarseLocationRequestResult(true)
        }

        addUsbDeviceFromIntent(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        addUsbDeviceFromIntent(intent)
    }

    private fun addUsbDeviceFromIntent(intent: Intent?) {
        if (intent != null && intent.hasExtra(UsbManager.EXTRA_DEVICE)) {
            val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
            connectToUsbDevice(device)
        }
    }

    private fun connectToUsbDevice(device: UsbDevice) {
        Snackbar.make(contentView!!,R.string.kiosk_usb_no_support, Snackbar.LENGTH_SHORT).show()
//        handler.postDelayed({
//            serviceBinder?.requestConnectToTappyUsb(device)
//        }, 32)
    }

    private fun connectToBleDevice(device: TappyBleDeviceDefinition) {
        serviceBinder?.requestConnectToTappyBle(device)
    }

    private fun registerWithService() {
        serviceBinder?.registerKioskControlListener(kioskControlViewModel,true)
    }

    private fun unregisterFromService() {
        serviceBinder?.unregisterKioskControlListener(kioskControlViewModel)
    }

    private fun postRecreate(delay: Long) {
        cancelPendingRecreate()
        handler.postDelayed(recreateRunnable,delay)
    }

    private fun cancelPendingRecreate(){
        handler.removeCallbacks(recreateRunnable)
    }

    override fun onStart() {
        super.onStart()

        val app = getCarrotApplication()
        preferencesDisposable = app.getAutolaunchEnabled()
                .subscribe {
            isAutolaunchingEnabled = it
            handler.post({
                resetOpenUrlsButton()
            })
        }

        permissionDelegate.register()
        bindService(Intent(this, TappyKioskService::class.java),serviceConnection, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)

    }

    private fun resetOpenUrlsButton() {
        if (isAutolaunchingEnabled) {
            openUrlsButton?.setImageDrawable(getColorResTintedDrawable(R.drawable.ic_open_links_in_browser_black_24dp,R.color.autolaunchIconColor))
        } else {
            openUrlsButton?.setImageDrawable(getColorResTintedDrawable(R.drawable.ic_dont_open_links_in_browser_black_24dp,R.color.autolaunchIconColor))
        }
    }

    override fun onResume() {
        super.onResume()
        searchManager.resume()
        searchManager.requestActivate()
    }

    override fun onPause() {
        super.onPause()
        searchManager.requestDeactivate()
        searchManager.pause()
    }

    override fun onStop() {
        super.onStop()
        preferencesDisposable?.dispose()
        cancelPendingRecreate()

        unregisterFromService()
        unbindService(serviceConnection)
        permissionDelegate.unregister()
    }


    companion object {
        init {
            AppCompatDelegate.setCompatVectorFromResourcesEnabled(true)
        }
    }
}

