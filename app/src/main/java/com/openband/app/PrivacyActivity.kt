package com.openband.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openband.app.ui.OpenBandTheme

/** Shown by Health Connect when the user taps "privacy policy" for this app. */
class PrivacyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            OpenBandTheme {
                Surface {
                    Column(
                        Modifier.safeDrawingPadding().padding(24.dp).verticalScroll(rememberScrollState())
                    ) {
                        Text("Privacy", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            "\nOpenBand keeps all health data on this phone only. It has no account, no server and " +
                                "no analytics.\n\n" +
                                "• Health Connect data is read so it can be shown in one place.\n" +
                                "• Data read from your band over Bluetooth can be written to Health Connect " +
                                "(you can turn this off in the Health tab).\n" +
                                "• Exports (CSV / Lab report) are only created when you tap Export, and only go " +
                                "where you choose to share them.\n\n" +
                                "Uninstalling the app deletes its database.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Button(onClick = { finish() }, modifier = Modifier.padding(top = 24.dp)) { Text("Close") }
                    }
                }
            }
        }
    }
}
