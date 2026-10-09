
package com.lecturesnap.ai

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private val screenCaptureLauncher =
        registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            if (result.resultCode == Activity.RESULT_OK &&
                result.data != null
            ) {
                val serviceIntent = Intent(
                    this,
                    ScreenCaptureService::class.java
                ).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_CODE,
                        result.resultCode
                    )
                    putExtra(
                        ScreenCaptureService.EXTRA_RESULT_DATA,
                        result.data
                    )
                }

                ContextCompat.startForegroundService(
                    this,
                    serviceIntent
                )
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                var status by remember {
                    mutableStateOf("Ready to capture lectures")
                }

                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            "LectureSnap AI",
                            style = MaterialTheme.typography.headlineMedium
                        )

                        Spacer(Modifier.height(12.dp))
                        Text("Automatic Lecture Notes")
                        Spacer(Modifier.height(24.dp))
                        Text(status)
                        Spacer(Modifier.height(24.dp))

                        Button(
                            onClick = {
                                val manager =
                                    getSystemService(
                                        Context.MEDIA_PROJECTION_SERVICE
                                    ) as MediaProjectionManager

                                screenCaptureLauncher.launch(
                                    manager.createScreenCaptureIntent()
                                )
                                status = "Waiting for screen-capture permission"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Start Lecture")
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                status = "Pause is not implemented yet"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Pause")
                        }

                        Spacer(Modifier.height(8.dp))

                        Button(
                            onClick = {
                                val stopIntent = Intent(
                                    this@MainActivity,
                                    ScreenCaptureService::class.java
                                ).apply {
                                    action = ScreenCaptureService.ACTION_STOP
                                }

                                startService(stopIntent)
                                status = "Capture stopped"
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Stop Lecture")
                        }

                        Spacer(Modifier.height(20.dp))
                        Text("Automatic screenshot saving is coming next.")
                    }
                }
            }
        }
    }
}
