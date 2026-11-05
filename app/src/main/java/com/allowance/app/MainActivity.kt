package com.allowance.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import android.view.WindowManager
import android.content.pm.ApplicationInfo
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.allowance.app.ui.AllowanceApp

class MainActivity : ComponentActivity() {
    private val model: AllowanceViewModel by viewModels()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_START) model.onAppStarted()
            if (event == Lifecycle.Event.ON_STOP && !isChangingConfigurations) model.onAppStopped()
        })
        setContent { AllowanceApp(model) }
    }
}

