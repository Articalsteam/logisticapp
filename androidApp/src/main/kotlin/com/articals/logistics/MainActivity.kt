package com.articals.logistics

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.articals.logistics.ui.ArticalsApp

/**
 * The only activity in the app.
 *
 * Everything below it is Compose Multiplatform code shared with iOS, which is why
 * this class has no layout, no navigation and no logic of its own. The dependency
 * graph is already built by [ArticalsApplication] before this runs.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The interface draws its own bars, so the content goes edge to edge and the
        // screens inset themselves where it matters.
        enableEdgeToEdge()
        setContent { ArticalsApp() }
    }
}
