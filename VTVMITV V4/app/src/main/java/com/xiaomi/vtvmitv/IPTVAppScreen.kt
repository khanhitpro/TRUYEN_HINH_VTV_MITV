package com.xiaomi.vtvmitv

import android.graphics.PixelFormat
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.compose.animation.*
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
            .setBufferDurationsMs(15000, 35000, 500, 1500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        // BẬT GIẢI MÃ PHẦN CỨNG ƯU TIÊN VPU/GPU ĐỂ XỬ LÝ HÌNH ẢNH NÉT NHẤT
        val renderersFactory = DefaultRenderersFactory(context).apply {
            setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
            setEnableDecoderFallback(true)
            setMediaCodecSelector(MediaCodecSelector.DEFAULT)
        }

        // ÉP CHẶT ĐỘ PHÂN GIẢI CAO NHẤT, BỎ QUA HOÀN TOÀN CÁC PROFILE MỜ
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setForceHighestSupportedBitrate(true)
                    .setMinVideoSize(1280, 720)
                    .setMaxVideoSize(3840, 2160)
                    .setMaxVideoBitrate(Int.MAX_VALUE)
                    .setMinVideoBitrate(2_500_000)
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
                // Khử kéo dãn sai tỷ lệ, ép chuẩn 16:9 sắc cạnh
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }

        Triple(player, dataSourceFactory, selector)
    }

    fun playCurrentStream() {
        if (currentChannel == null || currentChannel.streamUrls.isEmpty()) return
        val safeIdx = currentStreamIndex.coerceIn(0, currentChannel.streamUrls.size - 1)
        val streamUrl = currentChannel.streamUrls.getOrNull(safeIdx) ?: return

        try {
            val mediaItem = MediaItem.Builder()
                .setUri(streamUrl)
                .setMimeType(MimeTypes.APPLICATION_M3U8)
                .build()

            val mediaSource = HlsMediaSource.Factory(httpDataSourceFactory)
                .setAllowChunklessPreparation(true)
                .createMediaSource(mediaItem)

            exoPlayer.stop()
            exoPlayer.setMediaSource(mediaSource)
            exoPlayer.prepare()
            exoPlayer.play()
            showOsd = true
            onChannelChanged(currentChannel, currentChannelIndex)
        } catch (_: Throwable) {}
    }

    // Tự động ép khóa chặt profile nét nhất ngay khi phân tích xong luồng video
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
        onDispose {
            exoPlayer.removeListener(tracksListener)
        }
    }

    LaunchedEffect(initialIndex) {
        if (channels.isNotEmpty() && initialIndex in channels.indices && initialIndex != currentChannelIndex) {
            currentChannelIndex = initialIndex
            currentStreamIndex = 0
            retryCount = 0
            showChannelList = false
            showOsd = true
            playCurrentStream()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> exoPlayer.pause()
                Lifecycle.Event.ON_RESUME -> {
                    if (exoPlayer.playbackState != Player.STATE_IDLE) exoPlayer.play()
                }
                Lifecycle.Event.ON_DESTROY -> exoPlayer.release()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            exoPlayer.release()
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
        // TẦNG HIỂN THỊ VIDEO SURFACE VIEW CHẤT LƯỢNG CAO (RGBA_8888)
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    isFocusable = false
                    isFocusableInTouchMode = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )

                    // Ép tầng Surface bên dưới chạy dải màu 32-bit RGBA_8888 chống sương mù, tăng độ sâu điểm ảnh
                    val videoSurface = videoSurfaceView
                    if (videoSurface is SurfaceView) {
                        videoSurface.holder.setFormat(PixelFormat.RGBA_8888)
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // LỚP LỌC VI CHỈNH ĐỘ TƯƠNG PHẢN (CONTRAST TUNING OVERLAY)
        // Giúp loại bỏ màng sương mờ của luồng HLS, làm màu đen sâu thẳm và màu sắc nổi bật rõ rệt
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.08f),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.12f)
                        )
                    )
                )
        )

        // OSD THÔNG TIN
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

                        Spacer(modifier = Modifier.width(12.dp))

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

        // BẢNG DANH SÁCH KÊNH
        AnimatedVisibility(
            visible = showChannelList,
            enter = fadeIn() + slideInHorizontally { -it / 2 },
            exit = fadeOut() + slideOutHorizontally { -it / 2 },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = 24.dp, top = 20.dp, bottom = 20.dp)
                .width(328.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF070707).copy(alpha = 0.88f))
                    .border(
                        width = 1.5.dp,
                        color = Color(0xFF00E676).copy(alpha = 0.50f),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF00E676).copy(alpha = 0.22f))
                                .border(1.4.dp, Color(0xFF00E676), RoundedCornerShape(50))
                                .padding(horizontal = 22.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "DANH SÁCH KÊNH",
                                color = Color(0xFF00E676),
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Black,
                                letterSpacing = 1.sp,
                                style = TextStyle(
                                    shadow = Shadow(
                                        color = Color.Black,
                                        offset = Offset(1.5f, 1.5f),
                                        blurRadius = 3f
                                    )
                                )
                            )
                        }
                    }

                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        itemsIndexed(
                            items = channels,
                            key = { index, item -> "${index}_${item.name}" }
                        ) { index, channel ->
                            val isPlaying = index == currentChannelIndex
                            val logoResId = LauncherChannelBridge.getLocalLogoResId(context, channel.name)

                            ChannelBoxItem(
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
private fun ChannelBoxItem(
    index: Int,
    channel: Channel,
    logoResId: Int,
    isPlaying: Boolean,
    itemFocusRequester: FocusRequester? = null,
    onSelect: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()

    val cardBorderModifier = when {
        isFocused -> Modifier.border(2.2.dp, Color(0xFF00E676), RoundedCornerShape(18.dp))
        isPlaying -> Modifier.border(1.4.dp, Color(0xFF00E676).copy(alpha = 0.60f), RoundedCornerShape(18.dp))
        else -> Modifier.border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
    }

    var itemModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(
            if (isFocused) Color(0xFF00E676).copy(alpha = 0.22f)
            else if (isPlaying) Color.Black.copy(alpha = 0.65f)
            else Color(0xFF121212).copy(alpha = 0.40f)
        )
        .then(cardBorderModifier)

    if (itemFocusRequester != null) {
        itemModifier = itemModifier.focusRequester(itemFocusRequester)
    }

    Box(
        modifier = itemModifier
            .focusable(interactionSource = interactionSource)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) {
                onSelect()
            }
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(54.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White)
                    .border(1.dp, Color.Black.copy(alpha = 0.15f), RoundedCornerShape(7.dp))
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

            Spacer(modifier = Modifier.width(14.dp))

            Text(
                text = "${index + 1}. ${channel.name}",
                color = when {
                    isFocused -> Color(0xFF00E676)
                    isPlaying -> Color(0xFF69F0AE)
                    else -> Color(0xFFFFFFFF)
                },
                fontSize = 15.5.sp,
                fontWeight = if (isFocused || isPlaying) FontWeight.Black else FontWeight.Bold,
                maxLines = 1,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.95f),
                        offset = Offset(2f, 2f),
                        blurRadius = 3f
                    )
                )
            )
        }
    }
}