package dev.rivo.relay.ui.chat

import android.view.ViewConfiguration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.contextmenu.builder.item
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuItem
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuKeys
import androidx.compose.foundation.text.contextmenu.data.TextContextMenuSession
import androidx.compose.foundation.text.contextmenu.modifier.appendTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.modifier.filterTextContextMenuComponents
import androidx.compose.foundation.text.contextmenu.provider.LocalTextContextMenuToolbarProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuDataProvider
import androidx.compose.foundation.text.contextmenu.provider.TextContextMenuProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Migrated from QiGuang ChatTimelineContent: preserve its selection gestures and menu.
private object MessageTextMenuKeys {
  val SelectAll = Any()
  val Copy = Any()
  val Quote = Any()
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SelectableReadOnlyText(
  rendered: AnnotatedString,
  modifier: Modifier = Modifier,
  testTag: String,
  onCopyText: (String) -> Unit,
  onQuoteText: ((String) -> Unit)? = null,
  onOpenLink: ((String) -> Unit)? = null,
  onOpenImageLink: ((String) -> Unit)? = null,
  textStyle: TextStyle,
) {
  var selection by rememberSaveable(stateSaver = listSaver<TextRange, Int>(
    save = { listOf(it.start, it.end) }, restore = { TextRange(it[0], it[1]) })) { mutableStateOf(TextRange.Zero) }
  var previousText by rememberSaveable { mutableStateOf(rendered.text) }
  if (previousText != rendered.text) {
    selection = selectionAfterTextUpdate(previousText, rendered.text, selection)
    previousText = rendered.text
  }
  var textLayout by remember(rendered) { mutableStateOf<TextLayoutResult?>(null) }
  var wasFocused by remember { mutableStateOf(false) }
  var visibleTextBounds by remember { mutableStateOf(Rect.Zero) }
  var textOrigin by remember { mutableStateOf(Offset.Zero) }
  var hostOrigin by remember { mutableStateOf(Offset.Zero) }
  val interactionSource = remember { MutableInteractionSource() }
  val currentOpenLink by rememberUpdatedState(onOpenLink)
  val currentOpenImageLink by rememberUpdatedState(onOpenImageLink)

  // Let BasicTextField own the gesture so Compose keeps its native selection
  // handles. Double-tap keeps the native small selection; after long-press
  // selects a word, expand the selection to the surrounding paragraph.
  LaunchedEffect(rendered.text, interactionSource) {
    var activePress: PressInteraction.Press? = null
    var selectAllJob: Job? = null
    interactionSource.interactions.collect { interaction ->
      when (interaction) {
        is PressInteraction.Press -> {
          activePress = interaction
          selectAllJob?.cancel()
          selectAllJob =
            launch {
              delay(ViewConfiguration.getLongPressTimeout().toLong() + 40L)
              selection = paragraphTextRange(rendered.text, selection.start)
            }
        }
        is PressInteraction.Release ->
          if (interaction.press == activePress) {
            selectAllJob?.cancel()
            activePress = null
          }
        is PressInteraction.Cancel ->
          if (interaction.press == activePress) {
            selectAllJob?.cancel()
            activePress = null
          }
        else -> Unit
      }
    }
  }

  fun selectedText(): String {
    val start = minOf(selection.start, selection.end).coerceIn(0, rendered.length)
    val end = maxOf(selection.start, selection.end).coerceIn(start, rendered.length)
    return rendered.text.substring(start, end)
  }

  Box(Modifier.onGloballyPositioned { hostOrigin = it.positionInWindow() }) {
    BackHandler(!selection.collapsed) { selection = TextRange.Zero }
    BasicTextField(
      value = TextFieldValue(annotatedString = rendered, selection = selection),
      onValueChange = { selection = it.selection },
      modifier =
        modifier
          // Measure the text itself instead of inheriting the input field minimum width.
          .width(IntrinsicSize.Max)
          .onGloballyPositioned {
            textOrigin = it.positionInWindow()
            visibleTextBounds = it.boundsInWindow().translate(-textOrigin)
          }
          .onFocusChanged {
            if (wasFocused && !it.isFocused && !visibleTextBounds.isEmpty) selection = TextRange.Zero
            wasFocused = it.isFocused
          }
          .pointerInput(rendered, onOpenLink != null, onOpenImageLink != null) {
            if (onOpenLink == null && onOpenImageLink == null) return@pointerInput
            val movementLimit = 12.dp.toPx()
            val movementLimitSquared = movementLimit * movementLimit
            awaitEachGesture {
              val down = awaitFirstDown(requireUnconsumed = false)
              var latest = down
              var moved = false
              while (latest.pressed) {
                val event = awaitPointerEvent()
                latest = event.changes.firstOrNull { it.id == down.id } ?: break
                val delta = latest.position - down.position
                if (delta.x * delta.x + delta.y * delta.y > movementLimitSquared) moved = true
              }
              val elapsed = latest.uptimeMillis - down.uptimeMillis
              if (!moved && elapsed < ViewConfiguration.getLongPressTimeout()) {
                val offset = textLayout?.getOffsetForPosition(latest.position) ?: return@awaitEachGesture
                val imageTarget = markdownImageLinkAt(rendered, offset)
                if (imageTarget != null) currentOpenImageLink?.invoke(imageTarget)
                else markdownLinkAt(rendered, offset)?.let { currentOpenLink?.invoke(it) }
              }
            }
          }
          // Read-only message actions are driven by the actual selection, not toolbar requests.
          .filterTextContextMenuComponents { false }
          .testTag(testTag),
      readOnly = true,
      textStyle = textStyle,
      interactionSource = interactionSource,
      onTextLayout = { textLayout = it },
    )
    if (!selection.collapsed && selectedText().isNotEmpty()) {
      val anchor = textLayout?.let { layout ->
        val start = minOf(selection.start, selection.end).coerceIn(0, rendered.length)
        val end = maxOf(selection.start, selection.end).coerceIn(start, rendered.length)
        // Inspect visible lines only; an offscreen selection must not pin a menu to the edge.
        val firstLine = layout.getLineForVerticalPosition(visibleTextBounds.top)
        val lastLine = layout.getLineForVerticalPosition(visibleTextBounds.bottom)
        (firstLine..lastLine).firstNotNullOfOrNull { line ->
          val from = maxOf(start, layout.getLineStart(line))
          val to = minOf(end, layout.getLineEnd(line))
          if (from >= to) null else visibleSelectionAnchor(
            layout.getPathForRange(from, to).getBounds(), visibleTextBounds)
        }
      }
      if (anchor != null) ImageTextActionMenu(
        actions = buildList {
          add(ImageMenuAction("全选", "全选", "selection_select_all") { selection = TextRange(0, rendered.length) })
          add(ImageMenuAction("复制", "复制", "selection_copy") {
            onCopyText(selectedText())
            selection = TextRange.Zero
          })
          onQuoteText?.let { quote ->
            add(ImageMenuAction("引用", "引用", "selection_quote") {
              quote(selectedText())
              selection = TextRange.Zero
            })
          }
        },
        position = anchor + textOrigin - hostOrigin,
        tag = "message_text_context_menu",
        focusable = false,
        // Handle popups and scrolling are not selection cancellation.
        onDismiss = {},
      )
    }
  }
}

/** Opaque, platform-shaped selection toolbar with explicit app light/dark colours. */
@Composable
internal fun ThemedTextContextMenu(
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val provider = remember { ThemedTextContextMenuProvider() }
  var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
  DisposableEffect(provider) { onDispose(provider::dismiss) }

  CompositionLocalProvider(LocalTextContextMenuToolbarProvider provides provider) {
    Box(
      modifier =
        modifier
          .onGloballyPositioned { coordinates = it }
          .onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown) provider.resumeAfterUserGesture()
            false
          }
          .pointerInput(provider) {
            awaitEachGesture {
              awaitFirstDown(requireUnconsumed = false)
              // A down event may begin scrolling or selection adjustment.
              // Let Compose own selection dismissal; closing its session here
              // prevents the toolbar from following an unchanged selection.
              provider.resumeAfterUserGesture()
            }
          }
    ) {
      content()
      val session = provider.session
      val anchor = coordinates
      if (session != null && anchor?.isAttached == true) {
        ThemedTextContextMenuPopup(session, anchor, provider)
      }
    }
  }
}

@Composable
private fun ThemedTextContextMenuPopup(
  session: ThemedTextContextMenuSession,
  anchorCoordinates: LayoutCoordinates,
  provider: ThemedTextContextMenuProvider,
) {
  val items = session.dataProvider.data().components.filterIsInstance<TextContextMenuItem>()
    .filter { allowedTextMenuKey(it.key) }
  if (items.isEmpty()) {
    LaunchedEffect(session) { session.close() }
    return
  }
  fun dismissMenu() {
    session.close()
    provider.onActionClosed(session)
  }
  BackHandler { dismissMenu() }
  val density = LocalDensity.current
  val maxWidth = with(density) { LocalWindowInfo.current.containerSize.width.toDp() - 16.dp }
  val position = session.dataProvider.position(anchorCoordinates)
  val positionProvider =
    remember(position, density) {
      TextMenuPositionProvider(
        selectionPosition = IntOffset(position.x.roundToInt(), position.y.roundToInt()),
        marginPx = with(density) { 8.dp.roundToPx() },
      )
    }

  Popup(
    popupPositionProvider = positionProvider,
    // Selection handles live in separate popups. Their down event is an outside
    // touch here; allow Compose to show the toolbar again when dragging ends.
    onDismissRequest = session::close,
    properties = PopupProperties(focusable = false),
  ) {
    Surface(
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surfaceBright.copy(alpha = 1f),
      contentColor = MaterialTheme.colorScheme.onSurface,
      shadowElevation = 10.dp,
      border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 1f)),
      modifier = Modifier.widthIn(max = maxWidth).testTag("message_text_context_menu"),
    ) {
      Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        items.forEach { item ->
          Text(
            text = item.label,
            modifier =
              Modifier.clip(RoundedCornerShape(24.dp))
                .clickable {
                  if (item.keepsMenuOpenAfterClick()) {
                    item.onClick.invoke(NonClosingTextContextMenuSession())
                  } else {
                    // Block reentrant show requests before clipboard/selection callbacks.
                    // Platform and appended actions do not all call close themselves.
                    provider.onActionClosed(session)
                    try {
                      item.onClick.invoke(session)
                    } finally {
                      session.close()
                    }
                  }
                }
                .padding(horizontal = 18.dp, vertical = 13.dp),
            style = MaterialTheme.typography.labelLarge,
          )
        }
      }
    }
  }
}

// Explicit keys avoid device-, locale-, and installed-app-dependent process-text actions.
internal fun allowedTextMenuKey(key: Any): Boolean =
  key == TextContextMenuKeys.CutKey || key == TextContextMenuKeys.CopyKey ||
    key == TextContextMenuKeys.PasteKey || key == TextContextMenuKeys.SelectAllKey ||
    key == TextContextMenuKeys.AutofillKey || key == PasteImageKey ||
    key == MessageTextMenuKeys.SelectAll || key == MessageTextMenuKeys.Copy ||
    key == MessageTextMenuKeys.Quote

private fun TextContextMenuItem.keepsMenuOpenAfterClick(): Boolean =
  key == MessageTextMenuKeys.SelectAll || key == TextContextMenuKeys.SelectAllKey

internal class ThemedTextContextMenuProvider : TextContextMenuProvider {
  var session by mutableStateOf<ThemedTextContextMenuSession?>(null)
    private set
  private var suppressedUntilUserGesture = false

  override suspend fun showTextContextMenu(dataProvider: TextContextMenuDataProvider) {
    if (suppressedUntilUserGesture) return
    val nextSession = ThemedTextContextMenuSession(dataProvider)
    session?.close()
    session = nextSession
    try {
      nextSession.awaitClose()
    } finally {
      if (session === nextSession) session = null
    }
  }

  fun onActionClosed(target: ThemedTextContextMenuSession) {
    if (session !== target) return
    suppressedUntilUserGesture = true
    session = null
  }

  fun resumeAfterUserGesture() {
    suppressedUntilUserGesture = false
  }

  fun dismiss() {
    session?.close()
    session = null
  }
}

private class NonClosingTextContextMenuSession : TextContextMenuSession {
  override fun close() = Unit
}

internal class ThemedTextContextMenuSession(
  val dataProvider: TextContextMenuDataProvider,
) : TextContextMenuSession {
  private val closeSignal = Channel<Unit>(capacity = Channel.CONFLATED)
  var closeRequested = false
    private set

  override fun close() {
    closeRequested = true
    closeSignal.trySend(Unit)
  }

  suspend fun awaitClose() {
    closeSignal.receive()
  }
}

internal class TextMenuPositionProvider(
  private val selectionPosition: IntOffset,
  private val marginPx: Int,
) : PopupPositionProvider {
  override fun calculatePosition(
    anchorBounds: IntRect,
    windowSize: IntSize,
    layoutDirection: LayoutDirection,
    popupContentSize: IntSize,
  ): IntOffset {
    val maxX = (windowSize.width - popupContentSize.width).coerceAtLeast(0)
    val maxY = (windowSize.height - popupContentSize.height).coerceAtLeast(0)
    val x = (anchorBounds.left + selectionPosition.x).coerceIn(0, maxX)
    val above = anchorBounds.top + selectionPosition.y - popupContentSize.height - marginPx
    val y =
      if (above >= 0) above.coerceAtMost(maxY)
      else (anchorBounds.top + selectionPosition.y + marginPx).coerceIn(0, maxY)
    return IntOffset(x, y)
  }
}

internal fun visibleSelectionAnchor(selectionBounds: Rect, viewport: Rect): Offset? {
  val visible = selectionBounds.intersect(viewport)
  return if (visible.isEmpty) null else visible.topLeft
}

internal fun selectionAfterTextUpdate(previous: String, current: String, selection: TextRange): TextRange {
  // Preserve offsets only while their meaning remains unchanged.
  if (current.startsWith(previous)) return selection
  if (previous.startsWith(current)) return TextRange(
    selection.start.coerceIn(0, current.length), selection.end.coerceIn(0, current.length))
  return TextRange.Zero
}

private fun paragraphTextRange(text: String, position: Int): TextRange {
  val caret = position.coerceIn(0, text.length)
  val start = text.lastIndexOf("\n\n", (caret - 1).coerceAtLeast(0)).let { separator ->
    if (separator < 0) 0 else separator + 2
  }
  val end = text.indexOf("\n\n", caret).let { separator ->
    if (separator < 0) text.length else separator
  }
  return TextRange(start, end)
}

internal fun appendQuoteToDraft(draft: String, selectedText: String): String {
  val source = selectedText.trim()
  if (source.isEmpty()) return draft
  val quote =
    source.lineSequence().joinToString("\n") { line ->
      if (line.isEmpty()) ">" else "> $line"
    }
  return if (draft.isBlank()) "$quote\n\n" else "${draft.trimEnd()}\n\n$quote\n\n"
}
