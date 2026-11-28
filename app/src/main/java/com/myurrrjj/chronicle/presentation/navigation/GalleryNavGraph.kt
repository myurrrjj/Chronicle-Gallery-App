package com.myurrrjj.chronicle.presentation.navigation

//import androidx.hilt.navigation.compose.hiltViewModel
import android.Manifest
import android.app.Activity
import android.os.Build
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest.Builder
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.myurrrjj.chronicle.FaceSortingLabScreen
import com.myurrrjj.chronicle.core.util.MediaIntents
import com.myurrrjj.chronicle.core.util.hasFullMediaAccess
import com.myurrrjj.chronicle.presentation.gallery.GalleryViewModel
import com.myurrrjj.chronicle.presentation.gallery.contract.GalleryEffect
import com.myurrrjj.chronicle.presentation.gallery.contract.GalleryEvent
import com.myurrrjj.chronicle.presentation.gallery.screens.DetailScreen
import com.myurrrjj.chronicle.presentation.gallery.screens.mainGridScreen.GalleryScreen
import com.myurrrjj.chronicle.presentation.map.PhotoMapScreen
import com.myurrrjj.chronicle.presentation.navigation.Route.Detail
import com.myurrrjj.chronicle.presentation.navigation.Route.DirtyTest
import com.myurrrjj.chronicle.presentation.navigation.Route.Gallery
import com.myurrrjj.chronicle.presentation.navigation.Route.Settings
import com.myurrrjj.chronicle.presentation.search.SearchScreen
import com.myurrrjj.chronicle.presentation.search.SearchViewModel
import com.myurrrjj.chronicle.presentation.settings.SettingsScreen
import com.myurrrjj.chronicle.trial.DirtyTestScreen

@Suppress("EffectKeys")
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun GalleryNavGraph() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val sharedViewModel: GalleryViewModel = hiltViewModel()
    val permissionToRequest = remember {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
                )

            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                arrayOf(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO
                )

            else ->
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE
                )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        sharedViewModel.setEvent(
            GalleryEvent.PermissionResult(
                hasFullMediaAccess(context)
            )
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        val granted = hasFullMediaAccess(context)

        if (
            !granted &&
            sharedViewModel.uiState.value.configState.hasPermission
        ) {
            navController.popBackStack<Gallery>(
                inclusive = false
            )
        }

        sharedViewModel.setEvent(
            GalleryEvent.PermissionResult(granted)
        )
    }

    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            sharedViewModel.setEvent(GalleryEvent.LoadMedia)
            sharedViewModel.setEvent(GalleryEvent.ExitSelectionMode)
        }
    }

    LaunchedEffect(Unit) {
        sharedViewModel.effect.collect { effect ->
            when (effect) {
                is GalleryEffect.RequestPermission -> permissionLauncher.launch(permissionToRequest)

                is GalleryEffect.NavigateToDetail -> navController.navigate(Detail(effect.mediaId))

                is GalleryEffect.ShareMedia -> {
                    MediaIntents.shareMediaBatch(context, effect.uris)
                    sharedViewModel.setEvent(GalleryEvent.ExitSelectionMode)
                }

                is GalleryEffect.CopyMedia -> MediaIntents.copyToClipboard(context, effect.uris)

                is GalleryEffect.EditMedia ->
                    MediaIntents.editMedia(context, effect.uriString, effect.mimeType)

                is GalleryEffect.DeleteMedia -> {
                    val uris = effect.uris.map { it.toUri() }
                    try {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            val pendingIntent =
                                MediaStore.createDeleteRequest(context.contentResolver, uris)
                            deleteLauncher.launch(Builder(pendingIntent.intentSender).build())
                        } else {
                            uris.forEach { context.contentResolver.delete(it, null, null) }
                            sharedViewModel.setEvent(GalleryEvent.LoadMedia)
                            sharedViewModel.setEvent(GalleryEvent.ExitSelectionMode)
                        }
                    } catch (e: SecurityException) {
                        Toast.makeText(context, "Cannot delete these files", Toast.LENGTH_SHORT)
                            .show()
                    }
                }

                GalleryEffect.NavigateToSettings -> navController.navigate(Settings)
                GalleryEffect.NavigateToSearch -> navController.navigate(Route.Search)
                GalleryEffect.NavigateToPhotoMap -> navController.navigate(Route.PhotoMap)
            }
        }
    }

    SharedTransitionLayout {
        NavHost(
            navController = navController,
            startDestination = Gallery,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            composable<DirtyTest> {
                DirtyTestScreen()
            }

            composable<Gallery>(
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(300, delayMillis = 90)) },
                popEnterTransition = { fadeIn(tween(300)) },
                popExitTransition = { fadeOut(tween(300)) }
            ) {
                GalleryScreen(
                    onRequestPermission = { permissionLauncher.launch(permissionToRequest) },
                    viewModel = sharedViewModel,
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable
                )
            }

            composable<Detail>(
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(300, delayMillis = 90)) },
                popEnterTransition = { fadeIn(tween(300)) },
                popExitTransition = { fadeOut(tween(300)) }
            ) { backStackEntry ->
                val args = backStackEntry.toRoute<Detail>()
                DetailScreen(
                    initialMediaId = args.mediaId,
                    onNavigateBack = { navController.popBackStack() },
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    viewModel = sharedViewModel
                )
            }

            composable<Settings> {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenFaceLab = { navController.navigate(DirtyTest) }
                )
            }

            composable<Route.Search>(
                enterTransition = { fadeIn(tween(300)) },
                exitTransition = { fadeOut(tween(300, delayMillis = 90)) },
                popEnterTransition = { fadeIn(tween(300)) },
                popExitTransition = { fadeOut(tween(300)) }
            ) { backStackEntry ->


                val searchViewModel: SearchViewModel =
                    hiltViewModel()
                SearchScreen(
                    sharedTransitionScope = this@SharedTransitionLayout,
                    animatedVisibilityScope = this@composable,
                    onNavigateBack = {
                        sharedViewModel.setEvent(GalleryEvent.ClearSearchMediaList)
                        navController.popBackStack()
                    },
                    onNavigateToDetail = { mediaId ->
                        sharedViewModel.clearDetailScope()
                        navController.navigate(Detail(mediaId))
                    },
                    galleryViewModel = sharedViewModel,
                    viewModel = searchViewModel
                )
            }

            composable<Route.PhotoMap> {
                PhotoMapScreen(
                    onBackClick = { navController.popBackStack() },
                    onPhotoClick = { mediaId, scopeIds ->
                        sharedViewModel.openScopedDetail(scopeIds)
                        navController.navigate(Detail(mediaId))
                    }
                )
            }



            composable<Route.FaceLab> { FaceSortingLabScreen() }
        }
    }
}
