package com.simform.audio_waveforms

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.PluginRegistry
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.math.log10

private const val LOG_TAG = "AudioWaveforms"
private const val RECORD_AUDIO_REQUEST_CODE = 1001
private const val BLUETOOTH_CONNECT_REQUEST_CODE = 1002
private const val TYPE_BLE_HEADSET = 26 // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31+)

class AudioRecorder : PluginRegistry.RequestPermissionsResultListener {
    private var permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var useLegacyNormalization = false
    private var successCallback: RequestPermissionsSuccessCallback? = null
    private var isBluetoothScoEnabled = false
    private var originalAudioMode = AudioManager.MODE_NORMAL
    private var scoReceiver: android.content.BroadcastReceiver? = null
    private var communicationDevice: AudioDeviceInfo? = null

    fun getDecibel(result: MethodChannel.Result, recorder: MediaRecorder?) {
        if (useLegacyNormalization) {
            val db = 20 * log10(((recorder?.maxAmplitude?.toDouble() ?: (0.0 / 32768.0))))
            if (db == Double.NEGATIVE_INFINITY) {
                Log.d(LOG_TAG, "Microphone might be turned off")
            } else {
                result.success(db)
            }
        } else {
            result.success(recorder?.maxAmplitude?.toDouble() ?: 0.0)
        }
    }

    fun initRecorder(
        result: MethodChannel.Result,
        recorder: MediaRecorder?,
        recorderSettings: RecorderSettings,
        context: Context
    ) {
        recorder?.apply {
            // Check if target device is Bluetooth
            val isBluetoothDevice = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && recorderSettings.audioDeviceId != null) {
                isBluetoothInputDevice(context, recorderSettings.audioDeviceId)
            } else {
                false
            }

            // For Bluetooth, use VOICE_COMMUNICATION source for better compatibility
            val audioSource = if (isBluetoothDevice) {
                Log.d(LOG_TAG, "Bluetooth device detected, using VOICE_COMMUNICATION source")
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            } else {
                getAudioSource(recorderSettings.audioSource)
            }
            Log.d(LOG_TAG, "Setting audio source: $audioSource (index: ${recorderSettings.audioSource})")
            setAudioSource(audioSource)

            setOutputFormat(getOutputFormat(recorderSettings.outputFormat))
            setAudioEncoder(getEncoder(recorderSettings.encoder))

            // For Bluetooth SCO, use 16kHz or 8kHz sample rate for better compatibility
            val sampleRate = if (isBluetoothDevice && recorderSettings.sampleRate > 16000) {
                Log.d(LOG_TAG, "Bluetooth device detected, adjusting sample rate from ${recorderSettings.sampleRate} to 16000 Hz")
                16000
            } else {
                recorderSettings.sampleRate
            }
            setAudioSamplingRate(sampleRate)
            setAudioChannels(1) // Mono for standard recording

            // For Bluetooth, use lower bitrate (64kbps is sufficient for voice quality)
            val bitRate = if (isBluetoothDevice) {
                val btBitRate = 64000
                Log.d(LOG_TAG, "Bluetooth device detected, using bitRate: $btBitRate")
                btBitRate
            } else {
                recorderSettings.bitRate ?: 256000
            }
            Log.d(LOG_TAG, "Setting bitRate: $bitRate")
            setAudioEncodingBitRate(bitRate)

            setOutputFile(recorderSettings.path)
            try {
                // Android P+ only - must be called BEFORE prepare()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && recorderSettings.audioDeviceId != null) {
                    setPreferredAudioDevice(context, this, recorderSettings.audioDeviceId)
                }

                prepare()
                result.success(true)
            } catch (e: IOException) {
                Log.e(LOG_TAG, "Failed to initialize recorder: ${e.message}")
                result.error(LOG_TAG, "Failed to initialize recorder", e.message)
            }
        }
    }

    fun stopRecording(result: MethodChannel.Result, recorder: MediaRecorder?, path: String, context: Context?) {
        try {
            val hashMap: HashMap<String, Any?> = HashMap()
            try {
                recorder?.stop()

                val duration = getDuration(path)

                hashMap[Constants.resultFilePath] = path
                hashMap[Constants.resultDuration] = duration
            } catch (e: RuntimeException) {
                // Stop was called immediately after start which causes stop() call to fail.
                hashMap[Constants.resultFilePath] = null
                hashMap[Constants.resultDuration] = -1
            }

            recorder?.apply {
                reset()
                release()
            }

            // Clean up Bluetooth SCO if it was enabled
            if (isBluetoothScoEnabled && context != null) {
                cleanupBluetoothAudio(context)
            }

            result.success(hashMap)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to stop recording")
        }
    }

    private fun cleanupBluetoothAudio(context: Context) {
        try {
            val audioManager = context.getServiceOrNull<AudioManager>(Context.AUDIO_SERVICE) ?: return

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Android S+: Clear communication device
                communicationDevice?.let {
                    try {
                        audioManager.clearCommunicationDevice()
                        Log.d(LOG_TAG, "Communication device cleared")
                    } catch (e: Exception) {
                        Log.e(LOG_TAG, "Error clearing communication device: ${e.message}", e)
                    }
                    communicationDevice = null
                }
            } else {
                // Pre-Android S: Cleanup SCO
                scoReceiver?.let {
                    try {
                        context.unregisterReceiver(it)
                        Log.d(LOG_TAG, "SCO receiver unregistered")
                    } catch (e: IllegalArgumentException) {
                        // Receiver not registered, ignore
                    }
                    scoReceiver = null
                }

                if (audioManager.isBluetoothScoOn) {
                    audioManager.stopBluetoothSco()
                    audioManager.isBluetoothScoOn = false
                    Log.d(LOG_TAG, "Bluetooth SCO stopped")
                }
            }

            audioManager.mode = originalAudioMode
            Log.d(LOG_TAG, "Audio mode restored to $originalAudioMode")
            isBluetoothScoEnabled = false
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error cleaning up Bluetooth audio: ${e.message}", e)
        }
    }

    private fun getDuration(path: String): Int {
        val mediaMetadataRetriever = MediaMetadataRetriever()
        try {
            mediaMetadataRetriever.setDataSource(path)
            val duration = mediaMetadataRetriever.extractMetadata(METADATA_KEY_DURATION)
            return duration?.toInt() ?: -1
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to get recording duration")
        } finally {
            mediaMetadataRetriever.release()
        }
        return -1
    }

    fun startRecorder(result: MethodChannel.Result, recorder: MediaRecorder?, useLegacy: Boolean) {
        try {
            useLegacyNormalization = useLegacy
            recorder?.start()
            result.success(true)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to start recording")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun pauseRecording(result: MethodChannel.Result, recorder: MediaRecorder?) {
        try {
            recorder?.pause()
            result.success(false)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to pause recording")
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun resumeRecording(result: MethodChannel.Result, recorder: MediaRecorder?) {
        try {
            recorder?.resume()
            result.success(true)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to resume recording")
        }
    }

    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ): Boolean {
        return when (requestCode) {
            RECORD_AUDIO_REQUEST_CODE -> {
                successCallback?.onSuccess(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            }
            BLUETOOTH_CONNECT_REQUEST_CODE -> {
                grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            }
            else -> false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun hasBluetoothConnectPermission(activity: Activity?): Boolean {
        return activity?.let {
            ActivityCompat.checkSelfPermission(it, android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
        } ?: false
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun requestBluetoothConnectPermission(activity: Activity?) {
        activity?.let {
            ActivityCompat.requestPermissions(
                it,
                arrayOf(android.Manifest.permission.BLUETOOTH_CONNECT),
                BLUETOOTH_CONNECT_REQUEST_CODE
            )
        }
    }

    private fun isPermissionGranted(activity: Activity?): Boolean {
        val result =
                ActivityCompat.checkSelfPermission(activity!!, permissions[0])
        return result == PackageManager.PERMISSION_GRANTED
    }

    fun checkPermission(result: MethodChannel.Result, activity: Activity?, successCallback: RequestPermissionsSuccessCallback) {
        this.successCallback = successCallback
        if (!isPermissionGranted(activity)) {
            activity?.let {
                ActivityCompat.requestPermissions(
                        it, permissions,
                        RECORD_AUDIO_REQUEST_CODE
                )
            }
        } else {
            result.success(true)
        }
    }

    /**
     * Checks if the given device ID corresponds to a Bluetooth input device.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun isBluetoothInputDevice(context: Context, deviceId: Int): Boolean {
        try {
            val audioManager = context.getServiceOrNull<AudioManager>(Context.AUDIO_SERVICE) ?: return false
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

            // First try to find by ID
            var device = devices.find { it.id == deviceId && it.isSource }

            // Fallback: find any Bluetooth input device by type
            if (device == null) {
                device = devices.find { d ->
                    d.isSource && (
                        d.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && d.type == TYPE_BLE_HEADSET)
                    )
                }
            }

            return device != null && (
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == TYPE_BLE_HEADSET)
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error checking if device is Bluetooth: ${e.message}")
            return false
        }
    }

    /**
     * Sets up Bluetooth audio routing for the given device.
     * For Android S+: uses setCommunicationDevice (modern API)
     * For older versions: uses Bluetooth SCO (legacy API)
     * This must be called BEFORE prepare().
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun setupBluetoothAudio(context: Context, audioManager: AudioManager, device: AudioDeviceInfo) {
        // Save original audio mode
        originalAudioMode = audioManager.mode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android S+ (API 31+): Use modern setCommunicationDevice API
            try {
                audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
                Log.d(LOG_TAG, "Audio mode set to MODE_IN_COMMUNICATION (original: $originalAudioMode)")

                val success = audioManager.setCommunicationDevice(device)
                if (success) {
                    communicationDevice = device
                    isBluetoothScoEnabled = true
                    Log.d(LOG_TAG, "Communication device set successfully: ${device.productName} (ID: ${device.id})")
                } else {
                    Log.e(LOG_TAG, "Failed to set communication device: ${device.productName}")
                    audioManager.mode = originalAudioMode
                }
            } catch (e: Exception) {
                Log.e(LOG_TAG, "Error setting communication device: ${e.message}", e)
                audioManager.mode = originalAudioMode
            }
        } else {
            // Pre-Android S: Use legacy Bluetooth SCO
            setupBluetoothSco(context, audioManager)
        }
    }

    /**
     * Legacy Bluetooth SCO setup for Android versions before S.
     * Starts Bluetooth SCO and waits for connection with timeout.
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun setupBluetoothSco(context: Context, audioManager: AudioManager) {
        // Set audio mode BEFORE starting SCO
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        Log.d(LOG_TAG, "Audio mode set to MODE_IN_COMMUNICATION (original: $originalAudioMode)")

        // Start Bluetooth SCO
        if (!audioManager.isBluetoothScoOn) {
            val scoConnectionLatch = CountDownLatch(1)

            // Register receiver to monitor SCO state
            scoReceiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: android.content.Intent?) {
                    val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1)
                    when (state) {
                        AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                            Log.d(LOG_TAG, "Bluetooth SCO connected")
                            scoConnectionLatch.countDown()
                        }
                        AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                            Log.d(LOG_TAG, "Bluetooth SCO disconnected")
                        }
                        AudioManager.SCO_AUDIO_STATE_ERROR -> {
                            Log.e(LOG_TAG, "Bluetooth SCO error")
                            scoConnectionLatch.countDown()
                        }
                    }
                }
            }

            val filter = android.content.IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
            context.registerReceiver(scoReceiver, filter)

            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Log.d(LOG_TAG, "Bluetooth SCO start requested, waiting for connection...")

            // Wait for SCO connection with 3 second timeout
            try {
                val connected = scoConnectionLatch.await(3, TimeUnit.SECONDS)
                if (connected) {
                    Log.d(LOG_TAG, "Bluetooth SCO connection established")
                    isBluetoothScoEnabled = true
                } else {
                    Log.w(LOG_TAG, "Bluetooth SCO connection timeout (3s), proceeding anyway")
                    // Still mark as enabled so cleanup will run
                    isBluetoothScoEnabled = true
                }
            } catch (e: InterruptedException) {
                Log.e(LOG_TAG, "Interrupted while waiting for SCO connection: ${e.message}")
                isBluetoothScoEnabled = true
            }
        }
    }

    /**
     * Sets the preferred audio input device for the MediaRecorder.
     * This method is only available on Android P (API 28) and above.
     *
     * @param context Application context to access AudioManager
     * @param recorder MediaRecorder instance
     * @param deviceId The ID of the audio input device to use
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private fun setPreferredAudioDevice(context: Context, recorder: MediaRecorder, deviceId: Int) {
        try {
            val audioManager = context.getServiceOrNull<AudioManager>(Context.AUDIO_SERVICE) ?: run {
                Log.e(LOG_TAG, "AudioManager not available")
                return
            }

            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            var selectedDevice = devices.find { it.id == deviceId }

            if (selectedDevice != null) {
                // Check if this is an input device
                if (!selectedDevice.isSource) {
                    Log.w(LOG_TAG, "Device ${selectedDevice.productName} is not a source (input) device")
                    Log.d(LOG_TAG, "Falling back to default microphone")
                    return
                }

                // Check if this is a Bluetooth device (SCO or BLE headset, NOT A2DP)
                val isBluetooth = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    selectedDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    selectedDevice.type == TYPE_BLE_HEADSET
                } else {
                    selectedDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }

                if (isBluetooth) {
                    Log.d(LOG_TAG, "Detected Bluetooth input device: ${selectedDevice.productName} (Type: ${selectedDevice.type})")

                    // Check BLUETOOTH_CONNECT permission for Android S+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val hasPermission = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) {
                            Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted. Cannot use Bluetooth device for recording.")
                            Log.d(LOG_TAG, "Falling back to default microphone")
                            return
                        }
                    }

                    setupBluetoothAudio(context, audioManager, selectedDevice)
                }

                val success = recorder.setPreferredDevice(selectedDevice)
                if (success) {
                    Log.d(LOG_TAG, "Preferred audio device set: ${selectedDevice.productName} (ID: $deviceId, Type: ${selectedDevice.type})")
                } else {
                    Log.w(LOG_TAG, "Failed to set preferred device: ${selectedDevice.productName} (ID: $deviceId, Type: ${selectedDevice.type})")
                    // Fallback: cleanup Bluetooth if it was enabled
                    if (isBluetooth) {
                        cleanupBluetoothAudio(context)
                        Log.d(LOG_TAG, "Falling back to default microphone")
                    }
                }
            } else {
                Log.w(LOG_TAG, "Device ID $deviceId not found. Attempting fallback to find device by type...")

                // Fallback: Try to find a Bluetooth input device by type
                selectedDevice = devices.find { device ->
                    device.isSource && (
                        device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && device.type == TYPE_BLE_HEADSET)
                    )
                }

                if (selectedDevice != null) {
                    Log.d(LOG_TAG, "Fallback successful: Found Bluetooth device ${selectedDevice.productName} (ID: ${selectedDevice.id}, Type: ${selectedDevice.type})")

                    // Check BLUETOOTH_CONNECT permission for Android S+
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val hasPermission = context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        if (!hasPermission) {
                            Log.e(LOG_TAG, "BLUETOOTH_CONNECT permission not granted. Cannot use Bluetooth device for recording.")
                            Log.d(LOG_TAG, "Falling back to default microphone")
                            return
                        }
                    }

                    // Setup Bluetooth audio routing
                    setupBluetoothAudio(context, audioManager, selectedDevice)

                    val success = recorder.setPreferredDevice(selectedDevice)
                    if (success) {
                        Log.d(LOG_TAG, "Preferred audio device set via fallback: ${selectedDevice.productName} (ID: ${selectedDevice.id}, Type: ${selectedDevice.type})")
                    } else {
                        Log.w(LOG_TAG, "Failed to set preferred device via fallback: ${selectedDevice.productName}")
                        cleanupBluetoothAudio(context)
                        Log.d(LOG_TAG, "Falling back to default microphone")
                    }
                } else {
                    Log.w(LOG_TAG, "No suitable Bluetooth input device found. Available input devices:")
                    devices.filter { it.isSource }.forEach {
                        Log.w(LOG_TAG, "  - ${it.productName} (ID: ${it.id}, Type: ${it.type})")
                    }
                    Log.d(LOG_TAG, "Falling back to default microphone")
                }
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error setting preferred audio device: ${e.message}", e)
            // Fallback: cleanup any Bluetooth setup and use default
            if (isBluetoothScoEnabled) {
                cleanupBluetoothAudio(context)
            }
            Log.d(LOG_TAG, "Error occurred, falling back to default microphone")
        }
    }

    private inline fun <reified T> Context.getServiceOrNull(name: String): T? {
        return try {
            getSystemService(name) as? T
        } catch (e: Exception) {
            null
        }
    }

    private fun getEncoder(encoder: Int): Int {
        when (encoder) {
            Constants.acc -> return MediaRecorder.AudioEncoder.AAC
            Constants.aac_eld -> return MediaRecorder.AudioEncoder.AAC_ELD
            Constants.he_aac -> return MediaRecorder.AudioEncoder.HE_AAC
            Constants.amr_nb -> return MediaRecorder.AudioEncoder.AMR_NB
            Constants.amr_wb -> return MediaRecorder.AudioEncoder.AMR_WB
            Constants.opus -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.AudioEncoder.OPUS
                } else {
                    Log.e(LOG_TAG, "Minimum android Q is required, Setting Acc encoder.")
                    MediaRecorder.AudioEncoder.AAC
                }
            }

            Constants.vorbis -> return MediaRecorder.AudioEncoder.VORBIS

            else -> return MediaRecorder.AudioEncoder.AAC
        }
    }

    private fun getOutputFormat(format: Int): Int {
        when (format) {
            Constants.mpeg4 -> return MediaRecorder.OutputFormat.MPEG_4
            Constants.three_gpp -> return MediaRecorder.OutputFormat.THREE_GPP
            Constants.ogg -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.OutputFormat.OGG
                } else {
                    Log.e(LOG_TAG, "Minimum android Q is required, Setting Acc encoder.")
                    MediaRecorder.OutputFormat.MPEG_4
                }
            }

            Constants.amr_wb -> return MediaRecorder.OutputFormat.AMR_WB
            Constants.amr_nb -> return MediaRecorder.OutputFormat.AMR_NB
            Constants.webm ->
                return MediaRecorder.OutputFormat.WEBM

            Constants.mpeg_2_ts -> {
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    MediaRecorder.OutputFormat.MPEG_2_TS
                } else {
                    Log.e(LOG_TAG, "Minimum android Q is required, Setting MPEG_4 output format.")
                    MediaRecorder.OutputFormat.MPEG_4
                }
            }

            Constants.aac_adts -> return MediaRecorder.OutputFormat.AAC_ADTS
            else -> return MediaRecorder.OutputFormat.MPEG_4
        }
    }

    private fun getAudioSource(sourceIndex: Int?): Int {
        return when (sourceIndex) {
            0 -> MediaRecorder.AudioSource.MIC
            1 -> MediaRecorder.AudioSource.VOICE_RECOGNITION
            2 -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
            3 -> MediaRecorder.AudioSource.CAMCORDER
            4 -> {
                // UNPROCESSED requires Android Q (API 29)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaRecorder.AudioSource.UNPROCESSED
                } else {
                    Log.w(LOG_TAG, "UNPROCESSED source requires Android Q+, falling back to MIC")
                    MediaRecorder.AudioSource.MIC
                }
            }
            else -> {
                // Default to MIC if null or unknown value
                MediaRecorder.AudioSource.MIC
            }
        }
    }
}