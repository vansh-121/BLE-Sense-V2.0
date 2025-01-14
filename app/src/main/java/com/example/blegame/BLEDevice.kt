package com.example.blegame

data class BLEDevice(
    var name: String?,
    var address: String?,
    var rssi: String = 0.toString(),
    var lastSeen: Long = System.currentTimeMillis(),
    var isFound: Boolean = false,
)

data class SHT40Reading(
    val timestamp: Long,
    val deviceId: String,
    val temperature: String,
    val humidity: String
)



