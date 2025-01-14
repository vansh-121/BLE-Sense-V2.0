package com.example.blegame

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.ImageButton
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class GameDeviceListActivity : AppCompatActivity() {

    private lateinit var bleAdapter: BluetoothAdapterWrapper
    private lateinit var deviceAdapter: GameDeviceAdapter
    private val deviceList = mutableListOf<BLEDevice>()
    private val deviceRSSI = mutableMapOf<String, Int>() // To store the device and its RSSI value
    private val deviceAddresses = mutableSetOf<String>() // To track the MAC addresses of the devices
    private var selectedDevice: String? = null // Store the selected device
    private var isScanning = false

    private val targetDevices = setOf(
        "Scarlet Witch", "Black Widow", "Captain Marvel", "Wasp", "Hela",
        "Hulk", "Thor", "Iron_Man", "Spider Man", "Captain America"
    ) // List of target device names

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.game_device_list)

        bleAdapter = BluetoothAdapterWrapper(this)

        val deviceRecyclerView: RecyclerView = findViewById(R.id.deviceRecyclerView)
        deviceAdapter = GameDeviceAdapter(deviceList) { selectedDevice ->
            // On item click, handle the selected device
            handleDeviceClick(selectedDevice)
        }
        deviceRecyclerView.layoutManager = LinearLayoutManager(this)
        deviceRecyclerView.adapter = deviceAdapter

        val backButton: ImageButton = findViewById(R.id.backButton)
        val autoRenewButton: ImageButton = findViewById(R.id.autorenew)

        backButton.setOnClickListener {
            finish() // Go back to the previous activity
        }

        autoRenewButton.setOnClickListener {
            refreshBLEScan() // Trigger BLE rescan when the button is clicked
        }

        bleAdapter.checkAndEnableBluetooth()
        startBLEScan()

        // Retrieve the previously selected device if any
        selectedDevice = intent.getStringExtra("SELECTED_DEVICE")
    }

    private fun handleDeviceClick(device: BLEDevice) {
        selectedDevice = "${device.name} (${device.address})"

        val intent = Intent(this, GameplayActivity::class.java)
        intent.putExtra("DEVICE_NAME", device.name)
        intent.putExtra("DEVICE_ADDRESS", device.address)
        intent.putExtra("IS_FOUND", device.isFound) // Add this line to track found status
        startActivityForResult(intent, GAMEPLAY_ACTIVITY_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GAMEPLAY_ACTIVITY_REQUEST_CODE && resultCode == RESULT_OK) {
            val foundDeviceName = data?.getStringExtra("FOUND_DEVICE_NAME")
            val foundDeviceAddress = data?.getStringExtra("FOUND_DEVICE_ADDRESS")

            if (foundDeviceName != null && foundDeviceAddress != null) {
                // Create a found device entry with isFound flag set to true
                val foundDeviceEntry = BLEDevice(
                    name = foundDeviceName,
                    address = foundDeviceAddress,
                    rssi = "[Found]",
                    isFound = true // Set found flag
                )

                // Update the device list
                val existingIndex = deviceList.indexOfFirst { it.address == foundDeviceAddress }
                if (existingIndex != -1) {
                    deviceList[existingIndex] = foundDeviceEntry // Replace existing device
                } else {
                    deviceList.add(0, foundDeviceEntry) // Add at the top
                }
                deviceAdapter.notifyDataSetChanged()
            }
        }
    }


    @RequiresApi(Build.VERSION_CODES.O)
    private fun refreshBLEScan() {
        stopBLEScan()
        // Instead of clearing everything, only clear non-found devices
        deviceList.removeAll { !it.isFound }
        deviceRSSI.clear()
        deviceAddresses.clear()
        deviceAdapter.notifyDataSetChanged()
        startBLEScan()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun startBLEScan() {
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(
                this,
                bleAdapter.getRequiredPermissions(),
                REQUEST_CODE_PERMISSIONS
            )
            return
        }

        val bluetoothLeScanner = bleAdapter.bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            showToast("BLE Scanner not available.")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false) // Ensure support for Bluetooth 5 Advertising Extension
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) // Target Primary PHY as LE 1M
            .build()

        val scanFilter = ScanFilter.Builder().build()

        if (!isScanning) {
            isScanning = true
            showToast("Starting BLE Scan...")

            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }

            // Start scanning
            bluetoothLeScanner.startScan(
                listOf(scanFilter),
                scanSettings,
                bleScanCallback
            )

            // Stop scan after 10 seconds
            android.os.Handler(mainLooper).postDelayed({
                stopBLEScan()
                showToast("Scan completed.")
            }, 10000)
        }
    }

    private fun stopBLEScan() {
        if (isScanning) {
            val bluetoothLeScanner = bleAdapter.bluetoothAdapter?.bluetoothLeScanner
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.BLUETOOTH_SCAN
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            bluetoothLeScanner?.stopScan(bleScanCallback)
            isScanning = false
            showToast("Scan stopped.")
        }
    }


    private val bleScanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val deviceName = result.device.name
            val deviceAddress = result.device.address
            val rssi = result.rssi

            if (deviceName.isNullOrEmpty() || deviceName !in targetDevices) {
                return
            }

            // Check if this device was previously found
            val existingDevice = deviceList.find { it.address == deviceAddress }
            val isDeviceFound = existingDevice?.isFound ?: false

            runOnUiThread {
                val existingIndex = deviceList.indexOfFirst { it.address == deviceAddress }
                if (existingIndex != -1) {
                    // Update only RSSI while preserving other properties
                    deviceList[existingIndex].rssi = rssi.toString()
                    // Use payload to update only RSSI
                    deviceAdapter.notifyItemChanged(existingIndex, rssi.toString())
                } else {
                    // Add new device
                    val newDevice = BLEDevice(
                        name = deviceName,
                        address = deviceAddress,
                        rssi = rssi.toString(),
                        isFound = isDeviceFound
                    )
                    deviceList.add(newDevice)
                    deviceAdapter.notifyItemInserted(deviceList.size - 1)
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            showToast("Scan failed with error code: $errorCode")
        }
    }

    private fun hasPermissions(): Boolean {
        val permissions = bleAdapter.getRequiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            startBLEScan()
        } else {
            showToast("Permission denied. Cannot scan BLE devices.")
        }
    }

    companion object {
        private const val GAMEPLAY_ACTIVITY_REQUEST_CODE = 1001
        private const val REQUEST_CODE_PERMISSIONS = 1002
    }
}