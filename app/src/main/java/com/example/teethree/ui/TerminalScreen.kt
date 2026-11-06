package com.example.teethree.ui

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.teethree.data.TerminalSession
import com.example.teethree.ui.adaptive.AdaptiveAlertDialog as AlertDialog
import expo.modules.t3terminal.GhosttyBridge
import expo.modules.t3terminal.TerminalCanvasView
import expo.modules.t3terminal.TerminalEnterListener
import expo.modules.t3terminal.TerminalFrame
import expo.modules.t3terminal.TerminalOutputBuffer
import expo.modules.t3terminal.TerminalSelectionDelegate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    session: TerminalSession,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    onAddContext: ((JsonObject) -> Unit)? = null,
) {
    val displayState =
        remember(session) {
            session.state.map { it.copy(outputVersion = 0) }.distinctUntilChanged()
        }
    val initialState = remember(session) { session.state.value.copy(outputVersion = 0) }
    val state by displayState.collectAsStateWithLifecycle(initialValue = initialState)
    var terminalMenu by remember { mutableStateOf(false) }
    var actionsMenu by remember { mutableStateOf(false) }
    var confirmClose by remember { mutableStateOf(false) }
    var host by remember(session) { mutableStateOf<NativeTerminalHost?>(null) }
    var capturedOutput by
        remember(session) { mutableStateOf<Triple<String, String, String>?>(null) }
    val colors = MaterialTheme.colorScheme
    val appearance = LocalClientAppearance.current

    DisposableEffect(session) {
        session.start()
        onDispose { session.close() }
    }
    LaunchedEffect(session, host) {
        val surface = host ?: return@LaunchedEffect
        var cursor = -1L
        session.state
            .map { it.outputVersion }
            .distinctUntilChanged()
            .collect {
                session.outputSince(cursor)?.let { output ->
                    surface.writeOutput(output.bytes, output.reset)
                    cursor = output.version
                }
            }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Terminal")
                        Text(
                            state.status.replaceFirstChar(Char::uppercase),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { terminalMenu = true }) {
                        Text(state.terminalId)
                        Icon(Icons.Default.ArrowDropDown, null)
                    }
                    DropdownMenu(
                        expanded = terminalMenu,
                        onDismissRequest = { terminalMenu = false },
                    ) {
                        if (onAddContext != null)
                            DropdownMenuItem(
                                text = { Text("Attach visible output to task") },
                                enabled = host != null,
                                onClick = {
                                    actionsMenu = false
                                    val label =
                                        state.terminals
                                            .firstOrNull { it.id == state.terminalId }
                                            ?.label
                                            ?.ifBlank { state.terminalId } ?: state.terminalId
                                    capturedOutput =
                                        Triple(
                                            state.terminalId,
                                            label,
                                            host?.captureVisibleText().orEmpty(),
                                        )
                                },
                            )
                        for (terminal in state.terminals) {
                            DropdownMenuItem(
                                text = { Text(terminal.label.ifBlank { terminal.id }) },
                                onClick = {
                                    session.selectTerminal(terminal.id)
                                    terminalMenu = false
                                },
                            )
                        }
                    }
                    IconButton(onClick = session::newTerminal, enabled = state.canOperate) {
                        Icon(Icons.Default.Add, "New terminal")
                    }
                    IconButton(onClick = { actionsMenu = true }) {
                        Icon(Icons.Default.MoreVert, "Terminal actions")
                    }
                    DropdownMenu(
                        expanded = actionsMenu,
                        onDismissRequest = { actionsMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Clear terminal") },
                            enabled = state.canOperate,
                            onClick = {
                                session.clear()
                                actionsMenu = false
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Close terminal") },
                            enabled = state.canOperate,
                            onClick = {
                                confirmClose = true
                                actionsMenu = false
                            },
                        )
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            state.error?.let { message ->
                Surface(color = colors.errorContainer) {
                    Text(
                        message,
                        Modifier.fillMaxWidth().padding(16.dp),
                        color = colors.onErrorContainer,
                    )
                }
            }
            if (!state.canOperate)
                Text(
                    "Read only",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            if (state.status in setOf("exited", "closed", "error") && state.canOperate) {
                FilledTonalButton(
                    onClick = { session.restart() },
                    modifier = Modifier.padding(16.dp),
                ) {
                    Text("Start shell")
                }
            }
            AndroidView(
                factory = { context ->
                    NativeTerminalHost(context, session::write, session::paste, session::resize)
                        .also { host = it }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
                onRelease = { surface ->
                    surface.cleanup()
                    if (host === surface) host = null
                },
                update = { surface ->
                    surface.setFontSize(appearance.terminalFontSize)
                    surface.setReadOnly(!state.canOperate || state.status != "running")
                    surface.setGrid(state.cols, state.rows)
                    surface.setColors(
                        colors.onSurface.toArgb(),
                        colors.surfaceContainerLowest.toArgb(),
                        colors.primary.toArgb(),
                    )
                },
            )
            if (state.canOperate && state.status == "running") {
                Row(
                    Modifier.fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextButton(onClick = { host?.showKeyboard() }) { Text("Keyboard") }
                    TextButton(onClick = { session.write("\u001b") }) { Text("Esc") }
                    TextButton(onClick = { session.write("\t") }) { Text("Tab") }
                    TextButton(onClick = { session.write("\u0003") }) { Text("Ctrl+C") }
                    TextButton(onClick = { session.write("\u001b[A") }) { Text("↑") }
                    TextButton(onClick = { session.write("\u001b[B") }) { Text("↓") }
                    TextButton(onClick = { session.write("\u001b[D") }) { Text("←") }
                    TextButton(onClick = { session.write("\u001b[C") }) { Text("→") }
                    IconButton(onClick = { host?.pasteClipboard() }) {
                        Icon(Icons.Default.ContentPaste, "Paste")
                    }
                }
            }
        }
    }

    capturedOutput?.let { captured ->
        TerminalContextDialog(
            captured.first,
            captured.second,
            captured.third,
            onDismiss = { capturedOutput = null },
            onAdd = { record ->
                onAddContext?.invoke(record)
                capturedOutput = null
            },
        )
    }
    if (confirmClose)
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Close terminal?") },
            text = { Text("Any command running in this terminal will stop.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        session.closeTerminal()
                        confirmClose = false
                    }
                ) {
                    Text("Close terminal")
                }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Cancel") } },
        )
}

private class NativeTerminalHost(
    context: Context,
    private val onInput: (String) -> Unit,
    private val onPaste: (String) -> Unit,
    private val onResize: (Int, Int) -> Unit,
) : FrameLayout(context) {
    private val canvas = TerminalCanvasView(context)
    private val replay = TerminalOutputBuffer()
    private val input =
        object : EditText(context) {
            override fun getAutofillType(): Int = View.AUTOFILL_TYPE_NONE

            override fun onTextContextMenuItem(id: Int): Boolean {
                if (id == android.R.id.paste || id == android.R.id.pasteAsPlainText) {
                    pasteClipboard()
                    return true
                }
                return super.onTextContextMenuItem(id)
            }
        }
    private var handle = 0L
    private var clearingInput = false
    private var readOnly = true
    private var closed = false
    private var sharedCols = 80
    private var sharedRows = 24
    private var cols = 0
    private var rows = 0
    private var foreground = android.graphics.Color.WHITE
    private var background = android.graphics.Color.BLACK
    private var cursorColor = android.graphics.Color.CYAN

    init {
        contentDescription = "Terminal output"
        canvas.fontSizeSp = 12f
        canvas.onCellMetricsChanged = ::updateGrid
        canvas.onRequestKeyboard = ::showKeyboard
        canvas.onScrollRows = { delta ->
            if (handle != 0L) {
                GhosttyBridge.nativeScroll(handle, delta)
                render()
            }
        }
        canvas.selectionDelegate =
            object : TerminalSelectionDelegate {
                override fun selectWordAt(col: Int, row: Int): Boolean {
                    if (handle == 0L) return false
                    return GhosttyBridge.nativeSelectWordAt(handle, col, row).also { render() }
                }

                override fun extendSelection(anchorCol: Int, anchorRow: Int, col: Int, row: Int) {
                    if (handle != 0L) {
                        GhosttyBridge.nativeExtendSelection(handle, anchorCol, anchorRow, col, row)
                        render()
                    }
                }

                override fun selectAll(): Boolean =
                    handle != 0L && GhosttyBridge.nativeSelectAll(handle).also { render() }

                override fun clearSelection() {
                    if (handle != 0L) {
                        GhosttyBridge.nativeClearSelection(handle)
                        render()
                    }
                }

                override fun selectionText(): String? =
                    if (handle == 0L) null
                    else GhosttyBridge.nativeGetSelectionText(handle)?.toString(Charsets.UTF_8)
            }
        input.setSingleLine(true)
        input.setTextColor(android.graphics.Color.TRANSPARENT)
        input.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        input.alpha = 0.01f
        input.typeface = Typeface.MONOSPACE
        input.isFocusableInTouchMode = true
        input.imeOptions =
            EditorInfo.IME_ACTION_SEND or
                EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                EditorInfo.IME_FLAG_NO_FULLSCREEN or
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        input.inputType =
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        input.setOnEditorActionListener(TerminalEnterListener({ readOnly }) { onInput("\r") })
        input.setOnKeyListener { _, keyCode, event ->
            if (readOnly) return@setOnKeyListener true
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            if (event.isCtrlPressed && keyCode == KeyEvent.KEYCODE_V) {
                pasteClipboard()
                return@setOnKeyListener true
            }
            val data =
                when {
                    event.isCtrlPressed && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z ->
                        (keyCode - KeyEvent.KEYCODE_A + 1).toChar().toString()
                    keyCode == KeyEvent.KEYCODE_DEL -> "\u007f"
                    keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> "\u001b[3~"
                    keyCode == KeyEvent.KEYCODE_ESCAPE -> "\u001b"
                    keyCode == KeyEvent.KEYCODE_TAB -> "\t"
                    keyCode == KeyEvent.KEYCODE_DPAD_UP -> "\u001b[A"
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> "\u001b[B"
                    keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001b[C"
                    keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> "\u001b[D"
                    keyCode == KeyEvent.KEYCODE_MOVE_HOME -> "\u001b[H"
                    keyCode == KeyEvent.KEYCODE_MOVE_END -> "\u001b[F"
                    keyCode == KeyEvent.KEYCODE_PAGE_UP -> "\u001b[5~"
                    keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> "\u001b[6~"
                    else -> null
                }
            if (data == null) false
            else {
                onInput(data)
                true
            }
        }
        input.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    text: CharSequence?,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    text: CharSequence?,
                    start: Int,
                    before: Int,
                    count: Int,
                ) {
                    if (!readOnly && !clearingInput && text != null && count > 0)
                        onInput(
                            text.subSequence(start, minOf(start + count, text.length)).toString()
                        )
                }

                override fun afterTextChanged(editable: Editable?) {
                    if (clearingInput || editable.isNullOrEmpty()) return
                    clearingInput = true
                    editable.clear()
                    clearingInput = false
                }
            }
        )
        addView(canvas, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(input, LayoutParams(1, 1))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        updateGrid()
    }

    fun setFontSize(size: Float) {
        canvas.fontSizeSp = size
        input.textSize = maxOf(size, 13f)
    }

    fun setReadOnly(value: Boolean) {
        if (readOnly == value) return
        readOnly = value
        input.isEnabled = !value
        if (value) input.clearFocus()
    }

    fun setGrid(sharedCols: Int, sharedRows: Int) {
        if (this.sharedCols == sharedCols && this.sharedRows == sharedRows) return
        this.sharedCols = sharedCols
        this.sharedRows = sharedRows
        updateGrid()
    }

    fun setColors(foreground: Int, background: Int, cursor: Int) {
        if (this.foreground == foreground && this.background == background && cursorColor == cursor)
            return
        this.foreground = foreground
        this.background = background
        cursorColor = cursor
        setBackgroundColor(background)
        canvas.setBackgroundColor(background)
        if (handle != 0L) {
            GhosttyBridge.nativeSetTheme(handle, foreground, background, cursor, intArrayOf())
            render()
        }
    }

    fun writeOutput(bytes: ByteArray, reset: Boolean) {
        if (closed) return
        replay.write(bytes, reset)
        if (reset && handle != 0L) {
            GhosttyBridge.nativeDestroy(handle)
            handle = 0L
            canvas.resetSelectionState()
            updateGrid()
        } else if (handle != 0L) {
            feed(bytes)
            render()
        }
    }

    fun showKeyboard() {
        if (readOnly) return
        input.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(input, 0)
    }

    fun pasteClipboard() {
        if (readOnly) return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()?.let(onPaste)
    }

    fun captureVisibleText(): String {
        if (closed || handle == 0L) return ""
        val snapshot = TerminalFrame.decode(GhosttyBridge.nativeSnapshot(handle)) ?: return ""
        return (0 until snapshot.rows).joinToString("\n") { row ->
            (0 until snapshot.cols)
                .joinToString("") { col -> snapshot.cellText[row * snapshot.cols + col] }
                .trimEnd()
        }
    }

    fun cleanup() {
        if (closed) return
        closed = true
        if (handle != 0L) GhosttyBridge.nativeDestroy(handle)
        handle = 0L
        canvas.onCellMetricsChanged = null
        canvas.onRequestKeyboard = null
        canvas.onScrollRows = null
        canvas.selectionDelegate = null
        input.setOnEditorActionListener(null)
        input.setOnKeyListener(null)
    }

    private fun updateGrid() {
        if (closed || canvas.width <= 0 || canvas.height <= 0) return
        val measuredCols = (canvas.usableWidth() / canvas.cellWidthPx).toInt().coerceIn(2, 400)
        val measuredRows = (canvas.usableHeight() / canvas.cellHeightPx).toInt().coerceIn(2, 200)
        onResize(measuredCols, measuredRows)
        val nextCols = minOf(measuredCols, sharedCols.coerceAtLeast(2))
        val nextRows = minOf(measuredRows, sharedRows.coerceAtLeast(2))
        if (nextCols == cols && nextRows == rows && handle != 0L) return
        cols = nextCols
        rows = nextRows
        if (handle == 0L) {
            handle =
                GhosttyBridge.nativeCreate(
                    cols,
                    rows,
                    canvas.cellWidthPx.toInt(),
                    canvas.cellHeightPx.toInt(),
                    foreground,
                    background,
                    cursorColor,
                    intArrayOf(),
                )
            feed(replay.data)
        } else {
            respond(
                GhosttyBridge.nativeResize(
                    handle,
                    cols,
                    rows,
                    canvas.cellWidthPx.toInt(),
                    canvas.cellHeightPx.toInt(),
                )
            )
        }
        render()
    }

    private fun feed(bytes: ByteArray) {
        if (bytes.isEmpty() || handle == 0L) return
        respond(GhosttyBridge.nativeFeed(handle, bytes))
        if (canvas.hasActiveSelection()) {
            GhosttyBridge.nativeClearSelection(handle)
            canvas.resetSelectionState()
        }
    }

    private fun respond(bytes: ByteArray) {
        if (!readOnly && bytes.isNotEmpty()) onInput(bytes.toString(Charsets.UTF_8))
    }

    private fun render() {
        if (handle != 0L)
            TerminalFrame.decode(GhosttyBridge.nativeSnapshot(handle))?.let(canvas::setFrame)
    }
}
