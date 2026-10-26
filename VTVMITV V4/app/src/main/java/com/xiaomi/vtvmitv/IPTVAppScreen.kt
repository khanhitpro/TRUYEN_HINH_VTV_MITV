package com.xiaomi.vtvmitv

import android.view.KeyEvent
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@UnstableApi
object IPTVPlayerController {
    var activePlayer: ExoPlayer? = null
    var onSwitchChannel: ((Int) -> Unit)? = null

    fun stopAndRelease() {
        try {
            activePlayer?.apply {
                playWhenReady = false
                stop()
                clearMediaItems()
            }
        } catch (_: Throwable) {}
    }

    fun switchChannelDirectly(index: Int): Boolean {
        return onSwitchChannel?.let {
            it(index)
            true
        } ?: false
    }
}

@OptIn(UnstableApi::class)
@Composable
fun IPTVAppScreen(
    channels: List<Channel>,
    initialIndex: Int = 0,
    onChannelChanged: (Channel, Int) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var currentChannelIndex by remember { mutableIntStateOf(initialIndex.coerceAtLeast(0)) }
    var currentStreamIndex by remember { mutableIntStateOf(0) }
    var showChannelList by remember { mutableStateOf(false) }
    var showOsd by remember { mutableStateOf(true) }
    var retryCount by remember { mutableIntStateOf(0) }

    val networkState by produceState(initialValue = NetworkInfoState()) {
        NetworkSpeedMonitor.observeNetworkSpeed(context).collect { value = it }
    }

    var isDownloading by remember { mutableStateOf(false) }
    var downloadProgress by remember { mutableIntStateOf(0) }
    val targetApkUrl = "https://khoappxiaomi.storetv.workers.dev/apk/mitv_update.apk"

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val currentChannel = channels.getOrNull(currentChannelIndex)

    val (exoPlayer, httpDataSourceFactory, trackSelector) = remember {
        val dataSourceFactory = DefaultHttpDataSource.Factory()
            .setUserAgent("Mozilla/5.0 (Android TV; SMART-TV; Linux) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
            .setConnectTimeoutMs(8000)
            .setReadTimeoutMs(8000)
            .setAllowCrossProtocolRedirects(true)
            .setKeepPostFor302Redirects(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(6000, 30000, 800, 3000)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(10000, true)
            .build()

        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
        }

        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setMinVideoSize(1280, 720)
                    .setMaxVideoSize(3840, 2160)
                    .setViewportSizeToPhysicalDisplaySize(context, true)
                    .setAllowVideoMixedMimeTypeAdaptiveness(true)
                    .setAllowVideoNonSeamlessAdaptiveness(true)
            )
        }

        val player = ExoPlayer.Builder(context, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(selector)
            .build().apply {
                playWhenReady = true
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
            }

        IPTVPlayerController.activePlayer = player

        Triple(player, dataSourceFactory, selector)
    }

    // HÀM PHÁT DUY NHẤT: CHUYỂN NGUỒN MƯỢT MÀ, KHÔNG GÂY TREO BỘ GIẢI MÃ
    fun playCurrentStream() {
        if (channels.isEmpty()) return
        val channel = channels.getOrNull(currentChannelIndex) ?: return
        if (channel.streamUrls.isEmpty()) return

        val safeIdx = currentStreamIndex.coerceIn(0, channel.streamUrls.size - 1)
        val streamUrl = channel.streamUrls.getOrNull(safeIdx) ?: return

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .setLiveConfiguration(
                    MediaItem.LiveConfiguration.Builder()
                        .setTargetOffsetMs(5000)
                        .setMinOffsetMs(2000)
                        .setMaxOffsetMs(15000)
                        .setMinPlaybackSpeed(0.97f)
                        .setMaxPlaybackSpeed(1.03f)
                        .build()
                )
                .build()

            val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)

            // Thay thế luồng phát trực tiếp - ExoPlayer tự động chuyển mượt, không gọi .stop() làm đơ MediaCodec
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.playWhenReady = true

            showOsd = true
            onChannelChanged(channel, currentChannelIndex)
        } catch (_: Throwable) {}
    }

    // Đăng ký Callback: CHỈ CẬP NHẬT BIẾN INDEX, ĐỂ DISPOSABLEEFFECT TỰ KÍCH HOẠT PHÁT (TRÁNH GỌI KÉP)
    DisposableEffect(channels) {
        IPTVPlayerController.onSwitchChannel = { targetIdx ->
            if (channels.isNotEmpty() && targetIdx in channels.indices) {
                if (currentChannelIndex == targetIdx) {
                    playCurrentStream()
                } else {
                    currentChannelIndex = targetIdx
                    currentStreamIndex = 0
                    retryCount = 0
                }
                showChannelList = false
                showOsd = true
            }
        }
        onDispose {
            IPTVPlayerController.onSwitchChannel = null
        }
    }

    // ĐIỂM KÍCH HOẠT DUY NHẤT: MỖI KHI ĐỔI KÊNH HOẶC ĐỔI LUỒNG
    DisposableEffect(currentChannelIndex, currentStreamIndex) {
        playCurrentStream()

        val listener = object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                val streams = currentChannel?.streamUrls ?: return
                if (retryCount < 2) {
                    retryCount++
                    coroutineScope.launch {
                        delay(1200)
                        playCurrentStream()
                    }
                    return
                }

                val nextIndex = currentStreamIndex + 1
                if (nextIndex < streams.size) {
                    retryCount = 0
                    val fallbackName = if (nextIndex == 1) "Dự phòng 1" else "Dự phòng 2"
                    Toast.makeText(context, "Đang kết nối lại $fallbackName...", Toast.LENGTH_SHORT).show()
                    currentStreamIndex = nextIndex
                } else {
                    coroutineScope.launch {
                        delay(2000)
                        currentStreamIndex = 0
                        retryCount = 0
                        playCurrentStream()
                    }
                }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    retryCount = 0
                }
            }
        }
        exoPlayer.addListener(listener)
        onDispose { exoPlayer.removeListener(listener) }
    }

    DisposableEffect(exoPlayer) {
        val tracksListener = object : Player.Listener {
            override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                for (trackGroup in tracks.groups) {
                    if (trackGroup.type == C.TRACK_TYPE_VIDEO) {
                        var bestTrackIndex = -1
                        var maxBitrate = -1
                        var maxHeight = -1

                        for (i in 0 until trackGroup.length) {
                            val format = trackGroup.getTrackFormat(i)
                            if (trackGroup.isTrackSupported(i)) {
                                if (format.height > maxHeight || (format.height == maxHeight && format.bitrate > maxBitrate)) {
                                    maxHeight = format.height
                                    maxBitrate = format.bitrate
                                    bestTrackIndex = i
                                }
                            }
                        }

                        if (bestTrackIndex != -1) {
                            try {
                                val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, bestTrackIndex)
                                trackSelector.setParameters(
                                    trackSelector.buildUponParameters()
                                        .clearOverridesOfType(C.TRACK_TYPE_VIDEO)
                                        .addOverride(override)
                                )
                            } catch (_: Throwable) {}
                        }
                    }
                }
            }
        }
        exoPlayer.addListener(tracksListener)
        onDispose { exoPlayer.removeListener(tracksListener) }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> {
                    exoPlayer.playWhenReady = false
                }
                Lifecycle.Event.ON_RESUME -> {
                    if (exoPlayer.playbackState == Player.STATE_IDLE) {
                        playCurrentStream()
                    } else {
                        exoPlayer.playWhenReady = true
                    }
                }
                Lifecycle.Event.ON_DESTROY -> {
                    IPTVPlayerController.stopAndRelease()
                    exoPlayer.release()
                    IPTVPlayerController.activePlayer = null
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            IPTVPlayerController.stopAndRelease()
            exoPlayer.release()
            IPTVPlayerController.activePlayer = null
        }
    }

    LaunchedEffect(currentChannelIndex, currentStreamIndex, showOsd) {
        if (showOsd) {
            delay(4000)
            showOsd = false
        }
    }

    LaunchedEffect(showChannelList) {
        if (showChannelList && channels.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    listState.scrollToItem(currentChannelIndex.coerceAtLeast(0))
                    delay(60)
                    listFocusRequester.requestFocus()
                } catch (_: Throwable) {}
            }
        } else {
            coroutineScope.launch {
                delay(40)
                try { mainFocusRequester.requestFocus() } catch (_: Throwable) {}
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(mainFocusRequester)
            .focusable(!showChannelList)
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown) {
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> {
                            if (!showChannelList && channels.isNotEmpty()) {
                                currentChannelIndex = (currentChannelIndex + 1) % channels.size
                                currentStreamIndex = 0
                                retryCount = 0
                                showOsd = true
                                true
                            } else false
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                            if (!showChannelList && channels.isNotEmpty()) {
                                currentChannelIndex = if (currentChannelIndex - 1 < 0) channels.size - 1 else currentChannelIndex - 1
                                currentStreamIndex = 0
                                retryCount = 0
                                showOsd = true
                                true
                            } else false
                        }

                        KeyEvent.KEYCODE_DPAD_LEFT -> {
                            showChannelList = !showChannelList
                            true
                        }

                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (showChannelList) {
                                showChannelList = false
                                true
                            } else {
                                val streams = currentChannel?.streamUrls ?: emptyList()
                                if (streams.size > 1) {
                                    currentStreamIndex = (currentStreamIndex + 1) % streams.size
                                    retryCount = 0
                                    showOsd = true
                                }
                                true
                            }
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!showChannelList) {
                                retryCount = 0
                                playCurrentStream()
                                Toast.makeText(context, "Đang tải lại...", Toast.LENGTH_SHORT).show()
                                true
                            } else false
                        }

                        KeyEvent.KEYCODE_BACK -> {
                            if (showChannelList) {
                                showChannelList = false
                                true
                            } else false
                        }

                        else -> false
                    }
                } else false
            }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    keepScreenOn = true
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            update = { playerView ->
                if (playerView.player != exoPlayer) {
                    playerView.player = exoPlayer
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // THANH THÔNG TIN OSD
        AnimatedVisibility(
            visible = showOsd && !showChannelList,
            enter = fadeIn() + slideInVertically { it / 2 },
            exit = fadeOut() + slideOutVertically { it / 2 },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.70f),
                                Color.Black.copy(alpha = 0.96f)
                            )
                        )
                    )
                    .padding(horizontal = 38.dp, vertical = 24.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RealtimeClockBadge()

                        Spacer(modifier = Modifier.width(18.dp))

                        Column {
                            Text(
                                text = "${currentChannelIndex + 1}. ${currentChannel?.name ?: "VTV"}",
                                color = Color.White,
                                fontSize = 23.sp,
                                fontWeight = FontWeight.ExtraBold,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = Color.Black.copy(alpha = 0.95f),
                                        offset = Offset(2f, 2f),
                                        blurRadius = 3f
                                    )
                                )
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "Phát trực tiếp chuẩn truyền hình số vệ tinh • Tỷ lệ 16:9",
                                color = Color(0xFFD6D6D6),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Medium,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(1.5f, 1.5f),
                                        blurRadius = 2f
                                    )
                                )
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF141414), RoundedCornerShape(6.dp))
                                .border(1.2.dp, Color(0xFF333333), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(7.dp)
                                        .clip(CircleShape)
                                        .background(if (networkState.isGoodQuality) Color(0xFF00E676) else Color(0xFFFF5252))
                                )

                                Spacer(modifier = Modifier.width(7.dp))

                                Text(
                                    text = "${networkState.connectionType} • ${networkState.speedText}",
                                    color = if (networkState.isGoodQuality) Color.White else Color(0xFFFFD600),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    style = TextStyle(
                                        shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 2f)
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .background(Color(0xFF141414), RoundedCornerShape(6.dp))
                                .border(1.2.dp, Color(0xFF444444), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            val streamText = when (currentStreamIndex) {
                                0 -> "Luồng chính"
                                1 -> "Dự phòng 1"
                                else -> "Dự phòng 2"
                            }
                            Text(
                                text = streamText,
                                color = if (currentStreamIndex == 0) Color(0xFF00E676) else Color(0xFFFFD600),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                style = TextStyle(
                                    shadow = Shadow(color = Color.Black, offset = Offset(1f, 1f), blurRadius = 2f)
                                )
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE50914), RoundedCornerShape(6.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "1080p FHD",
                                color = Color.White,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 0.5.sp,
                                style = TextStyle(
                                    shadow = Shadow(color = Color.Black, offset = Offset(1.5f, 1.5f), blurRadius = 2f)
                                )
                            )
                        }
                    }
                }
            }
        }

        // BẢNG DANH SÁCH KÊNH SIDEBAR THƯƠNG MẠI
        AnimatedVisibility(
            visible = showChannelList,
            enter = fadeIn(tween(220)) + slideInHorizontally(tween(260)) { -it },
            exit = fadeOut(tween(180)) + slideOutHorizontally(tween(220)) { -it },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(360.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                Color(0xFF0C0909).copy(alpha = 0.95f),
                                Color(0xFF100B0B).copy(alpha = 0.90f),
                                Color(0xFF140D0D).copy(alpha = 0.80f)
                            )
                        )
                    )
                    .border(
                        width = 1.2.dp,
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color(0xFFE50914).copy(alpha = 0.7f),
                                Color(0xFFE50914).copy(alpha = 0.2f),
                                Color.Transparent
                            )
                        ),
                        shape = RoundedCornerShape(topEnd = 24.dp, bottomEnd = 24.dp)
                    )
                    .padding(start = 20.dp, end = 16.dp, top = 28.dp, bottom = 20.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .height(18.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(Color(0xFFE50914))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "TRUYỀN HÌNH VTV MITV",
                                    color = Color.White,
                                    fontSize = 15.5.sp,
                                    fontWeight = FontWeight.Black,
                                    letterSpacing = 0.8.sp
                                )
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${channels.size} Kênh phát sóng trực tiếp",
                                color = Color(0xFFA59090),
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        val downloadInteraction = remember { MutableInteractionSource() }
                        val isDownloadFocused by downloadInteraction.collectIsFocusedAsState()

                        fun triggerDownload() {
                            if (!isDownloading) {
                                isDownloading = true
                                downloadProgress = 0
                                Toast.makeText(context, "Đang tải bản cập nhật...", Toast.LENGTH_SHORT).show()
                                coroutineScope.launch {
                                    ApkDownloader.downloadAndInstall(
                                        context = context,
                                        apkUrl = targetApkUrl,
                                        onProgress = { progress -> downloadProgress = progress },
                                        onError = { err ->
                                            isDownloading = false
                                            Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                        }
                                    )
                                    isDownloading = false
                                }
                            }
                        }

                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (isDownloadFocused) Color(0xFFE50914)
                                    else Color(0xFF2B1416)
                                )
                                .border(
                                    width = if (isDownloadFocused) 1.8.dp else 1.dp,
                                    color = if (isDownloadFocused) Color.White else Color(0xFFE50914).copy(alpha = 0.5f),
                                    shape = RoundedCornerShape(10.dp)
                                )
                                .focusable(interactionSource = downloadInteraction)
                                .clickable(interactionSource = downloadInteraction, indication = null) {
                                    triggerDownload()
                                }
                                .onKeyEvent { keyEvent ->
                                    if (keyEvent.type == KeyEventType.KeyDown &&
                                        (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                                                keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                                    ) {
                                        triggerDownload()
                                        true
                                    } else false
                                }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = if (isDownloading) "$downloadProgress%" else "⬇ UPDATE",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }

                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = channels,
                            key = { index, item -> "${index}_${item.name}" }
                        ) { index, channel ->
                            val isPlaying = index == currentChannelIndex
                            val logoResId = LauncherChannelBridge.getLocalLogoResId(context, channel.name)

                            CommercialChannelCard(
                                index = index,
                                channel = channel,
                                logoResId = logoResId,
                                isPlaying = isPlaying,
                                itemFocusRequester = if (isPlaying || (currentChannelIndex !in channels.indices && index == 0)) listFocusRequester else null,
                                onSelect = {
                                    currentChannelIndex = index
                                    currentStreamIndex = 0
                                    retryCount = 0
                                    showChannelList = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        try { mainFocusRequester.requestFocus() } catch (_: Throwable) {}
    }
}

@Composable
private fun RealtimeClockBadge() {
    var timeText by remember { mutableStateOf("00:00") }
    LaunchedEffect(Unit) {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        while (true) {
            timeText = sdf.format(Date())
            delay(1000)
        }
    }

    Box(
        modifier = Modifier
            .background(Color(0xFF101010), RoundedCornerShape(6.dp))
            .border(1.2.dp, Color(0xFF4F4F4F), RoundedCornerShape(6.dp))
            .padding(horizontal = 11.dp, vertical = 5.dp)
    ) {
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 17.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Black,
            style = TextStyle(
                shadow = Shadow(
                    color = Color.Black,
                    offset = Offset(1.5f, 1.5f),
                    blurRadius = 2.5f
                )
            )
        )
    }
}

@Composable
private fun CommercialChannelCard(
    index: Int,
    channel: Channel,
    logoResId: Int,
    isPlaying: Boolean,
    itemFocusRequester: FocusRequester? = null,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.03f else 1.0f,
        animationSpec = tween(durationMillis = 150),
        label = "CardScale"
    )

    val cardBackgroundModifier = when {
        isFocused -> Modifier.background(
            Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFF00E676),
                    Color(0xFF00C853)
                )
            )
        )
        isPlaying -> Modifier.background(
            Brush.horizontalGradient(
                colors = listOf(
                    Color(0xFFE50914).copy(alpha = 0.25f),
                    Color(0xFF2B1214).copy(alpha = 0.40f)
                )
            )
        )
        else -> Modifier.background(Color(0xFF1A1314).copy(alpha = 0.55f))
    }

    val cardBorderModifier = when {
        isFocused -> Modifier.border(2.dp, Color.White, RoundedCornerShape(12.dp))
        isPlaying -> Modifier.border(1.2.dp, Color(0xFFE50914).copy(alpha = 0.75f), RoundedCornerShape(12.dp))
        else -> Modifier.border(1.dp, Color.White.copy(alpha = 0.05f), RoundedCornerShape(12.dp))
    }

    var itemModifier = Modifier
        .fillMaxWidth()
        .scale(scale)
        .clip(RoundedCornerShape(12.dp))
        .then(cardBackgroundModifier)
        .then(cardBorderModifier)

    if (itemFocusRequester != null) {
        itemModifier = itemModifier.focusRequester(itemFocusRequester)
    }

    Box(
        modifier = itemModifier
            .focusable(interactionSource = interactionSource)
            .clickable(interactionSource = interactionSource, indication = null) { onSelect() }
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown &&
                    (keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_ENTER ||
                            keyEvent.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER)
                ) {
                    onSelect()
                    true
                } else false
            }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = String.format("%02d", index + 1),
                    color = when {
                        isFocused -> Color.Black.copy(alpha = 0.85f)
                        isPlaying -> Color(0xFFFF5252)
                        else -> Color(0xFF7A6B6D)
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.width(26.dp)
                )

                Box(
                    modifier = Modifier
                        .width(52.dp)
                        .height(30.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.White)
                        .border(1.dp, Color.Black.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = if (logoResId != 0) logoResId else R.drawable.ic_app_mitv),
                        contentDescription = channel.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Text(
                    text = channel.name,
                    color = if (isFocused) Color.Black else Color.White,
                    fontSize = 14.5.sp,
                    fontWeight = if (isFocused || isPlaying) FontWeight.Black else FontWeight.SemiBold,
                    maxLines = 1,
                    style = TextStyle(
                        shadow = if (isFocused) null else Shadow(
                            color = Color.Black.copy(alpha = 0.90f),
                            offset = Offset(1.5f, 1.5f),
                            blurRadius = 2.5f
                        )
                    )
                )
            }

            if (isPlaying) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(if (isFocused) Color.Black.copy(alpha = 0.25f) else Color(0xFFE50914))
                        .padding(horizontal = 6.dp, vertical = 2.5.dp)
                ) {
                    Text(
                        text = "LIVE",
                        color = Color.White,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 0.5.sp
                    )
                }
            }
        }
    }
}