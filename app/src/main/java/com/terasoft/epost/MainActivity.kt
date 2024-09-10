package com.terasoft.epost

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.PermissionRequest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.print.PrintAttributes
import android.print.PrintManager
import android.util.Log
import android.webkit.JavascriptInterface
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.app.ActivityCompat
import com.google.gson.Gson
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    //    private val printerMACAddress = "86:67:7A:47:EE:44"
    private val printerMACAddress = "DC:0D:51:2C:BD:B5"
    private val uuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb")
    private val handler = Handler(Looper.getMainLooper())
    private val reconnectDelay = 10000L
    private var selectedDeviceAddress: String? = "DC:0D:51:2C:BD:B5"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissions()

        setUpWebview()

        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
            return
        }

        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED))
    }

    private fun requestPermissions() {
        if (ActivityCompat.checkSelfPermission(
                this,
                android.Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.CAMERA
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                    this,
            Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.BLUETOOTH_SCAN
            ), 1)
        }
    }

    private fun setUpWebview() {
        webView = findViewById(R.id.webView)
        webView.webViewClient = WebViewClient()
        webView.addJavascriptInterface(WebAppInterface(this), "Android")
        webView.loadUrl("https://m.epos.pandumedia.com")
        webView.settings.javaScriptEnabled = true
        webView.settings.mediaPlaybackRequiresUserGesture = false
        webView.settings.setSupportZoom(true)
        webView.settings.domStorageEnabled = true
        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.grant(request.resources)
            }
        }
    }

    private fun connectToPrinter() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        }

        try {
            val savedPrinterAddress = getSavedPrinterAddress()
            val device: BluetoothDevice? = bluetoothAdapter?.getRemoteDevice(savedPrinterAddress)
            if (device == null) {
                webView.evaluateJavascript("javascript:displayMessages('Printer tidak ditemukan!', 0)", null)
                Toast.makeText(this, "Printer tidak ditemukan", Toast.LENGTH_SHORT).show()
                return
            }

            bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
            bluetoothSocket?.connect()

            if (bluetoothSocket?.isConnected == true) {
                outputStream = bluetoothSocket?.outputStream
            } else {
                webView.evaluateJavascript("javascript:displayMessages('Printer tidak ditemukan!', 0)", null)
                throw IOException("Socket is not connected")
            }

            if (outputStream == null) {
                webView.evaluateJavascript("javascript:displayMessages('Printer tidak ditemukan!', 0)", null)
//                Toast.makeText(this, "Error connecting to printer: output stream is null", Toast.LENGTH_SHORT).show()
                closeConnection()
                return
            }

            webView.evaluateJavascript("javascript:displayMessages('Printer berhasil terhubung', 1)", null)
            return
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("cek1", e.message.toString())
//            Toast.makeText(this, "Printer Thermal tidak ditemukan", Toast.LENGTH_SHORT).show()
            closeConnection()
            return
        }
    }

    private fun printData(data: String) {
        try {
            if (outputStream == null) {
                connectToPrinter()
            }

            outputStream?.write(data.toByteArray())
            webView.evaluateJavascript("javascript:displayMessages('Nota berhasil di cetak', 1)", null)
            return
        } catch (e: IOException) {
            e.printStackTrace()
            Log.e("cek2", e.message.toString())
            webView.evaluateJavascript("javascript:displayMessages('Printer tidak ditemukan!', 0)", null)
            Toast.makeText(this, "Printer Thermal tidak ditemukan", Toast.LENGTH_SHORT).show()
            closeConnection()
            return
        }
    }

    private fun closeConnection() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            outputStream = null
            bluetoothSocket = null
        }
    }

    inner class WebAppInterface(private val context: Context) {
        @JavascriptInterface
        fun printPage(data: String) {
            runOnUiThread {
                if (outputStream == null) {
                    connectToPrinter()
                }
                printData(data)
            }
        }

        @JavascriptInterface
        fun scanBluetoothDevices() {
            runOnUiThread {
                if (bluetoothAdapter?.isEnabled == true) {
                    Log.e("cek", "Sudah sampe sini 0")
                    startDiscovery()
                } else {
                    Toast.makeText(context, "Bluetooth not enabled", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun selectBluetoothDevice(deviceAddress: String) {
            selectedDeviceAddress = deviceAddress
            val sharedPreferences = getSharedPreferences("PrinterPrefs", Context.MODE_PRIVATE)
            val editor = sharedPreferences.edit()
            editor.putString("printer_mac_address", deviceAddress)
            editor.apply()
            connectToPrinter()
        }
    }

    private fun startDiscovery() {
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                1
            )
            Log.e("cek", "Sudah sampe sini -1")
//            return
        }

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)

        bluetoothAdapter?.startDiscovery()
        Log.e("cek", "Sudah sampe sini 1")
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ActivityCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(context as Activity, arrayOf(android.Manifest.permission.BLUETOOTH_SCAN), 1)
            }
            Log.e("cek", intent.action.toString())
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        Log.e("cek", "Sudah sampe sini 2")
                        val deviceInfo = mapOf("name" to it.name, "address" to it.address)
                        val devicesJson = Gson().toJson(deviceInfo)
                        runOnUiThread {
                            webView.evaluateJavascript("javascript:displayDevices('$devicesJson')", null)
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.e("cek", "Sudah sampe sini 3")
                    runOnUiThread {
                        webView.evaluateJavascript("javascript:discoveryFinished()", null)
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    device?.let {
                        if (it.address == printerMACAddress) {
                            connectToPrinter()
                        }
                    }
                }
            }
        }
    }

    private fun getSavedPrinterAddress(): String? {
        Log.e("cek", "Sudah sampe sini 4")
        val sharedPreferences = getSharedPreferences("PrinterPrefs", Context.MODE_PRIVATE)
        return sharedPreferences.getString("printer_mac_address", printerMACAddress)
    }

    override fun onBackPressed() {
        if(webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeConnection()
        unregisterReceiver(bluetoothReceiver)
    }
}