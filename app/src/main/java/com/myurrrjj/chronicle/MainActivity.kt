package com.myurrrjj.chronicle

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
//import androidx.compose.ui.graphics.Color
import com.myurrrjj.chronicle.presentation.navigation.GalleryNavGraph
import com.yourname.gallery.core.presentation.theme.JetpackComposeGalleryAppTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(lightScrim = Color.TRANSPARENT, darkScrim = Color.TRANSPARENT)
        )



        setContent {
            JetpackComposeGalleryAppTheme {
                GalleryNavGraph()
            }
        }
    }

}