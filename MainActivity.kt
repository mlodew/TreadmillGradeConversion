package com.example.treadmillspeedconverter

import android.content.res.Configuration
import android.os.Bundle
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.*

class MainActivity : ComponentActivity(), SensorEventListener {
    private lateinit var sensorManager: SensorManager
    private var accelerometer: Sensor? = null

    // Compose state holders
    private val _sensorGrade = mutableStateOf(0.0)
    private val _isSensorMode = mutableStateOf(false)
    private val _showCalibration = mutableStateOf(false)
    private val _isCalibrated = mutableStateOf(false)
    private val _calibrationPitch = mutableStateOf(0.0)
    private val _latestPitch = mutableStateOf(0.0)
    private val _fallDetected = mutableStateOf(false)
    private var lastOrientation: Int? = null

    // Fall detection settings
    private var lastAccel = 0.0
    private var lastAccelTimestamp = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setContent {
            val config = LocalConfiguration.current
            // Orientation change detection
            val orientation = config.orientation
            LaunchedEffect(orientation) {
                if (lastOrientation != null && lastOrientation != orientation) {
                    // Force recalibration if orientation changed
                    if (_isSensorMode.value) {
                        _isCalibrated.value = false
                        _showCalibration.value = true
                    }
                }
                lastOrientation = orientation
            }

            // Always show calibration on launch if in sensor mode
            LaunchedEffect(Unit) {
                if (_isSensorMode.value) {
                    _isCalibrated.value = false
                    _showCalibration.value = true
                }
            }

            TreadmillSpeedConverterSensorApp(
                sensorGrade = _sensorGrade.value,
                isSensorMode = _isSensorMode.value,
                isCalibrated = _isCalibrated.value,
                showCalibration = _showCalibration.value,
                onToggleSensorMode = { handleSensorModeToggle() },
                onCalibrate = { calibrate() },
                onManualGradeChange = { },
                fallDetected = _fallDetected.value
            )
        }
    }

    private fun handleSensorModeToggle() {
        if (!_isSensorMode.value) {
            // Switching ON: require calibration
            _showCalibration.value = true
            _isCalibrated.value = false
            _isSensorMode.value = true
        } else {
            // Switching OFF: reset state
            _isSensorMode.value = false
            _isCalibrated.value = false
            _showCalibration.value = false
            _fallDetected.value = false
        }
    }

    private fun calibrate() {
        _calibrationPitch.value = _latestPitch.value
        _isCalibrated.value = true
        _showCalibration.value = false
        _fallDetected.value = false
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.also { sensor ->
            sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event?.sensor?.type == Sensor.TYPE_ACCELEROMETER) {
            val ax = event.values[0]
            val ay = event.values[1]
            val az = event.values[2]
            // Calculate pitch (radians)
            val pitchRadians = atan2(-ax, sqrt(ay * ay + az * az))
            _latestPitch.value = pitchRadians

            // Fall detection: large acceleration change
            val accel = sqrt(ax * ax + ay * ay + az * az)
            val now = System.currentTimeMillis()
            if (_isSensorMode.value && _isCalibrated.value && now - lastAccelTimestamp > 500) {
                if (abs(accel - lastAccel) > 8.0) { // sudden jolt
                    _fallDetected.value = true
                    _showCalibration.value = true
                    _isCalibrated.value = false
                }
                lastAccel = accel
                lastAccelTimestamp = now
            }

            // Only calculate grade if calibrated and in sensor mode
            if (_isSensorMode.value && _isCalibrated.value) {
                val relativePitch = pitchRadians - _calibrationPitch.value
                val gradePercent = tan(relativePitch) * 100
                _sensorGrade.value = gradePercent.coerceIn(-30.0, 30.0)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

}

@Composable
fun TreadmillSpeedConverterSensorApp(
    sensorGrade: Double,
    isSensorMode: Boolean,
    isCalibrated: Boolean,
    showCalibration: Boolean,
    onToggleSensorMode: () -> Unit,
    onCalibrate: () -> Unit,
    onManualGradeChange: (Double) -> Unit,
    fallDetected: Boolean
) {
    var speed by remember { mutableStateOf(6.0) }
    var manualGrade by remember { mutableStateOf(0.0) }
    val displayGrade = if (isSensorMode && isCalibrated) sensorGrade else manualGrade
    val flatSpeed: Double
        get() = speed / (1 + (displayGrade / 100.0) * 1.8)

    val config = LocalConfiguration.current
    val isLandscape = config.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Calibration dialog/modal
    if (showCalibration) {
        AlertDialog(
            onDismissRequest = { /* Block dismiss */ },
            confirmButton = {
                Button(onClick = onCalibrate) { Text("Calibrate") }
            },
            title = { Text("Calibrate Sensor") },
            text = {
                Text(
                    if (fallDetected)
                        "A fall or sudden movement was detected. Please place your phone on the treadmill at 0% incline and press Calibrate."
                    else
                        "To calibrate incline detection, place your phone securely on the treadmill deck at 0% incline, then press Calibrate."
                )
            }
        )
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        val MainContent: @Composable () -> Unit = {
            InputSection(
                speed, displayGrade, isSensorMode && isCalibrated,
                onSpeedChange = { speed = it },
                onGradeChange = {
                    manualGrade = it
                    onManualGradeChange(it)
                }
            )
            Spacer(Modifier.height(24.dp))
            AdjustSection(
                value = speed,
                onDecrement = { if (speed > 0.1) speed -= 0.1 },
                onIncrement = { speed += 0.1 },
                label = "Speed (mph)",
                step = 0.1
            )
            Spacer(Modifier.height(16.dp))
            AdjustSection(
                value = displayGrade,
                onDecrement = {
                    if (!(isSensorMode && isCalibrated) && displayGrade > 0.0) {
                        manualGrade -= 0.5
                        onManualGradeChange(manualGrade)
                    }
                },
                onIncrement = {
                    if (!(isSensorMode && isCalibrated)) {
                        manualGrade += 0.5
                        onManualGradeChange(manualGrade)
                    }
                },
                label = "Grade (%)",
                step = 0.5,
                enabled = !(isSensorMode && isCalibrated)
            )
            Spacer(Modifier.height(32.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Text(
                    when {
                        isSensorMode && isCalibrated -> "Sensor Grade: ON"
                        isSensorMode && !isCalibrated -> "Sensor Grade: CALIBRATION REQUIRED"
                        else -> "Sensor Grade: OFF"
                    },
                    fontSize = 18.sp
                )
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onToggleSensorMode,
                    enabled = !showCalibration
                ) {
                    Text(
                        if (isSensorMode)
                            if (isCalibrated) "Switch to Manual" else "Cancel Sensor Mode"
                        else "Use Sensor"
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
            ResultSection(flatSpeed)
        }

        if (isLandscape) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) { MainContent() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) { MainContent() }
        }
    }
}

@Composable
fun InputSection(
    speed: Double,
    grade: Double,
    isSensorActive: Boolean,
    onSpeedChange: (Double) -> Unit,
    onGradeChange: (Double) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        OutlinedTextField(
            value = if (speed == 0.0) "" else "%.1f".format(speed),
            onValueChange = { v -> v.toDoubleOrNull()?.let { onSpeedChange(it) } },
            label = { Text("Speed") },
            suffix = { Text("mph") },
            keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(140.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        OutlinedTextField(
            value = if (grade == 0.0) "" else "%.1f".format(grade),
            onValueChange = { v -> v.toDoubleOrNull()?.let { onGradeChange(it) } },
            label = { Text("Grade (%)") },
            suffix = { Text("%") },
            enabled = !isSensorActive,
            keyboardOptions = androidx.compose.ui.text.input.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
            singleLine = true,
            modifier = Modifier.width(140.dp)
        )
    }
}

@Composable
fun AdjustSection(
    value: Double,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
    label: String,
    step: Double,
    enabled: Boolean = true
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 18.sp)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            Button(
                onClick = onDecrement,
                modifier = Modifier
                    .size(width = 80.dp, height = 80.dp),
                enabled = enabled
            ) {
                Text("-", fontSize = 40.sp)
            }
            Spacer(Modifier.width(12.dp))
            Text(
                "%.1f".format(value),
                fontSize = 32.sp,
                modifier = Modifier.width(70.dp),
            )
            Spacer(Modifier.width(12.dp))
            Button(
                onClick = onIncrement,
                modifier = Modifier
                    .size(width = 80.dp, height = 80.dp),
                enabled = enabled
            ) {
                Text("+", fontSize = 40.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text("Step: $step", fontSize = 12.sp)
    }
}

@Composable
fun ResultSection(flatSpeed: Double) {
    Text(
        text = "Equivalent Flat Speed: %.2f mph".format(flatSpeed),
        style = MaterialTheme.typography.headlineMedium,
        modifier = Modifier.padding(16.dp)
    )
}