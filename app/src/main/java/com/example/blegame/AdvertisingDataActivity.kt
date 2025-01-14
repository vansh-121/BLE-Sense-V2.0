package com.example.blegame

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AdvertisingDataActivity : AppCompatActivity() {
    private lateinit var bleAdapter: BluetoothAdapterWrapper
    private lateinit var deviceInfo: TextView
    private lateinit var advertisingDataDetails: TextView
    private lateinit var temperatureMeterView: TemperatureViewMeter
    private lateinit var luxViewBulb: ImageView
    private val sht40Readings = mutableListOf<SHT40Reading>()
    private lateinit var downloadButton: Button
    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
//    private lateinit var luxChart: LineChart
//    private lateinit var sht40Chart: LineChart
    private var scanCallback: ScanCallback? = null
    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 1
        private const val TAG = "AdvertisingDataActivity"
    }
    @SuppressLint("SetTextI18n")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_advertising_data)

//        luxChart = findViewById(R.id.luxChart)
//        sht40Chart = findViewById(R.id.sht40Chart)
        initializeViews()
//        setupChart(luxChart)
//        setupChart(sht40Chart)
        val selectedDeviceName = intent.getStringExtra("DEVICE_NAME")
        val selectedDeviceAddress = intent.getStringExtra("DEVICE_ADDRESS")
        val deviceType = intent.getStringExtra("DEVICE_TYPE")
        luxViewBulb = findViewById(R.id.bulbImageView)
        luxViewBulb.visibility = View.GONE // Default to hidden
        downloadButton = Button(this).apply {
            text = "Download Data"
            visibility = View.GONE
            setOnClickListener { exportDataToCSV(6, 24) }
        }
        // Add the download button to your layout
        findViewById<LinearLayout>(R.id.root_layout).addView(downloadButton)

        // Show download button only for SHT40 device
        if (intent.getStringExtra("DEVICE_TYPE") == "SHT40") {
            downloadButton.visibility = View.VISIBLE
        }
        if(deviceType == "Lux Sensor") {
            luxViewBulb.visibility = View.VISIBLE
        }
        if (selectedDeviceName == null || selectedDeviceAddress == null) {
            showToast("Device details not found. Returning to previous screen.")
            finish()
            return
        }
        deviceInfo.text = "Device: $selectedDeviceName ($selectedDeviceAddress)"
        temperatureMeterView.visibility = if (deviceType == "SHT40") View.VISIBLE else View.GONE
        if (checkPermissions()) {
            startRealTimeScan(selectedDeviceAddress, selectedDeviceName, deviceType)
        } else {
            requestPermissions()
        }
    }
    private fun initializeViews() {
        bleAdapter = BluetoothAdapterWrapper(this)
        deviceInfo = findViewById(R.id.deviceInfo)
        advertisingDataDetails = findViewById(R.id.advertisingDataDetails)
        temperatureMeterView = findViewById(R.id.temperatureMeter)
        temperatureMeterView.visibility = View.GONE
        findViewById<ImageButton>(R.id.backButton).setOnClickListener { finish() }
    }
    @SuppressLint("InlinedApi")
    private fun checkPermissions(): Boolean {
        return ActivityCompat.checkSelfPermission(
            this,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED
    }
    @SuppressLint("InlinedApi")
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(
            this,
            arrayOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.ACCESS_FINE_LOCATION
            ),
            REQUEST_CODE_PERMISSIONS
        )
    }
    @SuppressLint("MissingPermission", "NewApi")
    private fun startRealTimeScan(
        selectedDeviceAddress: String,
        selectedDeviceName: String,
        deviceType: String?
    ) {
        val bluetoothLeScanner = bleAdapter.bluetoothAdapter?.bluetoothLeScanner
        if (bluetoothLeScanner == null) {
            showToast("BLE Scanner not available.")
            return
        }

        val scanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setLegacy(false)
            .setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            .build()
        scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (result.device.name == selectedDeviceName && result.device.address == selectedDeviceAddress) {
                    val parsedData = parseAdvertisingData(result, deviceType)
                    updateUIWithScanData(parsedData)
                }
            }
            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "Scan failed with error code: $errorCode")
                showToast("Scan failed. Error code: $errorCode")
            }
        }
        bluetoothLeScanner.startScan(null, scanSettings, scanCallback)
        Log.d(TAG, "Started scanning for BLE devices.")
    }
    private fun parseAdvertisingData(result: ScanResult, deviceType: String?): String {
        val gapDetails = StringBuilder()
        val scanRecord = result.scanRecord
        scanRecord?.manufacturerSpecificData?.let { manufacturerData ->
            for (i in 0 until manufacturerData.size()) {
                val data = manufacturerData.valueAt(i)
                if (data != null) {
                    when (deviceType) {
                        "SHT40" -> gapDetails.append(parseSHT40Data(data))
                        "Lux Sensor" -> gapDetails.append(parseLuxSensorData(data))
                        else -> gapDetails.append(parseManufacturerData(data, deviceType))
                    }
                    gapDetails.append("\n\n")
                }
            }
        }
        return gapDetails.toString()
    }
//    private fun setupChart(chart: LineChart) {
//        chart.description.isEnabled = false
//        chart.setDrawGridBackground(false)
//        chart.isDragEnabled = true
//        chart.setScaleEnabled(true)
//        chart.setTouchEnabled(true)
//    }
    private fun parseLuxSensorData(data: ByteArray): String {
        val builder = StringBuilder()
        // Print raw data in hexadecimal format
        builder.append("Raw BLE Data: ")
        builder.append(data.joinToString(" ") { byte -> String.format("%02X", byte) })
            .append("\n\n")
        return if (data.size >= 5) {
            val deviceId = data[0].toUByte().toString()
            val temperatureBeforeDecimal = data[1].toUByte().toString()
            val temperatureAfterDecimal = data[2].toUByte().toString()
            val temperature = "$temperatureBeforeDecimal.$temperatureAfterDecimal".toFloat()
            val calculatedLux = temperature * 60  // Convert temperature to lux
            // Update UI for Lux Sensor
            Handler(Looper.getMainLooper()).post {
                temperatureMeterView.visibility = View.GONE
                luxViewBulb.visibility = View.VISIBLE
                // Set bulb image based on lux ranges
                val bulbResource = when {
                    calculatedLux < 1700 -> R.drawable.bulb_off
                    calculatedLux in 1700.0..2000.0 -> R.drawable.bulb_25
                    calculatedLux in 2000.0..2500.0 -> R.drawable.bulb_50
                    calculatedLux in 2500.0..3000.0 -> R.drawable.bulb_75
                    else -> R.drawable.bulb_glow
                }
                luxViewBulb.setImageResource(bulbResource)
//                updateLuxChart(calculatedLux)
            }
            builder.append("Device ID: $deviceId\n")
            builder.append("Calculated Lux: $calculatedLux lux\n")
            builder.toString()
        } else {
            builder.append("Manufacturer Data is too short for Lux Sensor\n")
            builder.toString()
        }
    }
    private fun parseSHT40Data(data: ByteArray): String {
        val builder = StringBuilder()
        if (data.size >= 5) {
            val deviceId = data[0].toUByte().toString()
            val temperatureBeforeDecimal = data[1].toUByte().toString()
            val temperatureAfterDecimal = data[2].toUByte().toString()
            val humidityBeforeDecimal = data[3].toUByte().toString()
            val humidityAfterDecimal = data[4].toUByte().toString()
            val temperature = "$temperatureBeforeDecimal.$temperatureAfterDecimal"
            val humidity = "$humidityBeforeDecimal.$humidityAfterDecimal"
            val temperatureFloat = temperature.toFloatOrNull()
            val humidityFloat = humidity.toFloatOrNull()
            // Store the reading
            sht40Readings.add(
                SHT40Reading(
                    timestamp = System.currentTimeMillis(),
                    deviceId = deviceId,
                    temperature = temperature,
                    humidity = humidity
                )
            )
            // Update UI as before
            Handler(Looper.getMainLooper()).post {
                temperatureMeterView.visibility = View.VISIBLE
                temperatureMeterView.setTemperature(temperature.toFloat())
            }
            // Build the display string
            builder.append("Device ID: $deviceId\n")
            builder.append("Temperature: $temperature°C\n")
            builder.append("Humidity: $humidity%\n")
            if (temperatureFloat != null && humidityFloat != null) {
//                updateSHT40Chart(temperatureFloat, humidityFloat)
            } else {
                builder.append("Invalid temperature or humidity format\n")
            }
        } else {
            builder.append("Manufacturer Data is too short for SHT40\n")
        }
        return builder.toString()
    }
//    private val activityScope = CoroutineScope(Dispatchers.Main + Job())
//
//    private fun exportDataToCSV() {
//        activityScope.launch(Dispatchers.IO) {
//            try {
//                val fileName = "SHT40_Data_${System.currentTimeMillis()}.csv"
//                val file = File(getExternalFilesDir(null), fileName)
//
//                file.bufferedWriter().use { writer ->
//                    // Write CSV header
//                    writer.write("Timestamp,Device ID,Temperature (°C),Humidity (%)\n")
//
//                    // Write data
//                    synchronized(sht40Readings) {  // Add synchronization for thread safety
//                        sht40Readings.forEach { reading ->
//                            writer.write(
//                                "${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
//                                    Date(reading.timestamp)
//                                )}," +
//                                        "${reading.deviceId}," +
//                                        "${reading.temperature}," +
//                                        "${reading.humidity}\n"
//                            )
//                        }
//                    }
//                }
//
//                // Show success message on main thread
//                withContext(Dispatchers.Main) {
//                    showToast("Data exported to: ${file.absolutePath}")
//
//                    // Create file URI and start sharing intent
//                    val uri = FileProvider.getUriForFile(
//                        this@AdvertisingDataActivity,
//                        "${packageName}.provider",
//                        file
//                    )
//
//                    val intent = Intent(Intent.ACTION_SEND).apply {
//                        type = "text/csv"
//                        putExtra(Intent.EXTRA_STREAM, uri)
//                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
//                    }
//
//                    startActivity(Intent.createChooser(intent, "Share CSV File"))
//                }
//            } catch (e: Exception) {
//                withContext(Dispatchers.Main) {
//                    showToast("Error exporting data: ${e.message}")
//                }
//            }
//        }
//    }
    private fun exportDataToCSV(startTime: Long?, endTime: Long?) {
        activityScope.launch(Dispatchers.IO) {
            try {
                val filteredReadings = sht40Readings.filter {
                    (startTime == null || it.timestamp >= startTime) &&
                            (endTime == null || it.timestamp <= endTime)
                }
                if (filteredReadings.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showToast("No data available for the selected time range.")
                    }
                    return@launch
                }
                val fileName = "Filtered_SHT40_Data_${System.currentTimeMillis()}.csv"
                val file = File(getExternalFilesDir(null), fileName)
                file.bufferedWriter().use { writer ->
                    writer.write("Timestamp,Device ID,Temperature (°C),Humidity (%)\n")
                    filteredReadings.forEach { reading ->
                        writer.write(
                            "${
                                SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(
                                    Date(reading.timestamp)
                                )
                            }," +
                                    "${reading.deviceId}," +
                                    "${reading.temperature}," +
                                    "${reading.humidity}\n"
                        )
                    }
                }
                withContext(Dispatchers.Main) {
                    showToast("Filtered data exported to: ${file.absolutePath}")
                    shareFile(file)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showToast("Error exporting data: ${e.message}")
                }
            }
        }
    }
    private fun exportDailyDataToCSV() {
        val oneDayMillis = 24 * 60 * 60 * 1000
        val startTime = System.currentTimeMillis() - oneDayMillis
        exportDataToCSV(startTime, System.currentTimeMillis())
    }
    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.provider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share CSV File"))
    }
    private fun parseManufacturerData(data: ByteArray, deviceType: String?): String {
        return when (deviceType) {
            "SHT40" -> parseSHT40Data(data)
            "LIS2DH" -> parseLIS2DHData(data)
            "Soil Sensor" -> parseSoilSensorData(data)
            "Weather" -> parseWeatherData(data)
            else -> "Unknown Device Type"
        }
    }
    private var currentTemperature: Float = 25.0f // Default to 25°C (room temperature)
    private fun parseLIS2DHData(data: ByteArray): String {
        val builder = StringBuilder()
        // Print raw data in hexadecimal format
        builder.append("Raw BLE Data: ")
        builder.append(data.joinToString(" ") { byte -> String.format("%02X", byte) })
            .append("\n\n")
        return if (data.size >= 7) {
            val deviceId = data[0].toUByte().toString()
            val xBeforeDecimal = data[1].toUByte().toString()
            val xAfterDecimal = data[2].toUByte().toString()
            val yBeforeDecimal = data[3].toUByte().toString()
            val yAfterDecimal = data[4].toUByte().toString()
            val zBeforeDecimal = data[5].toUByte().toString()
            val zAfterDecimal = data[6].toUByte().toString()
            builder.append("Device ID: $deviceId\n")
            builder.append("X: $xBeforeDecimal.$xAfterDecimal\n")
            builder.append("Y: $yBeforeDecimal.$yAfterDecimal\n")
            builder.append("Z: $zBeforeDecimal.$zAfterDecimal\n")
            builder.toString()
        } else {
            builder.append("Manufacturer Data is too short for LIS2DH\n")
            builder.toString()
        }
    }
    private fun parseSoilSensorData(data: ByteArray): String {
        val builder = StringBuilder()
        // Print raw data in hexadecimal format
        builder.append("Raw BLE Data: ")
        builder.append(data.joinToString(" ") { byte -> String.format("%02X", byte) })
            .append("\n\n")
        if (data.size >= 11) {
            val deviceId = data[0].toUByte().toString()
            val nitrogen = data[1].toUByte().toString()
            val phosphorus = data[2].toUByte().toString()
            val potassium = data[3].toUByte().toString()
            val moisture = data[4].toUByte().toString()
            val temperatureBeforeDecimal = data[5].toUByte().toString()
            val temperatureAfterDecimal = data[6].toUByte().toString()
            val eCbeforeDecimal = data[7].toUByte().toString()
            val eCafterDecimal = data[8].toUByte().toString()
            val pHbeforeDecimal = data[9].toUByte().toString()
            val pHafterDecimal = data[10].toUByte().toString()
            builder.append("Device ID: $deviceId\n")
            builder.append("Nitrogen: $nitrogen mg/kg\n")
            builder.append("Phosphorus: $phosphorus mg/kg\n")
            builder.append("Potassium: $potassium mg/kg\n")
            builder.append("Moisture: $moisture%\n")
            builder.append("Temperature: $temperatureBeforeDecimal.$temperatureAfterDecimal°C\n")
            builder.append("Electrical Conductivity: $eCbeforeDecimal.$eCafterDecimal µS/cm\n")
            builder.append("pH: $pHbeforeDecimal.$pHafterDecimal\n")
        } else {
            builder.append("Manufacturer Data is too short for Soil Sensor\n")
        }
        return builder.toString()
    }
//    private fun updateLuxChart(luxValue: Float) {
//        // Create a new entry for Lux data
//        val entry =
//            com.github.mikephil.charting.data.Entry(System.currentTimeMillis().toFloat(), luxValue)
//        // Add entry to the list
//        val dataValues = ArrayList<Entry>()
//        dataValues.add(entry)
//        // Create dataset for the chart
//        val lineDataSet = LineDataSet(dataValues, "Lux Value")
//        lineDataSet.color = Color.RED
//        lineDataSet.lineWidth = 2f
//        // Set data to the chart
//        val lineData = LineData(lineDataSet)
//        luxChart.data = lineData
//        luxChart.invalidate()  // Refresh the chart
//    }
//    private fun updateSHT40Chart(temperature: Float, humidity: Float) {
//        // Create entries for both temperature and humidity
//        val tempEntry = com.github.mikephil.charting.data.Entry(
//            System.currentTimeMillis().toFloat(),
//            temperature
//        )
//        val humidityEntry =
//            com.github.mikephil.charting.data.Entry(System.currentTimeMillis().toFloat(), humidity)
//        // Add entries to separate lists for temperature and humidity
//        val tempData = ArrayList<com.github.mikephil.charting.data.Entry>()
//        val humidityData = ArrayList<com.github.mikephil.charting.data.Entry>()
//        tempData.add(tempEntry)
//        humidityData.add(humidityEntry)
//        // Create datasets for each chart
//        val tempDataSet = LineDataSet(tempData, "Temperature (°C)")
//        val humidityDataSet = LineDataSet(humidityData, "Humidity (%)")
//        // Customize the datasets (e.g., colors, line width)
//        tempDataSet.color = Color.BLUE
//        tempDataSet.lineWidth = 2f
//        humidityDataSet.color = Color.GREEN
//        humidityDataSet.lineWidth = 2f
//        // Set data to the chart
//        val lineData = LineData(tempDataSet, humidityDataSet)
//        sht40Chart.data = lineData
//        sht40Chart.invalidate()  // Refresh the chart
//    }
    private fun parseWeatherData(data: ByteArray): String {
        if (data.size < 20) {
            return "Insufficient data. Expected at least 20 bytes but received ${data.size}."
        }
        val builder = StringBuilder()
        // Display raw BLE data as hex
        builder.append("Raw BLE Data: ")
        builder.append(data.joinToString(" ") { byte -> String.format("%02X", byte) })
            .append("\n\n")
        // DeviceId 1
        val deviceId1 = data[0].toUByte().toString()
        // Temperature 1 (bytes 1 and 2) with a decimal between
        val temperature1 = "${data[1].toUByte()}.${data[2].toUByte()}°C"
        // Humidity 1 (bytes 3 and 4) with a decimal between
        val humidity1 = "${data[3].toUByte()}.${data[4].toUByte()}%"
        // Pressure 1 (bytes 5 and 6) with a decimal between
        val pressure1 = "${data[5].toUByte()}.${data[6].toUByte()} hPa"
        // Dew Point Temperature 1 (bytes 7 and 8) with a decimal between
        val dewPointTemperature1 = "${data[7].toUByte()}.${data[8].toUByte()}°C"
        // Skip bytes 9 and 10, DeviceId 2 starts at byte 11
        val deviceId2 = data[11].toUByte().toString()
        // Temperature 2 (bytes 12 and 13) with a decimal between
        val temperature2 = "${data[12].toUByte()}.${data[13].toUByte()}°C"
        // Humidity 2 (bytes 14 and 15) with a decimal between
        val humidity2 = "${data[14].toUByte()}.${data[15].toUByte()}%"
        // Pressure 2 (bytes 16 and 17) with a decimal between
        val pressure2 = "${data[16].toUByte()}.${data[17].toUByte()} hPa"
        // Dew Point Temperature 2 (bytes 18 and 19) with a decimal between
        val dewPointTemperature2 = "${data[18].toUByte()}.${data[19].toUByte()}°C"
        builder.append("Parsed Sensor Data:\n")
        builder.append("DeviceId 1: $deviceId1\n")
        builder.append("Temperature 1: $temperature1\n")
        builder.append("Humidity 1: $humidity1\n")
        builder.append("Pressure 1: $pressure1\n")
        builder.append("Dew Point Temperature 1: $dewPointTemperature1\n")
        builder.append("\n")
        builder.append("DeviceId 2: $deviceId2\n")
        builder.append("Temperature 2: $temperature2\n")
        builder.append("Humidity 2: $humidity2\n")
        builder.append("Pressure 2: $pressure2\n")
        builder.append("Dew Point Temperature 2: $dewPointTemperature2")
        return builder.toString()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable("readings", ArrayList(sht40Readings))
    }
    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        val savedReadings =
            savedInstanceState.getSerializable("readings") as? ArrayList<SHT40Reading>
        savedReadings?.let { sht40Readings.addAll(it) }
    }
    private fun updateUIWithScanData(parsedData: String) {
        Handler(Looper.getMainLooper()).post { advertisingDataDetails.text = parsedData }
    }
    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
    @SuppressLint("MissingPermission")
    override fun onDestroy() {
        super.onDestroy()
        bleAdapter.bluetoothAdapter?.bluetoothLeScanner?.apply {
            scanCallback?.let { stopScan(it) }
        }
    }
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            val selectedDeviceName = intent.getStringExtra("DEVICE_NAME") ?: return
            val selectedDeviceAddress = intent.getStringExtra("DEVICE_ADDRESS") ?: return
            val deviceType = intent.getStringExtra("DEVICE_TYPE")
            startRealTimeScan(selectedDeviceAddress, selectedDeviceName, deviceType)
        } else {
            showToast("Permissions denied. Cannot scan for BLE devices.")
        }
    }
}