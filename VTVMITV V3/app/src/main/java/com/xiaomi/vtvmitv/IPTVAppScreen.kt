package com.xiaomi.vtvmitv

import android.view.KeyEvent
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
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

    val mainFocusRequester = remember { FocusRequester() }
    val listFocusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()

    val currentChannel = channels.getOrNull(currentChannelIndex)

    val exoPlayer = remember {
        val httpDataSourceFactory = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(6000)
            .setReadTimeoutMs(6000)
            .setAllowCrossProtocolRedirects(true)

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 30000, 1500, 2500)
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(httpDataSourceFactory))
            .setLoadControl(loadControl)
            .build().apply {
                playWhenReady = true
            }
    }

    fun playCurrentStream() {
        if (currentChannel == null || currentChannel.streamUrls.isEmpty()) return
        val safeIdx = currentStreamIndex.coerceIn(0, currentChannel.streamUrls.size - 1)
        val streamUrl = currentChannel.streamUrls.getOrNull(safeIdx) ?: return

        try {
            val mediaItem = MediaItem.fromUri(streamUrl)
            exoPlayer.setMediaItem(mediaItem)
            exoPlayer.prepare()
            exoPlayer.play()
            showOsd = true
            onChannelChanged(currentChannel, currentChannelIndex)
        } catch (_: Throwable) {}
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
                val nextIndex = currentStreamIndex + 1

                if (nextIndex < streams.size) {
                    val fallbackName = if (nextIndex == 1) "Dự phòng 1" else "Dự phòng 2"
                    Toast.makeText(context, "Luồng lỗi, đang chuyển sang $fallbackName...", Toast.LENGTH_SHORT).show()
                    currentStreamIndex = nextIndex
                } else {
                    Toast.makeText(context, "Mất tín hiệu toàn bộ luồng kênh này!", Toast.LENGTH_LONG).show()
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
                                showOsd = true
                                true
                            } else false
                        }

                        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> {
                            if (!showChannelList && channels.isNotEmpty()) {
                                currentChannelIndex = if (currentChannelIndex - 1 < 0) channels.size - 1 else currentChannelIndex - 1
                                currentStreamIndex = 0
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
                                    showOsd = true
                                }
                                true
                            }
                        }

                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            if (!showChannelList) {
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
                    descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                }
            },
            modifier = Modifier.fillMaxSize()
        )

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
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.90f))
                        )
                    )
                    .padding(horizontal = 36.dp, vertical = 22.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RealtimeClockBadge()

                        Spacer(modifier = Modifier.width(16.dp))

                        Column {
                            Text(
                                text = "${currentChannelIndex + 1}. ${currentChannel?.name ?: "VTV"}",
                                color = Color.White,
                                fontSize = 21.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Phát trực tiếp chuẩn truyền hình số vệ tinh • Tỷ lệ 16:9",
                                color = Color.LightGray.copy(alpha = 0.9f),
                                fontSize = 13.sp
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF202020).copy(alpha = 0.8f), RoundedCornerShape(4.dp))
                                .border(1.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            val streamText = when (currentStreamIndex) {
                                0 -> "Luồng chính"
                                1 -> "Dự phòng 1"
                                else -> "Dự phòng 2"
                            }
                            Text(
                                text = streamText,
                                color = if (currentStreamIndex == 0) Color(0xFF00E676) else Color(0xFFFFD600),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }

                        Spacer(modifier = Modifier.width(10.dp))

                        Box(
                            modifier = Modifier
                                .background(Color(0xFFE50914), RoundedCornerShape(4.dp))
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = "1080p FHD",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        // BẢNG DANH SÁCH KÊNH XANH LÁ - TIÊU ĐỀ CÂN GIỮA
        AnimatedVisibility(
            visible = showChannelList,
            enter = fadeIn() + slideInHorizontally { -it / 2 },
            exit = fadeOut() + slideOutHorizontally { -it / 2 },
            modifier = Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .padding(start = 24.dp, top = 20.dp, bottom = 20.dp)
                .width(320.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(26.dp))
                    .background(Color.Black.copy(alpha = 0.72f))
                    .border(
                        width = 1.2.dp,
                        color = Color(0xFF00E676).copy(alpha = 0.35f),
                        shape = RoundedCornerShape(26.dp)
                    )
                    .padding(horizontal = 14.dp, vertical = 18.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // TIÊU ĐỀ ĐƯỢC CĂN CHÍNH GIỮA KHUNG
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(Color(0xFF00E676).copy(alpha = 0.18f))
                                .border(1.2.dp, Color(0xFF00E676).copy(alpha = 0.65f), RoundedCornerShape(50))
                                .padding(horizontal = 20.dp, vertical = 7.dp)
                        ) {
                            Text(
                                text = "DANH SÁCH KÊNH",
                                color = Color(0xFF00E676),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = 0.8.sp
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
            .background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(4.dp))
            .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(
            text = timeText,
            color = Color.White,
            fontSize = 16.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
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
        isFocused -> Modifier.border(2.dp, Color(0xFF00E676), RoundedCornerShape(18.dp))
        isPlaying -> Modifier.border(1.2.dp, Color(0xFF00E676).copy(alpha = 0.45f), RoundedCornerShape(18.dp))
        else -> Modifier
    }

    var itemModifier = Modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(18.dp))
        .background(
            if (isFocused) Color(0xFF00E676).copy(alpha = 0.15f)
            else if (isPlaying) Color.Black.copy(alpha = 0.4f)
            else Color.Transparent
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
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .width(52.dp)
                    .height(30.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White)
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
                color = if (isFocused) Color(0xFF00E676) else if (isPlaying) Color(0xFF69F0AE) else Color(0xFFEEEEEE),
                fontSize = 15.sp,
                fontWeight = if (isFocused || isPlaying) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
        }
    }
}