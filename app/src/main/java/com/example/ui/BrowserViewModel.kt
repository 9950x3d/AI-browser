package com.example.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class ChatMessage(
    val sender: String, // "user" | "assistant" | "system"
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val db = BrowserDatabase.getDatabase(application)
    private val bookmarkDao = db.bookmarkDao()
    private val macroDao = db.macroDao()
    private val historyDao = db.historyDao()

    // Model Selection State
    private val sharedPrefs = application.getSharedPreferences("browser_settings", android.content.Context.MODE_PRIVATE)
    private val _selectedModel = MutableStateFlow(
        sharedPrefs.getString("selected_model", "gemini-3.5-flash") ?: "gemini-3.5-flash"
    )
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    fun updateSelectedModel(model: String) {
        _selectedModel.value = model
        sharedPrefs.edit().putString("selected_model", model).apply()
        appendSystemMessage("🤖 AI 모델이 **$model**로 변경되었습니다.")
    }

    // Custom API Key State
    private val _customApiKey = MutableStateFlow(
        sharedPrefs.getString("custom_api_key", "") ?: ""
    )
    val customApiKey: StateFlow<String> = _customApiKey.asStateFlow()

    fun updateCustomApiKey(apiKey: String) {
        _customApiKey.value = apiKey.trim()
        sharedPrefs.edit().putString("custom_api_key", apiKey.trim()).apply()
        if (apiKey.isBlank()) {
            appendSystemMessage("🔑 커스텀 API 키가 비활성화되었습니다. 시스템 기본 API 키를 사용합니다.")
        } else {
            val masked = if (apiKey.length > 8) "${apiKey.take(4)}...${apiKey.takeLast(4)}" else "****"
            appendSystemMessage("🔑 커스텀 API 키가 등록되었습니다 ($masked).")
        }
    }

    private suspend fun generateAIResponse(
        prompt: String,
        systemInstruction: String? = null,
        responseJson: Boolean = false
    ): String {
        return RetrofitClient.generateResponse(
            prompt = prompt,
            systemInstruction = systemInstruction,
            responseJson = responseJson,
            model = _selectedModel.value,
            customApiKey = _customApiKey.value
        )
    }

    // UI States
    val bookmarks = bookmarkDao.getAllBookmarks().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val macros = macroDao.getAllMacros().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val history = historyDao.getRecentHistory().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentUrl = MutableStateFlow("https://www.google.com")
    val currentUrl: StateFlow<String> = _currentUrl.asStateFlow()

    private val _canGoBack = MutableStateFlow(false)
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    private val _canGoForward = MutableStateFlow(false)
    val canGoForward: StateFlow<Boolean> = _canGoForward.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _pageTitle = MutableStateFlow("")
    val pageTitle: StateFlow<String> = _pageTitle.asStateFlow()

    // AI Chat State
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
        listOf(
            ChatMessage(
                "assistant",
                "안녕하세요! 저는 웹 브라우저 AI 아시스턴트입니다.\n현재 열려있는 웹 페이지를 요약하거나, 필요한 정보를 추출하고, 직접 클릭/스크롤/검색을 자동화할 수 있습니다.\n\n💡 무엇을 도와드릴까요?\n- \"이 페이지 요약해줘\"\n- \"검색창에 Kotlin 검색하고 클릭해줘\"\n- \"이 페이지에 있는 모든 이메일 주소 추출해줘\""
            )
        )
    )
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _isThinking = MutableStateFlow(false)
    val isThinking: StateFlow<Boolean> = _isThinking.asStateFlow()

    // Multi-Step Automation Agent States
    private val _isExecutingAutomation = MutableStateFlow(false)
    val isExecutingAutomation: StateFlow<Boolean> = _isExecutingAutomation.asStateFlow()

    private val _automationProgress = MutableStateFlow(0f)
    val automationProgress: StateFlow<Float> = _automationProgress.asStateFlow()

    private val _currentObjective = MutableStateFlow("")
    val currentObjective: StateFlow<String> = _currentObjective.asStateFlow()

    private val _currentActionStatus = MutableStateFlow("")
    val currentActionStatus: StateFlow<String> = _currentActionStatus.asStateFlow()

    private val _pausedPrompt = MutableStateFlow<String?>(null)
    val pausedPrompt: StateFlow<String?> = _pausedPrompt.asStateFlow()

    // JS Communication Events
    private val _scriptEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val scriptEvent = _scriptEvent.asSharedFlow()

    // Track state of page elements extracted from WebView
    private var pageElementsJson: String = "[]"
    private var pageText: String = ""

    init {
        // Pre-populate some useful macros on first run
        viewModelScope.launch {
            macros.collectLatest { list ->
                if (list.isEmpty()) {
                    macroDao.insertMacro(
                        AutomationMacro(
                            name = "뉴스 요약하기",
                            description = "현재 뉴스 기사 본문을 파악하여 핵심 내용만 3문장으로 요약합니다.",
                            instruction = "이 뉴스 페이지의 주요 기사를 읽고 한글로 3문장 요약해줘."
                        )
                    )
                    macroDao.insertMacro(
                        AutomationMacro(
                            name = "이메일 추출기",
                            description = "페이지 안의 이메일 연락처 패턴을 찾아 정리해 줍니다.",
                            instruction = "이 페이지에 나타난 모든 이메일 주소나 연락처를 찾아 리스트로 가독성 있게 추출해줘."
                        )
                    )
                    macroDao.insertMacro(
                        AutomationMacro(
                            name = "자동 스크롤 & 쇼핑 리스트",
                            description = "현재 열린 페이지를 스크롤하면서 상품 정보나 주요 타이틀을 나열합니다.",
                            instruction = "이 페이지의 주요 제목이나 제품 목록을 깔끔하게 요약해줘."
                        )
                    )
                }
            }
        }
    }

    fun setWebStates(url: String, canBack: Boolean, canForward: Boolean, title: String) {
        _currentUrl.value = url
        _canGoBack.value = canBack
        _canGoForward.value = canForward
        _pageTitle.value = title

        // Save to history when url changes significantly
        if (url.isNotEmpty() && url.startsWith("http")) {
            viewModelScope.launch {
                historyDao.insertHistory(HistoryItem(title = title.ifEmpty { url }, url = url))
            }
        }
    }

    fun setLoading(loading: Boolean) {
        _isLoading.value = loading
    }

    fun navigateTo(url: String) {
        var cleanUrl = url.trim()
        if (cleanUrl.isNotEmpty()) {
            if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
                cleanUrl = if (cleanUrl.contains(".") && !cleanUrl.contains(" ")) {
                    "https://$cleanUrl"
                } else {
                    "https://www.google.com/search?q=${cleanUrl.replace(" ", "+")}"
                }
            }
            _currentUrl.value = cleanUrl
        }
    }

    // Bookmarks and Macros Actions
    fun toggleBookmark() {
        val url = _currentUrl.value
        val title = _pageTitle.value.ifEmpty { url }
        viewModelScope.launch {
            val isBookmarked = bookmarks.value.any { it.url == url }
            if (isBookmarked) {
                val bookmark = bookmarks.value.find { it.url == url }
                if (bookmark != null) bookmarkDao.deleteBookmark(bookmark)
            } else {
                bookmarkDao.insertBookmark(Bookmark(title = title, url = url))
            }
        }
    }

    fun deleteBookmark(bookmark: Bookmark) {
        viewModelScope.launch {
            bookmarkDao.deleteBookmark(bookmark)
        }
    }

    fun addMacro(name: String, description: String, instruction: String) {
        viewModelScope.launch {
            macroDao.insertMacro(AutomationMacro(name = name, description = description, instruction = instruction))
        }
    }

    fun deleteMacro(macro: AutomationMacro) {
        viewModelScope.launch {
            macroDao.deleteMacro(macro)
        }
    }

    fun clearHistory() {
        viewModelScope.launch {
            historyDao.clearHistory()
        }
    }

    // Set interactive element snapshots from WebView
    fun updatePageData(elementsJson: String, extractedText: String) {
        this.pageElementsJson = elementsJson
        this.pageText = extractedText
    }

    // Main AI Command Processing
    fun sendUserMessage(messageText: String) {
        if (messageText.isBlank()) return

        // Append user message
        val userMsg = ChatMessage("user", messageText)
        _chatMessages.value = _chatMessages.value + userMsg
        _isThinking.value = true

        viewModelScope.launch {
            try {
                // Determine user intent (is it page content summary/extraction, or is it browser action automation?)
                val classificationPrompt = """
                    Classify the user's intent based on this prompt: "$messageText"
                    
                    Choose ONE category:
                    - "NAVIGATE": User wants to visit a specific website, search Google, or go back/forward (e.g., "naver.com", "구글로 이동", "뒤로 가기", "앞으로 가기").
                    - "FIND_ON_PAGE": User wants to search, locate, find, or highlight specific text, keyword, or info on the CURRENT webpage (e.g., "이메일 찾아줘", "여기서 가격 정보 검색해줘", "코틀린 검색해줘").
                    - "SUMMARY": Wants to summarize, translate, read, or ask questions about the general text content of the current webpage.
                    - "EXTRACT": Wants to find links, emails, numbers, tables, lists, products, or metadata from the current page text.
                    - "AUTOMATE": Wants to click on buttons, fill forms/inputs, search inside inputs, or scroll on the page (e.g., "click search", "type Kotlin", "scroll down").
                    
                    Respond ONLY with the category name ("NAVIGATE", "FIND_ON_PAGE", "SUMMARY", "EXTRACT", or "AUTOMATE"). No markdown, no punctuation.
                """.trimIndent()

                val category = generateAIResponse(classificationPrompt).trim().uppercase()

                when {
                    category == "NAVIGATE" -> {
                        processNavigateRequest(messageText)
                    }
                    category == "FIND_ON_PAGE" -> {
                        processFindOnPageRequest(messageText)
                    }
                    category == "SUMMARY" || category == "EXTRACT" -> {
                        processContentRequest(messageText, category)
                    }
                    category == "AUTOMATE" -> {
                        processAutomationRequest(messageText)
                    }
                    else -> {
                        // Fallback: general QA with context
                        processContentRequest(messageText, "SUMMARY")
                    }
                }
            } catch (e: Exception) {
                appendSystemMessage("오류가 발생했습니다: ${e.localizedMessage ?: e.message}")
            } finally {
                _isThinking.value = false
            }
        }
    }

    private suspend fun processNavigateRequest(userPrompt: String) {
        appendSystemMessage("AI가 탐색 목적지를 분석 중입니다...")
        val systemInstruction = """
            You are a Web Browser Navigation assistant.
            The user wants to navigate somewhere or perform a navigation action (back, forward).
            Analyze the user prompt: "$userPrompt"
            
            Choose the action and destination URL.
            - If they want to search Google for something, create the Google search URL (e.g., "https://www.google.com/search?q=something").
            - If they typed a domain or website name, return its standard URL (e.g., "https://www.naver.com" or "https://github.com").
            - If they want to go back or forward, return "back" or "forward" in the action field.
            
            Return ONLY a JSON object:
            {
              "action": "navigate" | "back" | "forward",
              "url": "<destination URL or empty if back/forward>"
            }
        """.trimIndent()

        try {
            val response = generateAIResponse(
                prompt = "Determine the navigation action for: $userPrompt",
                systemInstruction = systemInstruction,
                responseJson = true
            )
            val cleanJson = response.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleanJson)
            val action = json.optString("action", "navigate")
            val url = json.optString("url", "")

            _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("탐색 목적지를 분석") }

            when (action) {
                "back" -> {
                    _scriptEvent.emit("window.history.back();")
                    appendAssistantMessage("뒤로 가기 동작을 수행했습니다.")
                }
                "forward" -> {
                    _scriptEvent.emit("window.history.forward();")
                    appendAssistantMessage("앞으로 가기 동작을 수행했습니다.")
                }
                "navigate" -> {
                    if (url.isNotEmpty()) {
                        navigateTo(url)
                        appendAssistantMessage("🔗 **$url** 사이트로 이동합니다.")
                    } else {
                        appendAssistantMessage("이동할 목적지 주소를 찾지 못했습니다.")
                    }
                }
            }
        } catch (e: Exception) {
            _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("탐색 목적지를 분석") }
            appendSystemMessage("탐색 오류: ${e.localizedMessage}")
        }
    }

    private suspend fun processFindOnPageRequest(userPrompt: String) {
        appendSystemMessage("AI가 페이지 내에서 정보를 찾는 중입니다...")
        
        val maxTextLen = 8000
        val safeText = if (pageText.length > maxTextLen) pageText.substring(0, maxTextLen) + "...(Truncated)" else pageText

        val systemInstruction = """
            You are a Page Search Assistant.
            Your task is to find the exact word, phrase, or short text snippet on the current webpage that answers or matches the user's request: "$userPrompt".
            
            Current page text:
            $safeText
            
            Choose the exact matching text snippet from the page. It should be a short, unique substring (1 to 5 words, maximum 30 characters) that actually exists on the page.
            
            Return ONLY a JSON object:
            {
              "searchText": "<exact text to highlight and find on the page>",
              "explanation": "<short sentence in Korean explaining what you found, e.g., '문의 이메일 주소 gildong@example.com을 찾았습니다.'"
            }
        """.trimIndent()

        try {
            val response = generateAIResponse(
                prompt = "Find on page for: $userPrompt",
                systemInstruction = systemInstruction,
                responseJson = true
            )
            val cleanJson = response.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(cleanJson)
            val searchText = json.optString("searchText", "")
            val explanation = json.optString("explanation", "")

            _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("페이지 내에서 정보를") }

            if (searchText.isNotEmpty()) {
                val highlightJs = """
                    (function() {
                      var searchText = "${searchText.replace("\"", "\\\"")}";
                      if (window.find && window.find(searchText, false, false, true, false, true, false)) {
                        return "window_find_success";
                      }
                      
                      // Fallback manual highlight
                      var els = document.body.getElementsByTagName('*');
                      for (var i = 0; i < els.length; i++) {
                        var el = els[i];
                        if (el.children.length === 0 && el.textContent.toLowerCase().includes(searchText.toLowerCase())) {
                          el.scrollIntoView({ behavior: 'smooth', block: 'center' });
                          var origBg = el.style.backgroundColor;
                          el.style.backgroundColor = '#FFD166';
                          el.style.transition = 'background-color 0.2s';
                          setTimeout(function() {
                            el.style.backgroundColor = origBg;
                          }, 2500);
                          return "manual_highlight_success";
                        }
                      }
                      return "not_found";
                    })()
                """.trimIndent()
                
                _scriptEvent.emit(highlightJs)
                appendAssistantMessage("🔍 **페이지 내 검색 결과**:\n$explanation\n\n페이지에서 **\"$searchText\"** 부분을 찾아 화면에 노출하고 강조 표시했습니다!")
            } else {
                appendAssistantMessage("페이지에서 해당 정보를 찾지 못했습니다. 문맥에 맞는 다른 질의를 해보세요.")
            }
        } catch (e: Exception) {
            _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("페이지 내에서 정보를") }
            appendSystemMessage("페이지 내 검색 중 오류 발생: ${e.localizedMessage}")
        }
    }

    private suspend fun processContentRequest(userPrompt: String, mode: String) {
        // Prepare context
        val maxTextLen = 8000
        val safeText = if (pageText.length > maxTextLen) pageText.substring(0, maxTextLen) + "...(Truncated)" else pageText
        val pageTitleStr = _pageTitle.value
        val urlStr = _currentUrl.value

        val systemInstruction = """
            You are an advanced Browser AI Assistant. Your task is to analyze the content of the user's currently active web page and answer their request.
            - Current Page Title: $pageTitleStr
            - Current Page URL: $urlStr
            - Extracted Page Content:
            $safeText
            
            Instructions:
            1. Respond politely in the language of the user's prompt (usually Korean).
            2. Be extremely informative, concise, and structured.
            3. If the user wants to extract lists, tables, or links, present them in clean bullet points or lists.
            4. If the page content is empty or unavailable, state so honestly, but try to answer based on general knowledge if appropriate.
        """.trimIndent()

        appendSystemMessage("AI가 웹 페이지 데이터를 읽어 분석 중입니다...")
        val aiResponse = generateAIResponse(
            prompt = userPrompt,
            systemInstruction = systemInstruction
        )
        
        // Replace "Reading..." or append
        _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("분석 중입니다") }
        appendAssistantMessage(aiResponse)
    }

    private suspend fun processAutomationRequest(userPrompt: String) {
        _isExecutingAutomation.value = true
        _currentObjective.value = userPrompt
        _pausedPrompt.value = null
        _automationProgress.value = 0.05f

        var lastActionDescription = "시작 대기 중"
        var step = 1
        val maxSteps = 5

        appendSystemMessage("🤖 **멀티스텝 AI 에이전트 시작**:\n전체 목표: \"$userPrompt\"\n총 5단계 내에서 자동으로 작업을 탐색하고 해결합니다.")

        while (step <= maxSteps && _isExecutingAutomation.value) {
            _automationProgress.value = (step - 1) / maxSteps.toFloat() + 0.05f
            _currentActionStatus.value = "[단계 $step/$maxSteps] 화면 요소 분석 중..."
            appendSystemMessage("🔍 [단계 $step/$maxSteps] 웹 화면 요소를 가져오는 중...")

            // Fetch elements
            _scriptEvent.emit("fetchElements")
            kotlinx.coroutines.delay(1000)

            val elementsStr = pageElementsJson
            if (elementsStr == "[]" || elementsStr.isBlank()) {
                appendSystemMessage("⚠️ 화면 요소를 가져오지 못했습니다. 다시 시도합니다...")
                kotlinx.coroutines.delay(800)
                _scriptEvent.emit("fetchElements")
                kotlinx.coroutines.delay(800)
            }

            if (!_isExecutingAutomation.value) break

            val currentElementsStr = pageElementsJson
            if (currentElementsStr == "[]" || currentElementsStr.isBlank()) {
                appendAssistantMessage("죄송합니다. 이 페이지에서 클릭/입력할 수 있는 대화형 요소를 찾을 수 없어 에이전트 실행을 중단합니다.")
                break
            }

            val systemInstruction = """
                You are a Web Browser Multi-Step Automation Agent.
                Analyze the visible interactive elements on the current webpage and the user's overall objective to decide the next action.
                
                Overall Objective: "$userPrompt"
                Current Step: $step of $maxSteps
                Previous Action Taken: $lastActionDescription
                Current URL: ${_currentUrl.value}
                
                Interactive Elements List (JSON format):
                $currentElementsStr
                
                Output Requirements:
                You must decide the single next best action to move closer to the overall objective.
                Return ONLY a JSON object with the following fields:
                {
                  "action": "click" | "type" | "scroll" | "done" | "none",
                  "index": <the integer index from the element list, or -1 if scroll/done/none>,
                  "text": "<only for 'type' action, the text string to insert>",
                  "explanation": "<short sentence in Korean explaining your action, e.g., '검색창을 클릭해 Kotlin을 입력합니다.'>"
                }
                
                Rules:
                1. If you believe the overall objective is already achieved, set action to "done" and explanation to a success summary.
                2. If you cannot find any suitable element, set action to "none".
                3. Choose only ONE action for this step. If typing, do not click in the same step.
                4. Respond ONLY with valid JSON. No markdown backticks, no extra text outside the JSON.
            """.trimIndent()

            val rawDecision = try {
                generateAIResponse(
                    prompt = "Determine the single best automation action for Step $step.",
                    systemInstruction = systemInstruction,
                    responseJson = true
                )
            } catch (e: Exception) {
                appendSystemMessage("⚠️ [단계 $step] API 요청 오류가 발생했습니다: ${e.localizedMessage}")
                break
            }

            if (!_isExecutingAutomation.value) break

            try {
                val cleanJson = rawDecision.replace("```json", "").replace("```", "").trim()
                val json = JSONObject(cleanJson)
                val action = json.optString("action", "none")
                val index = json.optInt("index", -1)
                val text = json.optString("text", "")
                val explanation = json.optString("explanation", "화면 요소를 자동 조작합니다.")

                lastActionDescription = explanation
                _currentActionStatus.value = "[단계 $step] $explanation"

                if (action == "done") {
                    appendAssistantMessage("🎉 **에이전트가 목표를 달성했습니다!**\n최종 설명: $explanation")
                    break
                }

                if (action == "none" || (action != "scroll" && index == -1)) {
                    appendAssistantMessage("요청하신 동작에 대응하는 웹 요소를 찾지 못해 자동화 루프를 종료합니다.\n이유: $explanation")
                    break
                }

                appendSystemMessage("⚡ **[실행 단계 $step/$maxSteps]**\n$explanation")

                if (action == "scroll") {
                    val scrollScript = if (text == "up") "window.scrollBy({ top: -500, behavior: 'smooth' });" else "window.scrollBy({ top: 500, behavior: 'smooth' });"
                    _scriptEvent.emit(scrollScript)
                } else {
                    // Flash highlight first, then execute
                    _scriptEvent.emit("highlight:$index")
                    kotlinx.coroutines.delay(800)

                    val executionScript = when (action) {
                        "click" -> {
                            """
                                (function() {
                                  var els = document.querySelectorAll('button, a, input, textarea, [role="button"], select');
                                  var el = els[$index];
                                  if (el) {
                                    el.click();
                                    return "success";
                                  }
                                  return "failed";
                                })()
                            """.trimIndent()
                        }
                        "type" -> {
                            """
                                (function() {
                                  var els = document.querySelectorAll('button, a, input, textarea, [role="button"], select');
                                  var el = els[$index];
                                  if (el) {
                                    el.focus();
                                    el.value = "$text";
                                    el.dispatchEvent(new Event('input', { bubbles: true }));
                                    el.dispatchEvent(new Event('change', { bubbles: true }));
                                    
                                    if (el.form) {
                                      setTimeout(function() { el.form.submit(); }, 300);
                                    }
                                    return "success";
                                  }
                                  return "failed";
                                })()
                            """.trimIndent()
                        }
                        else -> ""
                    }

                    if (executionScript.isNotEmpty()) {
                        _scriptEvent.emit(executionScript)
                    }
                }

                // Wait 3 seconds for page interaction/load/navigation before next step
                kotlinx.coroutines.delay(3000)

            } catch (e: Exception) {
                appendSystemMessage("⚠️ [단계 $step] JSON 분석 또는 스크립트 실행 오류: ${e.message}")
                break
            }

            step++
        }

        _isExecutingAutomation.value = false
        _automationProgress.value = 1.0f
        _currentActionStatus.value = "완료됨"
    }

    fun stopAutomation() {
        if (_isExecutingAutomation.value) {
            _isExecutingAutomation.value = false
            _pausedPrompt.value = _currentObjective.value
            _currentActionStatus.value = "일시정지됨 (Stop)"
            appendSystemMessage("🤖 에이전트 자동 실행이 사용자에 의해 일시정지되었습니다.")
        }
    }

    fun resumeAutomation() {
        val paused = _pausedPrompt.value
        if (paused != null && !_isExecutingAutomation.value) {
            _pausedPrompt.value = null
            _isExecutingAutomation.value = true
            appendSystemMessage("⚡ 에이전트 자동 실행을 재개합니다.")
            viewModelScope.launch {
                try {
                    processAutomationRequest(paused)
                } catch (e: Exception) {
                    appendSystemMessage("재개 오류: ${e.localizedMessage}")
                } finally {
                    _isExecutingAutomation.value = false
                    _isThinking.value = false
                }
            }
        }
    }

    fun translateCurrentPage() {
        _isThinking.value = true
        appendSystemMessage("현재 페이지 번역을 준비 중입니다...")
        viewModelScope.launch {
            try {
                val maxTextLen = 8000
                val safeText = if (pageText.length > maxTextLen) pageText.substring(0, maxTextLen) + "...(Truncated)" else pageText
                val systemInstruction = """
                    You are a professional web translator. Translate the given webpage content into natural, high-quality, readable Korean.
                    Maintain formatting such as headings, lists, and paragraphs.
                """.trimIndent()
                val aiResponse = generateAIResponse(
                    prompt = "Translate this content to Korean:\n\n$safeText",
                    systemInstruction = systemInstruction
                )
                _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("번역을 준비 중") }
                appendAssistantMessage("📢 **현재 페이지 번역본**:\n\n$aiResponse")
            } catch (e: Exception) {
                appendSystemMessage("번역 실패: ${e.localizedMessage}")
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun summarizeCurrentPage() {
        _isThinking.value = true
        appendSystemMessage("현재 페이지 요약을 준비 중입니다...")
        viewModelScope.launch {
            try {
                val maxTextLen = 8000
                val safeText = if (pageText.length > maxTextLen) pageText.substring(0, maxTextLen) + "...(Truncated)" else pageText
                val systemInstruction = """
                    You are an expert content summarizer. Summarize the webpage content into 3 clear, informative, and beautifully structured bullet points in Korean.
                    Include a brief introduction and an actionable key takeaway.
                """.trimIndent()
                val aiResponse = generateAIResponse(
                    prompt = "Summarize this content:\n\n$safeText",
                    systemInstruction = systemInstruction
                )
                _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("요약을 준비 중") }
                appendAssistantMessage("📝 **페이지 핵심 요약**:\n\n$aiResponse")
            } catch (e: Exception) {
                appendSystemMessage("요약 실패: ${e.localizedMessage}")
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun autoFillForm() {
        _isThinking.value = true
        appendSystemMessage("폼(Form) 자동 완성을 수행하는 중입니다...")
        viewModelScope.launch {
            try {
                _scriptEvent.emit("fetchElements")
                kotlinx.coroutines.delay(1000)
                
                val elementsStr = pageElementsJson
                if (elementsStr == "[]" || elementsStr.isBlank()) {
                    _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("폼(Form) 자동") }
                    appendAssistantMessage("자동완성할 수 있는 폼 입력 필드를 발견하지 못했습니다. 페이지가 다 로드되었는지 확인해 주세요.")
                    return@launch
                }
                
                val systemInstruction = """
                    You are a Form Auto-filler Agent.
                    Analyze the visible input elements on the page and generate a Javascript snippet to auto-fill them.
                    
                    Visible elements list:
                    $elementsStr
                    
                    Generate smart values for inputs. For example:
                    - If it looks like a name field, fill "홍길동".
                    - If it looks like an email field, fill "gildong@example.com".
                    - If it looks like a phone field, fill "010-1234-5678".
                    - If it looks like a search or query field, fill "Kotlin 개발 가이드".
                    - If it looks like a content or message box, fill "자동 완성 기능을 테스트 중입니다. 잘 작동하네요!".
                    
                    Output Requirement:
                    You must output a Javascript self-executing function string that fills those elements by their array index from elements list.
                    Return ONLY the raw javascript code. Do NOT wrap it in ```js ... ``` or other markdown. Just plain javascript.
                    Example of generated Javascript:
                    (function() {
                      var inputs = document.querySelectorAll('button, a, input, textarea, [role="button"], select');
                      if (inputs[2]) { inputs[2].value = "홍길동"; inputs[2].dispatchEvent(new Event('input', {bubbles:true})); }
                      if (inputs[4]) { inputs[4].value = "gildong@example.com"; inputs[4].dispatchEvent(new Event('input', {bubbles:true})); }
                      return "autofill_success";
                    })()
                """.trimIndent()
                
                val jsCode = generateAIResponse(
                    prompt = "Generate Javascript code to autofill fields based on the elements list.",
                    systemInstruction = systemInstruction
                ).replace("```javascript", "").replace("```js", "").replace("```", "").trim()
                
                _chatMessages.value = _chatMessages.value.filterNot { it.text.contains("폼(Form) 자동") }
                if (jsCode.isNotEmpty() && jsCode.contains("function")) {
                     _scriptEvent.emit(jsCode)
                     appendAssistantMessage("⚡ **AI 스마트 자동완성 완료**:\n페이지의 입력 필드들을 가장 어울리는 가상 정보(이름, 이메일, 전화번호, 검색어 등)로 자동 완성했습니다!")
                } else {
                     appendAssistantMessage("자동완성 필드 분석 결과를 적용하지 못했습니다.")
                }
            } catch (e: Exception) {
                appendSystemMessage("자동완성 실패: ${e.localizedMessage}")
            } finally {
                _isThinking.value = false
            }
        }
    }

    fun appendUserMessage(text: String) {
        _chatMessages.value = _chatMessages.value + ChatMessage("user", text)
    }

    private fun appendAssistantMessage(text: String) {
        _chatMessages.value = _chatMessages.value + ChatMessage("assistant", text)
    }

    private fun appendSystemMessage(text: String) {
        _chatMessages.value = _chatMessages.value + ChatMessage("system", text)
    }

    fun clearChat() {
        _chatMessages.value = listOf(
            ChatMessage(
                "assistant",
                "채팅방이 초기화되었습니다. 웹 페이지를 탐색하고 필요한 질문이나 자동화 명령을 내려주세요!"
            )
        )
    }
}
