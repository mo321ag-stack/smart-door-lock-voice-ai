package com.smartdoorlock.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.smartdoorlock.app.ui.AppRoot
import com.smartdoorlock.app.ui.theme.SmartDoorLockTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DoorLockViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SmartDoorLockTheme {
                AppRoot(viewModel)
            }
        }
    }
}
