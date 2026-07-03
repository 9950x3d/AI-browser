package com.example.ui

import android.graphics.Bitmap
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.Bookmark
import com.example.data.AutomationMacro
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

// Frosted Glass Aesthetic Palette
private val Slate950 = Color(0xFF020617) // Deep Slate base background
private val Slate900 = Color(0xFF0F172A) // Sleek Slate-900 for bars
private val GlassSurface = Color(0xCC1E293B) // Translucent Frosted Glass container (Slate 800 @ 80%)
private val GlassBorder = Color(0x2BFFFFFF) // High-contrast White/17% border for sharp glass edges
private val GlassIndigo = Color(0xFF6366F1) // Indigo-500 main brand color
private val GlassViolet = Color(0xFFA78BFA) // Violet-400 accent color
private val GlassEmerald = Color(0xFF10B981) // Emerald-500 success state
private val LightText = Color(0xFFF1F5F9) // Slate-100 high readability text
private val SubText = Color(0xFF94A3B8) // Slate-400 muted text


@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current

    // Browser State
    val currentUrl by viewModel.currentUrl.collectAsState()
    val pageTitle by viewModel.pageTitle.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val canGoBack by viewModel.canGoBack.collectAsState()
    val canGoForward by viewModel.canGoForward.collectAsState()

    // AI Chat State
    val chatMessages by viewModel.chatMessages.collectAsState()
    val isThinking by viewModel.isThinking.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()

    // DB lists
    val bookmarksList by viewModel.bookmarks.collectAsState()
    val macrosList by viewModel.macros.collectAsState()
    val historyList by viewModel.history.collectAsState()

    // Multi-Step Automation Agent States
    val isExecutingAutomation by viewModel.isExecutingAutomation.collectAsState()
    val automationProgress by viewModel.automationProgress.collectAsState()
    val currentObjective by viewModel.currentObjective.collectAsState()
    val currentActionStatus by viewModel.currentActionStatus.collectAsState()
    val pausedPrompt by viewModel.pausedPrompt.collectAsState()

    var urlInputText by remember { mutableStateOf(currentUrl) }
    var searchFocused by remember { mutableStateOf(false) }

    // Sync input text when page url changes externally
    LaunchedEffect(currentUrl) {
        urlInputText = currentUrl
    }

    // Bottom Panel State (AI Chat vs Bookmark vs Macros)
    var isPanelExpanded by remember { mutableStateOf(true) }
    var currentTab by remember { mutableStateOf(0) } // 0 = AI Assistant, 1 = Bookmarks/History, 2 = AI Macros

    // Macro Creation Dialogue State
    var showAddMacroDialog by remember { mutableStateOf(false) }
    var newMacroName by remember { mutableStateOf("") }
    var newMacroDesc by remember { mutableStateOf("") }
    var newMacroPrompt by remember { mutableStateOf("") }

    // Text-To-Speech State
    val tts = remember {
        var ttsInstance: android.speech.tts.TextToSpeech? = null
        ttsInstance = android.speech.tts.TextToSpeech(context) { status ->
            if (status == android.speech.tts.TextToSpeech.SUCCESS) {
                ttsInstance?.language = java.util.Locale.KOREAN
            }
        }
        ttsInstance
    }

    DisposableEffect(Unit) {
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    val speakText: (String) -> Unit = { text ->
        tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, null)
    }

    // Smart Find Dialog State
    var showFindDialog by remember { mutableStateOf(false) }
    var findQueryText by remember { mutableStateOf("") }

    // Reference to WebView to execute scripts manually if needed
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    // Helper to evaluate script directly
    fun runJs(script: String) {
        webViewRef?.evaluateJavascript(script, null)
    }

    // Helper to extract page metadata
    fun extractPageData(wv: WebView) {
        wv.evaluateJavascript("(function() { return document.body.innerText; })()") { text ->
            val cleanedText = text?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\n", "\n") ?: ""
            val extractElementsJs = """
                (function() {
                  var els = document.querySelectorAll('button, a, input, textarea, [role="button"], select');
                  var items = [];
                  for (var i = 0; i < els.length; i++) {
                    var el = els[i];
                    var rect = el.getBoundingClientRect();
                    var isVisible = rect.width > 0 && rect.height > 0;
                    if (isVisible) {
                      items.push({
                        index: i,
                        tag: el.tagName.toLowerCase(),
                        text: (el.textContent || el.value || el.placeholder || '').trim().substring(0, 40),
                        id: el.id || '',
                        placeholder: el.placeholder || '',
                        type: el.type || ''
                      });
                    }
                  }
                  return JSON.stringify(items.slice(0, 40));
                })()
            """.trimIndent()

            wv.evaluateJavascript(extractElementsJs) { elements ->
                val cleanElements = elements?.trim()?.removePrefix("\"")?.removeSuffix("\"")?.replace("\\\"", "\"") ?: "[]"
                viewModel.updatePageData(cleanElements, cleanedText)
            }
        }
    }

    // Handle Script execution events emitted by the ViewModel
    LaunchedEffect(key1 = Unit) {
        viewModel.scriptEvent.collectLatest { script ->
            webViewRef?.let { wv ->
                if (script == "fetchElements") {
                    extractPageData(wv)
                } else if (script.startsWith("highlight:")) {
                    val index = script.substringAfter("highlight:").toIntOrNull() ?: -1
                    if (index != -1) {
                        val highlightJs = """
                            (function() {
                              var els = document.querySelectorAll('button, a, input, textarea, [role="button"], select');
                              var el = els[$index];
                              if (el) {
                                el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                                var origOutline = el.style.outline;
                                var origTransition = el.style.transition;
                                el.style.transition = 'outline 0.15s ease-in-out';
                                el.style.outline = '5px solid #FF9F1C';
                                el.style.outlineOffset = '2px';
                                setTimeout(function() {
                                  el.style.outline = origOutline;
                                  el.style.transition = origTransition;
                                }, 1200);
                              }
                            })()
                        """.trimIndent()
                        wv.evaluateJavascript(highlightJs, null)
                    }
                } else {
                    wv.evaluateJavascript(script, null)
                }
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Slate950
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // --- TOP ADRESS BAR & BROWSER CONTROLS ---
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Slate900)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Back Arrow
                IconButton(
                    onClick = { webViewRef?.goBack() },
                    enabled = canGoBack,
                    modifier = Modifier.size(36.dp).testTag("nav_back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Go Back",
                        tint = if (canGoBack) GlassIndigo else GlassBorder
                    )
                }

                // Forward Arrow
                IconButton(
                    onClick = { webViewRef?.goForward() },
                    enabled = canGoForward,
                    modifier = Modifier.size(36.dp).testTag("nav_forward")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "Go Forward",
                        tint = if (canGoForward) GlassIndigo else GlassBorder
                    )
                }

                // Refresh/Loading icon
                IconButton(
                    onClick = { webViewRef?.reload() },
                    modifier = Modifier.size(36.dp)
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = GlassIndigo,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Reload",
                            tint = GlassIndigo
                        )
                    }
                }

                // URL Address input field
                TextField(
                    value = urlInputText,
                    onValueChange = { urlInputText = it },
                    placeholder = { Text("URL 주소 또는 검색어 입력", color = SubText, fontSize = 13.sp) },
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                        .padding(horizontal = 4.dp)
                        .testTag("url_input"),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Slate950,
                        unfocusedContainerColor = Slate950,
                        focusedTextColor = LightText,
                        unfocusedTextColor = LightText,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(22.dp),
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        viewModel.navigateTo(urlInputText)
                        keyboardController?.hide()
                    })
                )

                // Home button
                IconButton(
                    onClick = { viewModel.navigateTo("https://www.google.com") },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Home,
                        contentDescription = "Home",
                        tint = LightText
                    )
                }

                // Bookmark toggle button
                val isBookmarked = bookmarksList.any { it.url == currentUrl }
                IconButton(
                    onClick = { viewModel.toggleBookmark() },
                    modifier = Modifier.size(36.dp).testTag("toggle_bookmark")
                ) {
                    Icon(
                        imageVector = if (isBookmarked) Icons.Default.Star else Icons.Default.StarBorder,
                        contentDescription = "Bookmark Page",
                        tint = if (isBookmarked) GlassIndigo else LightText
                    )
                }
            }

            // Divider separating Address bar from Browser body
            HorizontalDivider(color = GlassBorder, thickness = 1.dp)

            // --- MAIN BODY SPLIT-SCREEN LAYOUT ---
            // WebView at the top (resizable/flexible) and AI Agent drawer at the bottom
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                // WEBVIEW SECTION (Occupies upper half, scalable)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(if (isPanelExpanded) 1.2f else 4f)
                        .background(Color.White)
                ) {
                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                settings.apply {
                                    javaScriptEnabled = true
                                    domStorageEnabled = true
                                    databaseEnabled = true
                                    useWideViewPort = true
                                    loadWithOverviewMode = true
                                    builtInZoomControls = true
                                    displayZoomControls = false
                                    javaScriptCanOpenWindowsAutomatically = true
                                    userAgentString = "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/100.0.0.0 Mobile Safari/537.36"
                                }
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                        super.onPageStarted(view, url, favicon)
                                        viewModel.setLoading(true)
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        viewModel.setLoading(false)
                                        val cTitle = view?.title ?: ""
                                        viewModel.setWebStates(
                                            url = url ?: "",
                                            canBack = view?.canGoBack() ?: false,
                                            canForward = view?.canGoForward() ?: false,
                                            title = cTitle
                                        )
                                        // Auto update web agent context on finish
                                        extractPageData(this@apply)
                                    }
                                }
                                loadUrl(currentUrl)
                                webViewRef = this
                            }
                        },
                        update = { wv ->
                            // When currentUrl changes in viewmodel, load it only if it's different
                            if (wv.url != currentUrl) {
                                wv.loadUrl(currentUrl)
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Overlay loading progress bar
                    if (isLoading) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .align(Alignment.TopCenter),
                            color = GlassIndigo,
                            trackColor = Color.Transparent
                        )
                    }
                }

                // AI AGENT CONTROL COLLAPSE INDICATOR / BAR
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate900)
                        .clickable { isPanelExpanded = !isPanelExpanded }
                        .padding(vertical = 6.dp, horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = "AI Agent Icon",
                            tint = GlassViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "AI 브라우저 아시스턴트",
                            color = LightText,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.SansSerif
                        )
                        if (isThinking) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "AI 분석중...",
                                color = GlassViolet,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Icon(
                        imageVector = if (isPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                        contentDescription = if (isPanelExpanded) "Collapse" else "Expand",
                        tint = SubText,
                        modifier = Modifier.size(24.dp)
                    )
                }

                HorizontalDivider(color = GlassBorder, thickness = 1.dp)

                // AI AGENT PANEL DRAWER (Occupies lower half when expanded)
                AnimatedVisibility(
                    visible = isPanelExpanded,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(2f),
                    enter = slideInVertically(initialOffsetY = { it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { it }) + fadeOut()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(GlassSurface)
                    ) {
                        // TAB NAVIGATION INSIDE DRAWER
                        TabRow(
                            selectedTabIndex = currentTab,
                            containerColor = Slate900,
                            contentColor = GlassIndigo,
                            indicator = { tabPositions ->
                                TabRowDefaults.SecondaryIndicator(
                                    modifier = Modifier.tabIndicatorOffset(tabPositions[currentTab]),
                                    color = GlassIndigo
                                )
                            },
                            modifier = Modifier.height(44.dp)
                        ) {
                            Tab(
                                selected = currentTab == 0,
                                onClick = { currentTab = 0 },
                                text = { Text("AI 채팅", fontSize = 11.sp, color = if (currentTab == 0) GlassIndigo else LightText) },
                                icon = { Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (currentTab == 0) GlassIndigo else LightText) }
                            )
                            Tab(
                                selected = currentTab == 1,
                                onClick = { currentTab = 1 },
                                text = { Text("매크로", fontSize = 11.sp, color = if (currentTab == 1) GlassIndigo else LightText) },
                                icon = { Icon(Icons.Default.Bolt, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (currentTab == 1) GlassIndigo else LightText) }
                            )
                            Tab(
                                selected = currentTab == 2,
                                onClick = { currentTab = 2 },
                                text = { Text("북마크", fontSize = 11.sp, color = if (currentTab == 2) GlassIndigo else LightText) },
                                icon = { Icon(Icons.Default.Bookmarks, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (currentTab == 2) GlassIndigo else LightText) }
                            )
                            Tab(
                                selected = currentTab == 3,
                                onClick = { currentTab = 3 },
                                text = { Text("AI 설정", fontSize = 11.sp, color = if (currentTab == 3) GlassIndigo else LightText) },
                                icon = { Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(15.dp), tint = if (currentTab == 3) GlassIndigo else LightText) }
                            )
                        }

                        // TAB CONTENTS
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            when (currentTab) {
                                0 -> ChatAssistantTab(
                                    messages = chatMessages,
                                    isThinking = isThinking,
                                    isExecutingAutomation = isExecutingAutomation,
                                    automationProgress = automationProgress,
                                    currentObjective = currentObjective,
                                    currentActionStatus = currentActionStatus,
                                    pausedPrompt = pausedPrompt,
                                    onSendMessage = { text -> viewModel.sendUserMessage(text) },
                                    onClearChat = { viewModel.clearChat() },
                                    onStopAutomation = { viewModel.stopAutomation() },
                                    onResumeAutomation = { viewModel.resumeAutomation() },
                                    onTranslate = { viewModel.translateCurrentPage() },
                                    onSummarize = { viewModel.summarizeCurrentPage() },
                                    onAutoFill = { viewModel.autoFillForm() },
                                    onSpeakText = speakText
                                )
                                1 -> MacrosTab(
                                    macros = macrosList,
                                    onRunMacro = { macro ->
                                        // Type into chat assistant directly
                                        viewModel.appendUserMessage(macro.instruction)
                                        viewModel.sendUserMessage(macro.instruction)
                                    },
                                    onAddMacroClick = { showAddMacroDialog = true },
                                    onDeleteMacro = { macro -> viewModel.deleteMacro(macro) }
                                )
                                2 -> BookmarksAndHistoryTab(
                                    bookmarks = bookmarksList,
                                    history = historyList,
                                    onNavigate = { url -> viewModel.navigateTo(url) },
                                    onDeleteBookmark = { bookmark -> viewModel.deleteBookmark(bookmark) },
                                    onClearHistory = { viewModel.clearHistory() }
                                )
                                3 -> SettingsTab(
                                    selectedModel = selectedModel,
                                    onModelSelected = { model -> viewModel.updateSelectedModel(model) },
                                    customApiKey = customApiKey,
                                    onCustomApiKeyChanged = { apiKey -> viewModel.updateCustomApiKey(apiKey) },
                                    bookmarksCount = bookmarksList.size,
                                    macrosCount = macrosList.size,
                                    historyCount = historyList.size,
                                    onClearChat = { viewModel.clearChat() }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Add Custom Automation Macro Dialog
        if (showAddMacroDialog) {
            AlertDialog(
                onDismissRequest = { showAddMacroDialog = false },
                title = { Text("새 자동화 매크로 만들기", color = LightText, fontWeight = FontWeight.Bold, fontSize = 18.sp) },
                text = {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = newMacroName,
                            onValueChange = { newMacroName = it },
                            label = { Text("매크로 이름", color = SubText) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LightText,
                                unfocusedTextColor = LightText,
                                focusedBorderColor = GlassIndigo,
                                unfocusedBorderColor = GlassBorder
                            ),
                            modifier = Modifier.fillMaxWidth().testTag("macro_name_input")
                        )

                        OutlinedTextField(
                            value = newMacroDesc,
                            onValueChange = { newMacroDesc = it },
                            label = { Text("설명", color = SubText) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LightText,
                                unfocusedTextColor = LightText,
                                focusedBorderColor = GlassIndigo,
                                unfocusedBorderColor = GlassBorder
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )

                        OutlinedTextField(
                            value = newMacroPrompt,
                            onValueChange = { newMacroPrompt = it },
                            label = { Text("자동 조작 프롬프트", color = SubText) },
                            placeholder = { Text("예: 이 페이지의 가격 정보를 추출해 줘") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedTextColor = LightText,
                                unfocusedTextColor = LightText,
                                focusedBorderColor = GlassIndigo,
                                unfocusedBorderColor = GlassBorder
                            ),
                            modifier = Modifier.fillMaxWidth().height(100.dp).testTag("macro_prompt_input")
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (newMacroName.isNotBlank() && newMacroPrompt.isNotBlank()) {
                                viewModel.addMacro(newMacroName, newMacroDesc, newMacroPrompt)
                                newMacroName = ""
                                newMacroDesc = ""
                                newMacroPrompt = ""
                                showAddMacroDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GlassIndigo, contentColor = Slate950),
                        modifier = Modifier.testTag("save_macro_button")
                    ) {
                        Text("매크로 저장")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddMacroDialog = false }) {
                        Text("취소", color = SubText)
                    }
                },
                containerColor = Slate900,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

// --- SUB-VIEWS / TAB CONTENTS ---

@Composable
fun ChatAssistantTab(
    messages: List<ChatMessage>,
    isThinking: Boolean,
    isExecutingAutomation: Boolean,
    automationProgress: Float,
    currentObjective: String,
    currentActionStatus: String,
    pausedPrompt: String?,
    onSendMessage: (String) -> Unit,
    onClearChat: () -> Unit,
    onStopAutomation: () -> Unit,
    onResumeAutomation: () -> Unit,
    onTranslate: () -> Unit,
    onSummarize: () -> Unit,
    onAutoFill: () -> Unit,
    onSpeakText: (String) -> Unit = {}
) {
    var messageInputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    var showFindDialog by remember { mutableStateOf(false) }
    var findQueryText by remember { mutableStateOf("") }

    // Auto-scroll to latest message
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // --- 1. Multi-Step Automation Active Status & Control Card ---
        if (isExecutingAutomation || pausedPrompt != null) {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.9f)),
                border = BorderStroke(1.dp, GlassBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = if (isExecutingAutomation) Icons.Default.AutoAwesome else Icons.Default.Pause,
                                contentDescription = null,
                                tint = if (isExecutingAutomation) GlassIndigo else GlassViolet,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isExecutingAutomation) "AI 에이전트 실행 중..." else "AI 에이전트 일시정지됨",
                                color = LightText,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Stop/Resume buttons
                        if (isExecutingAutomation) {
                            Button(
                                onClick = onStopAutomation,
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE63946)),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(12.dp), tint = Color.White)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("일시정지", fontSize = 10.sp, color = Color.White)
                            }
                        } else {
                            Button(
                                onClick = onResumeAutomation,
                                colors = ButtonDefaults.buttonColors(containerColor = GlassIndigo),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(12.dp), tint = Slate950)
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("이어하기", fontSize = 10.sp, color = Slate950)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "목표: \"$currentObjective\"",
                        color = LightText,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "상태: $currentActionStatus",
                        color = SubText,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { automationProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp),
                        color = GlassIndigo,
                        trackColor = Slate950,
                        strokeCap = androidx.compose.ui.graphics.StrokeCap.Round
                    )
                }
            }
        }

        // --- 2. Smart AI Quick Action Buttons Panel ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Translate Page Button
            AssistChip(
                onClick = onTranslate,
                label = { Text("페이지 번역", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(12.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = LightText,
                    leadingIconContentColor = GlassIndigo,
                    containerColor = Slate900
                ),
                border = BorderStroke(1.dp, GlassBorder)
            )

            // Summarize Page Button
            AssistChip(
                onClick = onSummarize,
                label = { Text("페이지 요약", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(12.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = LightText,
                    leadingIconContentColor = GlassViolet,
                    containerColor = Slate900
                ),
                border = BorderStroke(1.dp, GlassBorder)
            )

            // Auto-Fill Form Button
            AssistChip(
                onClick = onAutoFill,
                label = { Text("폼 자동 완성", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Assignment, contentDescription = null, modifier = Modifier.size(12.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = LightText,
                    leadingIconContentColor = GlassIndigo,
                    containerColor = Slate900
                ),
                border = BorderStroke(1.dp, GlassBorder)
            )

            // Smart Find on Page Button
            AssistChip(
                onClick = { showFindDialog = true },
                label = { Text("페이지 스마트 검색", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(12.dp)) },
                colors = AssistChipDefaults.assistChipColors(
                    labelColor = LightText,
                    leadingIconContentColor = GlassViolet,
                    containerColor = Slate900
                ),
                border = BorderStroke(1.dp, GlassBorder)
            )
        }

        // Chat list
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages) { message ->
                Box(
                    modifier = Modifier.fillMaxWidth(),
                    contentAlignment = when (message.sender) {
                        "user" -> Alignment.CenterEnd
                        "system" -> Alignment.Center
                        else -> Alignment.CenterStart
                    }
                ) {
                    if (message.sender == "system") {
                        // Custom styling for system actions/alerts
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Slate900),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GlassBorder),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = GlassIndigo,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = message.text,
                                    color = SubText,
                                    fontSize = 11.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                    } else {
                        // User or AI bubble
                        Column(
                            modifier = Modifier.widthIn(max = 280.dp),
                            horizontalAlignment = if (message.sender == "user") Alignment.End else Alignment.Start
                        ) {
                            // Avatar header
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(bottom = 2.dp)
                            ) {
                                Icon(
                                    imageVector = if (message.sender == "user") Icons.Default.Person else Icons.Default.SmartToy,
                                    contentDescription = null,
                                    tint = if (message.sender == "user") GlassIndigo else GlassViolet,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (message.sender == "user") "나" else "AI 브라우저",
                                    color = SubText,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                if (message.sender != "user") {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = "Read Aloud",
                                        tint = GlassViolet,
                                        modifier = Modifier
                                            .size(14.dp)
                                            .clickable { onSpeakText(message.text) }
                                    )
                                }
                            }

                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = if (message.sender == "user") GlassIndigo.copy(alpha = 0.15f) else Slate900.copy(alpha = 0.8f)
                                ),
                                shape = RoundedCornerShape(16.dp),
                                border = BorderStroke(1.dp, if (message.sender == "user") GlassIndigo.copy(alpha = 0.4f) else GlassBorder)
                            ) {
                                Text(
                                    text = message.text,
                                    color = LightText,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(10.dp),
                                    lineHeight = 18.sp
                                )
                            }
                        }
                    }
                }
            }

            if (isThinking) {
                item {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = GlassIndigo,
                            strokeWidth = 1.5.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("AI가 생각하고 지시를 수행하고 있습니다...", color = SubText, fontSize = 11.sp)
                    }
                }
            }
        }

        // Bottom Chat Input panel
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Clear chat history button
            IconButton(
                onClick = onClearChat,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteSweep,
                    contentDescription = "Clear Chat",
                    tint = SubText
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            OutlinedTextField(
                value = messageInputText,
                onValueChange = { messageInputText = it },
                placeholder = { Text("자동 조작 명령어 예: '아래로 스크롤 해줘'", color = SubText, fontSize = 12.sp) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("chat_input"),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = LightText,
                    unfocusedTextColor = LightText,
                    focusedBorderColor = GlassIndigo,
                    unfocusedBorderColor = GlassBorder,
                    focusedContainerColor = Slate900,
                    unfocusedContainerColor = Slate900
                ),
                shape = RoundedCornerShape(24.dp),
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (messageInputText.isNotBlank()) {
                        onSendMessage(messageInputText)
                        messageInputText = ""
                    }
                })
            )

            Spacer(modifier = Modifier.width(6.dp))

            IconButton(
                onClick = {
                    if (messageInputText.isNotBlank()) {
                        onSendMessage(messageInputText)
                        messageInputText = ""
                    }
                },
                modifier = Modifier
                    .size(40.dp)
                    .background(GlassIndigo, shape = CircleShape)
                    .testTag("send_chat_button")
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = Slate950,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        if (showFindDialog) {
            AlertDialog(
                onDismissRequest = { showFindDialog = false },
                title = { Text("AI 스마트 본문 검색", color = LightText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
                text = {
                    Column {
                        Text(
                            "현재 웹페이지 본문에서 인공지능이 정보를 탐색해 스크롤하고 강조 표시해 줍니다.",
                            color = SubText,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )
                        OutlinedTextField(
                            value = findQueryText,
                            onValueChange = { findQueryText = it },
                            placeholder = { Text("예: 이메일 주소, 가격 정보, 연락처 등", fontSize = 12.sp) },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = GlassIndigo,
                                unfocusedBorderColor = GlassBorder,
                                focusedTextColor = LightText,
                                unfocusedTextColor = LightText
                            ),
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            if (findQueryText.isNotBlank()) {
                                onSendMessage("현재 페이지에서 \"${findQueryText}\" 정보 찾아줘")
                                findQueryText = ""
                                showFindDialog = false
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = GlassIndigo, contentColor = Slate950)
                    ) {
                        Text("검색")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showFindDialog = false }) {
                        Text("취소", color = SubText)
                    }
                },
                containerColor = Slate900,
                shape = RoundedCornerShape(24.dp)
            )
        }
    }
}

@Composable
fun MacrosTab(
    macros: List<AutomationMacro>,
    onRunMacro: (AutomationMacro) -> Unit,
    onAddMacroClick: () -> Unit,
    onDeleteMacro: (AutomationMacro) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "자주 쓰는 자동화 명령 매크로",
                color = LightText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = onAddMacroClick,
                colors = ButtonDefaults.buttonColors(containerColor = GlassIndigo, contentColor = Slate950),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.height(30.dp).testTag("add_macro_tab_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("추가", fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (macros.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "저장된 매크로가 없습니다.\n나만의 맞춤 자동 조작 프롬프트를 추가해 보세요!",
                    color = SubText,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(macros) { macro ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.8f)),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, GlassBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = macro.name,
                                    color = LightText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = macro.description,
                                    color = SubText,
                                    fontSize = 11.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Card(
                                    colors = CardDefaults.cardColors(containerColor = Slate950),
                                    shape = RoundedCornerShape(6.dp),
                                    border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.5f))
                                ) {
                                    Text(
                                        text = macro.instruction,
                                        color = GlassViolet,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(8.dp))

                            // Play macro action
                            IconButton(
                                onClick = { onRunMacro(macro) },
                                modifier = Modifier
                                    .size(36.dp)
                                    .background(GlassIndigo.copy(alpha = 0.15f), shape = CircleShape).testTag("run_macro_${macro.id}")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PlayArrow,
                                    contentDescription = "Run Macro",
                                    tint = GlassIndigo,
                                    modifier = Modifier.size(18.dp)
                                )
                            }

                            Spacer(modifier = Modifier.width(4.dp))

                            // Delete macro
                            IconButton(
                                onClick = { onDeleteMacro(macro) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = "Delete",
                                    tint = SubText,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookmarksAndHistoryTab(
    bookmarks: List<Bookmark>,
    history: List<com.example.data.HistoryItem>,
    onNavigate: (String) -> Unit,
    onDeleteBookmark: (Bookmark) -> Unit,
    onClearHistory: () -> Unit
) {
    var subTabState by remember { mutableStateOf(0) } // 0 = Bookmarks, 1 = History

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp)
    ) {
        // Bookmarks / History toggle bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            FilledTonalButton(
                onClick = { subTabState = 0 },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (subTabState == 0) GlassIndigo.copy(alpha = 0.2f) else Slate900
                ),
                shape = RoundedCornerShape(12.dp, 0.dp, 0.dp, 12.dp),
                modifier = Modifier.weight(1f).height(32.dp).testTag("subtab_bookmarks")
            ) {
                Text(
                    "북마크 (${bookmarks.size})",
                    color = if (subTabState == 0) GlassIndigo else LightText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            FilledTonalButton(
                onClick = { subTabState = 1 },
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = if (subTabState == 1) GlassIndigo.copy(alpha = 0.2f) else Slate900
                ),
                shape = RoundedCornerShape(0.dp, 12.dp, 12.dp, 0.dp),
                modifier = Modifier.weight(1f).height(32.dp).testTag("subtab_history")
            ) {
                Text(
                    "히스토리",
                    color = if (subTabState == 1) GlassIndigo else LightText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        if (subTabState == 0) {
            // BOOKMARKS LIST
            if (bookmarks.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("저장된 북마크가 없습니다.\n주소창 옆 별표(★)를 눌러 등록해 보세요!", color = SubText, fontSize = 12.sp, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(bookmarks) { bookmark ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(bookmark.url) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Star,
                                    contentDescription = null,
                                    tint = GlassIndigo,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.title,
                                        color = LightText,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = bookmark.url,
                                        color = SubText,
                                        fontSize = 10.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                IconButton(
                                    onClick = { onDeleteBookmark(bookmark) },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "Remove",
                                        tint = SubText,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        } else {
            // HISTORY ITEM LIST
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                horizontalArrangement = Arrangement.End
            ) {
                Text(
                    text = "기록 지우기",
                    color = GlassIndigo,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clickable { onClearHistory() }
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                )
            }

            if (history.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("방문 기록이 비어있습니다.", color = SubText, fontSize = 12.sp)
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(history) { item ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.8f)),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.4f)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onNavigate(item.url) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.History,
                                    contentDescription = null,
                                    tint = SubText,
                                    modifier = Modifier.size(14.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = item.title,
                                        color = LightText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = item.url,
                                        color = SubText,
                                        fontSize = 9.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsTab(
    selectedModel: String,
    onModelSelected: (String) -> Unit,
    customApiKey: String,
    onCustomApiKeyChanged: (String) -> Unit,
    bookmarksCount: Int,
    macrosCount: Int,
    historyCount: Int,
    onClearChat: () -> Unit
) {
    var showResetDialog by remember { mutableStateOf(false) }
    var apiKeyText by remember { mutableStateOf(customApiKey) }
    var isPasswordVisible by remember { mutableStateOf(false) }

    LaunchedEffect(customApiKey) {
        apiKeyText = customApiKey
    }

    val models = listOf(
        Triple("gemini-2.5-flash", "Gemini 2.5 Flash", "속도 및 한국어 요약 최적화 (권장 최신 모델)"),
        Triple("gemini-2.5-pro", "Gemini 2.5 Pro", "정밀 분석, 고난도 추론 및 복잡한 매크로 작업용"),
        Triple("gemini-1.5-flash", "Gemini 1.5 Flash", "가볍고 안정적인 기본 속도형 모델"),
        Triple("gemini-1.5-pro", "Gemini 1.5 Pro", "안정성이 뛰어난 고급 추론용 전문가 모델"),
        Triple("gemini-3.5-flash", "Gemini 3.5 Flash", "고속 추론 실험적 모델")
    )

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // AI Model Section Title
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = GlassViolet,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "AI 모델 인텔리전스 설정",
                    color = LightText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Model List Cards
        items(models) { (modelId, name, desc) ->
            val isSelected = selectedModel == modelId
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSelected) GlassIndigo.copy(alpha = 0.15f) else Slate900.copy(alpha = 0.6f)
                ),
                shape = RoundedCornerShape(14.dp),
                border = BorderStroke(
                    1.dp,
                    if (isSelected) GlassIndigo else GlassBorder.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onModelSelected(modelId) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = { onModelSelected(modelId) },
                        colors = RadioButtonDefaults.colors(
                            selectedColor = GlassIndigo,
                            unselectedColor = SubText
                        )
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = name,
                                color = if (isSelected) GlassViolet else LightText,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                            if (isSelected) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Card(
                                    colors = CardColors(
                                        containerColor = GlassIndigo,
                                        contentColor = Slate950,
                                        disabledContainerColor = GlassIndigo,
                                        disabledContentColor = Slate950
                                    ),
                                    shape = RoundedCornerShape(4.dp),
                                    modifier = Modifier.padding(vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "ACTIVE",
                                        color = Slate950,
                                        fontSize = 8.sp,
                                        fontWeight = FontWeight.Black,
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = desc,
                            color = SubText,
                            fontSize = 11.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
            }
        }

        // Custom API Key Card Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Info,
                            contentDescription = null,
                            tint = GlassViolet,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "커스텀 Gemini API 키 설정",
                            color = LightText,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Text(
                        text = "AI Studio Secrets에 설정된 기본 API 키 외에, 본인 소유의 특정 Gemini API 키를 사용하여 브라우저 AI 기능을 사용하려면 아래에 입력하세요. 입력하지 않거나 비워둘 경우 시스템 기본 API 키가 사용됩니다.",
                        color = SubText,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )

                    OutlinedTextField(
                        value = apiKeyText,
                        onValueChange = { apiKeyText = it },
                        label = { Text("Gemini API Key", color = SubText, fontSize = 11.sp) },
                        singleLine = true,
                        visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            TextButton(
                                onClick = { isPasswordVisible = !isPasswordVisible }
                            ) {
                                Text(
                                    text = if (isPasswordVisible) "숨기기" else "보기",
                                    color = GlassIndigo,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = LightText,
                            unfocusedTextColor = LightText,
                            focusedBorderColor = GlassIndigo,
                            unfocusedBorderColor = GlassBorder.copy(alpha = 0.5f)
                        ),
                        modifier = Modifier.fillMaxWidth().testTag("custom_api_key_input")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (customApiKey.isNotEmpty()) {
                            TextButton(
                                onClick = {
                                    onCustomApiKeyChanged("")
                                    apiKeyText = ""
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                            ) {
                                Text("삭제", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }

                        Button(
                            onClick = {
                                onCustomApiKeyChanged(apiKeyText)
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = GlassIndigo,
                                contentColor = Slate950
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("저장 및 적용", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Stats Section Title
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    tint = GlassViolet,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "데이터 및 리소스 통계",
                    color = LightText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Stats Grid Card
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Bookmarks, contentDescription = null, tint = GlassViolet, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("북마크", color = SubText, fontSize = 11.sp)
                        Text("$bookmarksCount 개", color = LightText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.width(1.dp).height(40.dp).background(GlassBorder))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Bolt, contentDescription = null, tint = GlassViolet, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("매크로", color = SubText, fontSize = 11.sp)
                        Text("$macrosCount 개", color = LightText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(modifier = Modifier.width(1.dp).height(40.dp).background(GlassBorder))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.History, contentDescription = null, tint = GlassViolet, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.height(6.dp))
                        Text("방문기록", color = SubText, fontSize = 11.sp)
                        Text("$historyCount 개", color = LightText, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // Actions & System Status
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.6f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.3f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // API Status Row
                    val isApiKeyConfigured = com.example.BuildConfig.GEMINI_API_KEY.isNotEmpty() && com.example.BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY"
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = SubText, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Gemini API 키 연결", color = LightText, fontSize = 12.sp)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(
                                        color = if (isApiKeyConfigured) GlassEmerald else Color.Red,
                                        shape = CircleShape
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isApiKeyConfigured) "활성화됨" else "미설정 (Secret)",
                                color = if (isApiKeyConfigured) GlassEmerald else Color.Red,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    HorizontalDivider(color = GlassBorder.copy(alpha = 0.3f), thickness = 1.dp)

                    // Clear Chat action
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = SubText, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("채팅 대화방 초기화", color = LightText, fontSize = 12.sp)
                        }
                        TextButton(
                            onClick = { showResetDialog = true },
                            colors = ButtonDefaults.textButtonColors(contentColor = Color.Red),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("채팅 비우기", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Info Section
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.3f)),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, GlassBorder.copy(alpha = 0.2f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "💡 AI 스마트 웹 브라우저 가이드",
                        color = GlassViolet,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "• AI가 현재 페이지의 본문을 이해하고, 특정 단어를 찾아 화면을 스크롤하고 강조할 수 있습니다.\n" +
                        "• '자동화 매크로'를 만들어 반복되는 질문이나 작업을 클릭 한 번으로 실행할 수 있습니다.\n" +
                        "• 복잡한 형태의 폼(Form)이 보이면 '자동완성'을 실행하여 정보를 미리 채울 수 있습니다.\n" +
                        "• 우측 상단 메인 대화창에서 자유롭게 웹 조작 자동화(클릭, 스크롤 등)를 명령해 보세요.",
                        color = SubText,
                        fontSize = 11.sp,
                        lineHeight = 15.sp
                    )
                }
            }
        }
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("대화 초기화", color = LightText, fontWeight = FontWeight.Bold, fontSize = 16.sp) },
            text = { Text("AI 어시스턴트와의 모든 채팅 기록을 영구적으로 삭제하고 초기 상태로 되돌리시겠습니까?", color = SubText, fontSize = 13.sp) },
            confirmButton = {
                Button(
                    onClick = {
                        onClearChat()
                        showResetDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Red, contentColor = LightText)
                ) {
                    Text("초기화 실행")
                }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) {
                    Text("취소", color = SubText)
                }
            },
            containerColor = Slate900,
            shape = RoundedCornerShape(20.dp)
        )
    }
}
