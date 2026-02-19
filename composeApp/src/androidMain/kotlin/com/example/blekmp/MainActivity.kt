package com.example.blekmp

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.blekmp.ble.BleForegroundService
import com.example.blekmp.ui.BleScreen
import com.example.blekmp.ui.BleViewModel

class MainActivity : ComponentActivity() {

    private var bleService: BleForegroundService? = null
    private var isBound by mutableStateOf(false)
    private var viewModel: BleViewModel? = null
    private var isRequestingPermissions by mutableStateOf(false)
    private var serviceError by mutableStateOf<String?>(null)

    companion object {
        private const val TAG = "MainActivity"
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "Service connected successfully")
            val localBinder = binder as? BleForegroundService.LocalBinder
            bleService = localBinder?.getService()
            bleService?.let { service ->
                viewModel = BleViewModel(service.bleRepository)
                isBound = true
                serviceError = null
                Log.d(TAG, "ViewModel created and bound")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.d(TAG, "Service disconnected")
            bleService = null
            isBound = false
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        Log.d(TAG, "Permission result: $permissions")
        val allGranted = permissions.values.all { it }
        isRequestingPermissions = false

        if (allGranted) {
            Log.d(TAG, "All permissions granted, starting service")
            startAndBindService()
        } else {
            val deniedPermissions = permissions.filter { !it.value }.keys
            serviceError = "Permissions denied: $deniedPermissions"
            Log.e(TAG, "Permissions denied: $deniedPermissions")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize()
                ) {
                    when {
                        serviceError != null -> {
                            ErrorScreen(
                                error = serviceError!!,
                                onRetry = {
                                    serviceError = null
                                    checkAndRequestPermissions()
                                }
                            )
                        }
                        isBound && viewModel != null -> {
                            BleScreen(viewModel = viewModel!!)
                        }
                        else -> {
                            PermissionRequestScreen(
                                statusText = when {
                                    isRequestingPermissions -> "Waiting for permission dialog..."
                                    !hasRequiredPermissions() -> "Requesting permissions..."
                                    else -> "Initializing BLE service..."
                                }
                            )
                        }
                    }
                }
            }
        }

        // Check permissions immediately
        checkAndRequestPermissions()
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart, isBound: $isBound, hasPermissions: ${hasRequiredPermissions()}")

        // If we have permissions but not bound, try to bind
        if (!isBound && hasRequiredPermissions()) {
            bindToService()
        }
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        // Don't unbind here to keep the service running
        // We'll unbind in onDestroy
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): List<String> {
        return buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            } else {
                add(Manifest.permission.ACCESS_FINE_LOCATION)
                add(Manifest.permission.ACCESS_COARSE_LOCATION)
                add(Manifest.permission.BLUETOOTH)
                add(Manifest.permission.BLUETOOTH_ADMIN)
            }
        }
    }

    private fun checkAndRequestPermissions() {
        val permissions = getRequiredPermissions()
        Log.d(TAG, "Checking permissions: $permissions")

        val notGranted = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        Log.d(TAG, "Not granted permissions: $notGranted")

        if (notGranted.isEmpty()) {
            Log.d(TAG, "All permissions already granted")
            startAndBindService()
        } else {
            Log.d(TAG, "Requesting permissions: $notGranted")
            isRequestingPermissions = true
            permissionLauncher.launch(notGranted.toTypedArray())
        }
    }

    private fun startAndBindService() {
        startBleService()
        // Give the service a moment to start before binding
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            bindToService()
        }, 500)
    }

    private fun startBleService() {
        try {
            Log.d(TAG, "Starting BLE service")
            val intent = Intent(this, BleForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting service", e)
            serviceError = "Failed to start service: ${e.message}"
        }
    }

    private fun bindToService() {
        try {
            Log.d(TAG, "Binding to service")
            val intent = Intent(this, BleForegroundService::class.java)
            bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Error binding to service", e)
            serviceError = "Failed to bind to service: ${e.message}"
        }
    }
}

@Composable
fun PermissionRequestScreen(
    statusText: String
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = statusText,
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(bottom = 16.dp)
        )

        CircularProgressIndicator(
            modifier = Modifier.size(48.dp)
        )
    }
}

@Composable
fun ErrorScreen(
    error: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Error",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Text(
            text = error,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(bottom = 24.dp)
        )

        Button(
            onClick = onRetry
        ) {
            Text("Retry")
        }
    }
}