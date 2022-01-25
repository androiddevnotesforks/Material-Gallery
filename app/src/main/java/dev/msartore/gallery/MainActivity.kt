package dev.msartore.gallery

import android.annotation.SuppressLint
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import dev.msartore.gallery.models.DeleteMediaVars
import dev.msartore.gallery.models.LoadingStatus
import dev.msartore.gallery.models.Media
import dev.msartore.gallery.models.MediaClass
import dev.msartore.gallery.ui.compose.*
import dev.msartore.gallery.ui.compose.basic.DialogContainer
import dev.msartore.gallery.ui.theme.GalleryTheme
import dev.msartore.gallery.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue


@SuppressLint("NewApi")
@OptIn(ExperimentalAnimationApi::class, ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {

    private var imageDeleteCounter = 0
    private var deleteInProgress = false
    private var updateNeeded = false
    private var deletedImageUri: Uri? = null
    private var deleteAction: (() -> Unit)? = null
    private var contentObserver: ContentObserver? = null
    private val updateList = MutableSharedFlow<Unit>()
    private val checkBoxVisible = mutableStateOf(false)
    private val mediaList = SnapshotStateList<MediaClass>()
    private val dialogLoadingStatus = LoadingStatus()
    private val updateCLDCache = MutableSharedFlow<Media>()
    private val concurrentLinkedQueueCache = ConcurrentLinkedQueue<Media>()
    private var intentSaveLocation =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { activityResult ->
            if (activityResult.resultCode == RESULT_OK) {
                val selectedList = mediaList.filter { it.selected.value }.map { it.uri }

                dialogLoadingStatus.count = selectedList.size

                cor {
                    dialogLoadingStatus.status.value = true

                    withContext(Dispatchers.IO) {
                        activityResult.data?.data?.toString()?.let { path ->
                            documentGeneration(
                                listImage = selectedList,
                                path = path,
                                contentResolver = contentResolver,
                                loadingStatus = dialogLoadingStatus
                            )
                        }

                        unselectAll()
                        vibrate(
                            duration = 250,
                            amplitude = VibrationEffect.CONTENTS_FILE_DESCRIPTOR
                        )
                    }

                    dialogLoadingStatus.status.value = false
                }
            }
        }
    private var intentSenderLauncher =
        registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { activityResult ->
            if (activityResult.resultCode == RESULT_OK) {

                if(Build.VERSION.SDK_INT in 28..29) {
                    lifecycleScope.launch {
                        setVarsAndDeleteImage(deletedImageUri ?: return@launch)
                    }
                }

                deleteAction?.invoke()

                updateNeeded = true

                runCatching {
                    mediaList.removeIf { it.uri == deletedImageUri }
                }.getOrElse {
                    it.printStackTrace()
                }
            }

            imageDeleteCounter -= 1

            if (imageDeleteCounter == 0) {

                unselectAll()
                deleteInProgress = false

                if (updateNeeded) {
                    updateNeeded = false
                    lifecycleScope.launch {
                        updateList.emit(Unit)
                    }
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)
        
        val mediaDeleteFlow = MutableSharedFlow<DeleteMediaVars>()
        val selectedMedia = mutableStateOf<MediaClass?>(null)
        var counterMedia = 0
        val loading = mutableStateOf(false)
        val getContent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode != RESULT_OK) {
                finishAffinity()
            }
        }
        val toolbarVisible = mutableStateOf(true)
        this.initContentResolver(contentResolver) {
            cor { updateList.emit(Unit) }
        }

        cor {
            updateCLDCache.collect { media ->

                if (concurrentLinkedQueueCache.any { it.index == media.index })
                    return@collect

                if (concurrentLinkedQueueCache.size > 300) {
                    concurrentLinkedQueueCache.poll()
                }

                concurrentLinkedQueueCache.add(media)
            }
        }

        cor {
            mediaDeleteFlow.collect {
                deleteInProgress = true
                checkBoxVisible.value = false
                imageDeleteCounter = it.listUri.size

                it.listUri.forEach { uri ->
                    setVarsAndDeleteImage(
                        uri,
                        it.action
                    )
                }
            }
        }

        cor {
            updateList.collect {

                if (!deleteInProgress)
                    cor {
                        loading.value = true

                        mediaList.clear()
                        concurrentLinkedQueueCache.clear()
                        mediaList.addAll(contentResolver.queryImageMediaStore { counterMedia = it })
                        mediaList.addAll(contentResolver.queryVideoMediaStore(counter = counterMedia) { counterMedia = it })
                        mediaList.sortByDescending { it.date }

                        delay(10)

                        loading.value = false
                        updateNeeded = false
                    }
            }
        }

        setContent {
            GalleryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = if (selectedMedia.value != null) Color.Black else MaterialTheme.colorScheme.background
                ) {
                    val scrollState = rememberLazyListState()

                    Box(modifier = Modifier.fillMaxSize()) {

                        FileAndMediaPermission(
                            navigateToSettingsScreen = {
                                getContent.launch(
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", packageName, null)
                                    )
                                )
                            },
                            onPermissionDenied = {
                                finishAffinity()
                            },
                            onPermissionGranted = {
                                LaunchedEffect(key1 = true) {
                                    updateList.emit(Unit)
                                }

                                AnimatedVisibility(
                                    visible = selectedMedia.value == null,
                                    enter = expandVertically(),
                                    exit = fadeOut()
                                ) {
                                    if (!loading.value)
                                        MediaListUI(
                                            concurrentLinkedQueue = concurrentLinkedQueueCache,
                                            updateCLDCache = updateCLDCache,
                                            lazyListState = scrollState,
                                            mediaList = mediaList,
                                            checkBoxVisible = checkBoxVisible,
                                        ) {
                                            selectedMedia.value = it
                                        }
                                }

                                if (selectedMedia.value == null) {
                                    if (loading.value) {
                                        CircularProgressIndicator(
                                            modifier = Modifier
                                                .height(100.dp)
                                                .width(100.dp)
                                                .align(Alignment.Center),
                                            color = Color.White
                                        )
                                    }
                                }

                                AnimatedVisibility(
                                    visible = selectedMedia.value != null && selectedMedia.value?.duration == null,
                                    enter = scaleIn()
                                ) {

                                    if (selectedMedia.value != null) {

                                        BackHandler(enabled = true){
                                            selectedMedia.value = null
                                        }

                                        contentResolver.ImageViewerUI(selectedMedia.value!!)
                                    }
                                }

                                AnimatedVisibility(
                                    visible = selectedMedia.value != null && selectedMedia.value?.duration != null,
                                    enter = scaleIn()
                                ) {

                                    if (selectedMedia.value != null) {

                                        VideoViewerUI(
                                            selectedMedia.value!!.uri,
                                            onBackPressedCallback = {
                                                selectedMedia.value = null
                                                toolbarVisible.value = true
                                            },
                                            onControllerVisibilityChange = {
                                                toolbarVisible.value =
                                                    when (it) {
                                                        VideoControllerVisibility.VISIBLE.value -> false
                                                        else -> true
                                                    }
                                            }
                                        )
                                    }
                                }

                                ToolBarUI(
                                    visible = ((scrollState.firstVisibleItemScrollOffset == 0) || !scrollState.isScrollInProgress || checkBoxVisible.value) && toolbarVisible.value,
                                    mediaList = mediaList,
                                    mediaDelete = mediaDeleteFlow,
                                    selectedMedia = selectedMedia,
                                    checkBoxVisible = checkBoxVisible,
                                    backgroundColor =
                                    if (selectedMedia.value == null) {
                                        if (scrollState.firstVisibleItemScrollOffset == 0) {
                                            MaterialTheme.colorScheme.background
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        }
                                    }
                                    else Color.Transparent,
                                    onPDFClick = {

                                        val intentCreateDocument = Intent(Intent.ACTION_CREATE_DOCUMENT)
                                        intentCreateDocument.addCategory(Intent.CATEGORY_OPENABLE)
                                        intentCreateDocument.type = "application/pdf"
                                        intentCreateDocument.putExtra(Intent.EXTRA_TITLE, "Material Gallery ${getDate()}.pdf")

                                        intentSaveLocation.launch(intentCreateDocument)
                                    }
                                )

                                if (dialogLoadingStatus.status.value)
                                    DialogContainer {
                                        Column(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(160.dp)
                                                .background(
                                                    color = MaterialTheme.colorScheme.onSecondary,
                                                    shape = RoundedCornerShape(16.dp)
                                                )
                                                .padding(25.dp),
                                            verticalArrangement = Arrangement.SpaceBetween,
                                            horizontalAlignment = Alignment.Start
                                        ) {
                                            Text(
                                                text = "Please wait...",
                                                style = MaterialTheme.typography.headlineSmall
                                            )

                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .wrapContentHeight(),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.Center
                                            ) {
                                                CircularProgressIndicator(
                                                    modifier = Modifier
                                                        .size(45.dp),
                                                    color = MaterialTheme.colorScheme.primary
                                                )

                                                Text(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    text = dialogLoadingStatus.text.value,
                                                    textAlign = TextAlign.Center,
                                                )
                                            }
                                        }
                                    }
                            }
                        )
                    }
                }
            }
        }
    }

    private fun unselectAll() {
        mediaList.forEach {
            it.selected.value = false
        }
        checkBoxVisible.value = false
    }

    private suspend fun setVarsAndDeleteImage(
        photoUri: Uri,
        actionAfterDelete: (() -> Unit)? = null
    ) {
        deletedImageUri = photoUri
        deleteAction = actionAfterDelete

        contentResolver.deletePhotoFromExternalStorage(photoUri, intentSenderLauncher)
    }

    override fun onDestroy() {
        super.onDestroy()

        contentObserver?.let { unregisterContentResolver(it) }
    }
}

