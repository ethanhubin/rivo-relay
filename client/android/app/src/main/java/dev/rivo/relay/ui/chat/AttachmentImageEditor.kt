package dev.rivo.relay.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Rect as AndroidRect
import android.graphics.RectF as AndroidRectF
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val EDITOR_MAX_BITMAP_EDGE = 4_096
private val DefaultAnnotationRed = Color(0xFFF04444)
private val EditorChromeColor = Color(0xCC181820)
private val EditorSelectedBackground = Color(0x664E3D91)
private val EditorSelectedIcon = Color(0xFFB8A8FF)
private val InitialCrop = Rect(.15f, .15f, .85f, .85f)
private val FullImageViewport = Rect(0f, 0f, 1f, 1f)

internal enum class AttachmentEditorTool {
  CROP,
  ARROW,
  RECTANGLE,
  CIRCLE,
}

internal enum class CropHandle {
  NONE,
  MOVE,
  LEFT,
  TOP,
  RIGHT,
  BOTTOM,
  TOP_LEFT,
  TOP_RIGHT,
  BOTTOM_LEFT,
  BOTTOM_RIGHT,
}

private enum class AnnotationHandle { START, END }

internal data class AttachmentAnnotation(
  val id: Long,
  val tool: AttachmentEditorTool,
  val start: Offset,
  val end: Offset,
  val color: Color,
)

internal data class AttachmentEditResult(
  val crop: Rect,
  val annotations: List<AttachmentAnnotation>,
) {
  fun isNoOp(): Boolean =
    annotations.isEmpty() &&
      crop.left <= .001f && crop.top <= .001f &&
      crop.right >= .999f && crop.bottom >= .999f
}

@Composable
internal fun AttachmentImageEditor(
  source: DraftImage,
  onDismiss: () -> Unit,
  onComplete: (DraftImage) -> Unit,
  alwaysExport: Boolean = false,
) {
  val scope = rememberCoroutineScope()
  var bitmap by remember(source) { mutableStateOf<Bitmap?>(null) }
  var loadingError by remember(source) { mutableStateOf<String?>(null) }
  var saving by remember(source) { mutableStateOf(false) }

  LaunchedEffect(source) {
    val result = runCatching { withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(source.bytes, 0, source.bytes.size) ?: error("无法读取图片") } }
    withContext(Dispatchers.Main.immediate) {
      result
        .onSuccess { bitmap = it }
        .onFailure {
          if (it is CancellationException) throw it
          loadingError = it.message ?: "无法读取图片"
        }
    }
  }

  Dialog(
    onDismissRequest = { if (!saving) onDismiss() },
    properties =
      DialogProperties(
        usePlatformDefaultWidth = false,
        dismissOnBackPress = true,
        dismissOnClickOutside = false,
        decorFitsSystemWindows = false,
      ),
  ) {
    val editorWindow = (LocalView.current.parent as DialogWindowProvider).window
    DisposableEffect(editorWindow) {
      editorWindow.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
      editorWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
      onDispose { }
    }
    BackHandler(enabled = saving) {}
    val loadedBitmap = bitmap
    if (loadedBitmap == null) {
      Box(
        Modifier.fillMaxSize().background(Color.Black).testTag("attachment_image_editor"),
        contentAlignment = Alignment.Center,
      ) {
        if (loadingError == null) {
          CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        } else {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(loadingError.orEmpty(), color = Color.White)
            Spacer(Modifier.height(12.dp))
            Surface(
              onClick = onDismiss,
              shape = RoundedCornerShape(16.dp),
              color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
              Text("关闭", modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            }
          }
        }
      }
    } else {
      AttachmentImageEditorContent(
        bitmap = loadedBitmap,
        saving = saving,
        errorMessage = loadingError,
        onDismiss = onDismiss,
        onFinish = { result ->
          if (saving) return@AttachmentImageEditorContent
          if (!alwaysExport && result.isNoOp()) {
            onComplete(source)
            return@AttachmentImageEditorContent
          }
          loadingError = null
          saving = true
          scope.launch {
            runCatching { withContext(Dispatchers.Default) {
              val edited = renderEditedBitmap(loadedBitmap, result)
              try { DraftImage(source.id, compressDraftBitmap(edited)) }
              finally { if (edited !== loadedBitmap) edited.recycle() }
            } }
              .onSuccess(onComplete)
              .onFailure {
                if (it is CancellationException) throw it
                loadingError = it.message ?: "图片编辑结果保存失败"
              }
            saving = false
          }
        },
      )
    }
  }
}

@Composable
internal fun AttachmentImageEditorContent(
  bitmap: Bitmap,
  saving: Boolean = false,
  errorMessage: String? = null,
  onDismiss: () -> Unit,
  onFinish: (AttachmentEditResult) -> Unit,
) {
  val image = remember(bitmap) { bitmap.asImageBitmap() }
  var tool by remember(bitmap) { mutableStateOf<AttachmentEditorTool?>(null) }
  var crop by remember(bitmap) { mutableStateOf(FullImageViewport) }
  var cropStarted by remember(bitmap) { mutableStateOf(false) }
  val annotations = remember(bitmap) { mutableStateListOf<AttachmentAnnotation>() }
  var selectedAnnotationId by remember(bitmap) { mutableStateOf<Long?>(null) }
  var nextAnnotationId by remember(bitmap) { mutableStateOf(1L) }
  var currentColor by remember(bitmap) { mutableStateOf(DefaultAnnotationRed) }
  var colorsVisible by remember(bitmap) { mutableStateOf(false) }
  var canvasSize by remember(bitmap) { mutableStateOf(IntSize.Zero) }
  val density = LocalDensity.current
  val handleTolerance = with(density) { 26.dp.toPx() }

  Box(
    Modifier.fillMaxSize().background(Color.Black).testTag("attachment_image_editor"),
  ) {
    Canvas(
      modifier =
        Modifier.fillMaxSize()
          .padding(horizontal = 16.dp, vertical = 0.dp)
          .padding(top = 84.dp, bottom = 104.dp)
          .onSizeChanged { canvasSize = it }
          .pointerInput(tool, canvasSize, bitmap) {
            if (canvasSize == IntSize.Zero) return@pointerInput
            var cropHandle = CropHandle.NONE
            var annotationIndex = -1
            var annotationHandle = AnnotationHandle.END
            var draftAnnotationId: Long? = null
            detectDragGestures(
              onDragStart = { position ->
                val activeTool = tool ?: return@detectDragGestures
                val viewport = editorViewport(tool, crop)
                val bounds = fittedImageRect(canvasSize, bitmap, viewport)
                if (!bounds.contains(position)) return@detectDragGestures
                colorsVisible = false
                if (activeTool == AttachmentEditorTool.CROP) {
                  cropHandle = cropHandleAt(position, crop.toScreen(bounds), handleTolerance)
                } else {
                  val selectedIndex =
                    selectedAnnotationId?.let { selected ->
                      annotations.indexOfFirst { it.id == selected }.takeIf { it >= 0 }
                    }
                  val selected = selectedIndex?.let(annotations::get)
                  val selectedHandle =
                    selected?.let {
                      annotationHandleAt(position, it, bounds, viewport, handleTolerance)
                    }
                  if (selectedIndex != null && selectedHandle != null) {
                    annotationIndex = selectedIndex
                    annotationHandle = selectedHandle
                  } else {
                    val point = position.toNormalized(bounds, viewport)
                    val annotation =
                      AttachmentAnnotation(
                        id = nextAnnotationId++,
                        tool = activeTool,
                        start = point,
                        end = point,
                        color = currentColor,
                      )
                    annotations += annotation
                    annotationIndex = annotations.lastIndex
                    annotationHandle = AnnotationHandle.END
                    draftAnnotationId = annotation.id
                    selectedAnnotationId = annotation.id
                  }
                }
              },
              onDrag = { change, dragAmount ->
                change.consume()
                val viewport = editorViewport(tool, crop)
                val bounds = fittedImageRect(canvasSize, bitmap, viewport)
                if (tool == AttachmentEditorTool.CROP && cropHandle != CropHandle.NONE) {
                  crop =
                    resizeNormalizedCrop(
                      crop,
                      cropHandle,
                      dragAmount.x / bounds.width,
                      dragAmount.y / bounds.height,
                    )
                } else if (annotationIndex in annotations.indices) {
                  val previous = annotations[annotationIndex]
                  val delta =
                    Offset(
                      dragAmount.x / bounds.width * viewport.width,
                      dragAmount.y / bounds.height * viewport.height,
                    )
                  annotations[annotationIndex] =
                    when (annotationHandle) {
                      AnnotationHandle.START ->
                        previous.copy(start = (previous.start + delta).clampTo(viewport))
                      AnnotationHandle.END ->
                        previous.copy(end = (previous.end + delta).clampTo(viewport))
                    }
                }
              },
              onDragEnd = {
                val draftId = draftAnnotationId
                if (draftId != null) {
                  val viewport = editorViewport(tool, crop)
                  val bounds = fittedImageRect(canvasSize, bitmap, viewport)
                  val index = annotations.indexOfFirst { it.id == draftId }
                  if (
                    index >= 0 &&
                      annotations[index].screenLength(bounds, viewport) < handleTolerance * .65f
                  ) {
                    annotations.removeAt(index)
                    selectedAnnotationId = null
                  }
                }
              },
              onDragCancel = {
                val draftId = draftAnnotationId
                if (draftId != null) annotations.removeAll { it.id == draftId }
              },
            )
          }
          .testTag("attachment_editor_canvas"),
    ) {
      val viewport = editorViewport(tool, crop)
      val bounds = fittedImageRect(size.toIntSize(), bitmap, viewport)
      if (tool == AttachmentEditorTool.CROP) {
        drawEditorImage(image, bitmap, bounds, viewport)
        annotations.forEach { annotation -> drawAnnotation(annotation, bounds, viewport) }
        drawCropOverlay(crop.toScreen(bounds), bounds)
      } else {
        clipRect(bounds.left, bounds.top, bounds.right, bounds.bottom) {
          drawEditorImage(image, bitmap, bounds, viewport)
          annotations.forEach { annotation -> drawAnnotation(annotation, bounds, viewport) }
          annotations.firstOrNull { it.id == selectedAnnotationId }?.let { selected ->
            drawAnnotationHandles(selected, bounds, viewport)
          }
        }
      }
    }

    Row(
      modifier =
        Modifier.fillMaxWidth()
          .statusBarsPadding()
          .padding(horizontal = 20.dp, vertical = 12.dp),
      horizontalArrangement = Arrangement.SpaceBetween,
      verticalAlignment = Alignment.CenterVertically,
    ) {
      EditorTopAction(
        text = "×",
        description = "关闭图片编辑",
        tag = "attachment_editor_close",
        enabled = !saving,
        onClick = onDismiss,
      )
      Surface(
        onClick = {
          onFinish(AttachmentEditResult(crop, annotations.toList()))
        },
        enabled = !saving,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.testTag("attachment_editor_done"),
      ) {
        Box(
          modifier = Modifier.height(48.dp).padding(horizontal = 20.dp),
          contentAlignment = Alignment.Center,
        ) {
          if (saving) {
            CircularProgressIndicator(
              modifier = Modifier.size(22.dp),
              strokeWidth = 2.dp,
              color = MaterialTheme.colorScheme.onPrimary,
            )
          } else {
            Text(
              "完成",
              color = MaterialTheme.colorScheme.onPrimary,
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.SemiBold,
            )
          }
        }
      }
    }

    if (errorMessage != null) {
      Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        modifier =
          Modifier.align(Alignment.TopCenter)
            .statusBarsPadding()
            .padding(horizontal = 24.dp)
            .padding(top = 72.dp)
            .testTag("attachment_editor_error"),
      ) {
        Text(
          errorMessage,
          color = MaterialTheme.colorScheme.onErrorContainer,
          style = MaterialTheme.typography.bodySmall,
          modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
      }
    }

    Column(
      modifier =
        Modifier.align(Alignment.BottomCenter)
          .navigationBarsPadding()
          .padding(bottom = 18.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (colorsVisible) {
        EditorColorPalette(
          selected = currentColor,
          onSelect = { color ->
            currentColor = color
            selectedAnnotationId?.let { selected ->
              val index = annotations.indexOfFirst { it.id == selected }
              if (index >= 0) annotations[index] = annotations[index].copy(color = color)
            }
            colorsVisible = false
          },
        )
      }
      Surface(
        shape = RoundedCornerShape(28.dp),
        color = EditorChromeColor,
        modifier = Modifier.testTag("attachment_editor_toolbar"),
      ) {
        Row(
          modifier = Modifier.padding(6.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          AttachmentEditorTool.entries.forEach { candidate ->
            EditorToolButton(
              tool = candidate,
              selected = tool == candidate,
              onClick = {
                tool = candidate
                colorsVisible = false
                if (candidate == AttachmentEditorTool.CROP) {
                  if (!cropStarted) {
                    crop = InitialCrop
                    cropStarted = true
                  }
                  selectedAnnotationId = null
                }
              },
            )
          }
          Box(
            modifier =
              Modifier.size(52.dp)
                .clip(CircleShape)
                .clickable(enabled = !saving) { colorsVisible = !colorsVisible }
                .semantics { contentDescription = "选择标注颜色" }
                .testTag("attachment_editor_color"),
            contentAlignment = Alignment.Center,
          ) {
            Box(
              Modifier.size(25.dp)
                .clip(CircleShape)
                .background(Color.White)
                .padding(3.dp)
                .clip(CircleShape)
                .background(currentColor)
            )
          }
        }
      }
    }
  }
}

@Composable
private fun EditorTopAction(
  text: String,
  description: String,
  tag: String,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  Surface(
    onClick = onClick,
    enabled = enabled,
    shape = CircleShape,
    color = EditorChromeColor,
    modifier =
      Modifier.size(50.dp)
        .semantics { contentDescription = description }
        .testTag(tag),
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(text, color = Color.White, style = MaterialTheme.typography.headlineSmall)
    }
  }
}

@Composable
private fun EditorToolButton(
  tool: AttachmentEditorTool,
  selected: Boolean,
  onClick: () -> Unit,
) {
  val description =
    when (tool) {
      AttachmentEditorTool.CROP -> "自由裁切"
      AttachmentEditorTool.ARROW -> "绘制定向箭头"
      AttachmentEditorTool.RECTANGLE -> "绘制矩形框"
      AttachmentEditorTool.CIRCLE -> "绘制圆形框"
    }
  Box(
    modifier =
      Modifier.size(52.dp)
        .clip(RoundedCornerShape(16.dp))
        .background(
          if (selected) EditorSelectedBackground else Color.Transparent
        )
        .clickable(onClick = onClick)
        .semantics {
          contentDescription = description
          this.selected = selected
        }
        .testTag("attachment_editor_tool_${tool.name.lowercase()}"),
    contentAlignment = Alignment.Center,
  ) {
    val iconColor = if (selected) EditorSelectedIcon else Color.White
    Canvas(Modifier.size(25.dp)) {
      val stroke = 2.2.dp.toPx()
      when (tool) {
        AttachmentEditorTool.CROP -> {
          val inset = size.minDimension * .15f
          val middle = size.minDimension * .48f
          drawLine(iconColor, Offset(inset, 0f), Offset(inset, middle), stroke, StrokeCap.Round)
          drawLine(iconColor, Offset(inset, inset), Offset(middle, inset), stroke, StrokeCap.Round)
          drawLine(iconColor, Offset(size.width - inset, size.height), Offset(size.width - inset, size.height - middle), stroke, StrokeCap.Round)
          drawLine(iconColor, Offset(size.width - inset, size.height - inset), Offset(size.width - middle, size.height - inset), stroke, StrokeCap.Round)
          drawLine(iconColor, Offset(size.width - inset, inset), Offset(size.width - inset, size.height - inset), stroke)
          drawLine(iconColor, Offset(inset, size.height - inset), Offset(size.width - inset, size.height - inset), stroke)
        }
        AttachmentEditorTool.ARROW -> {
          val start = Offset(size.width * .18f, size.height * .82f)
          val end = Offset(size.width * .82f, size.height * .18f)
          drawLine(iconColor, start, end, stroke, StrokeCap.Round)
          drawLine(iconColor, end, Offset(size.width * .51f, size.height * .18f), stroke, StrokeCap.Round)
          drawLine(iconColor, end, Offset(size.width * .82f, size.height * .49f), stroke, StrokeCap.Round)
        }
        AttachmentEditorTool.RECTANGLE ->
          drawRect(
            color = iconColor,
            topLeft = Offset(size.width * .15f, size.height * .2f),
            size = androidx.compose.ui.geometry.Size(size.width * .7f, size.height * .6f),
            style = Stroke(stroke),
          )
        AttachmentEditorTool.CIRCLE ->
          drawCircle(color = iconColor, radius = size.minDimension * .34f, style = Stroke(stroke))
      }
    }
  }
}

@Composable
private fun EditorColorPalette(selected: Color, onSelect: (Color) -> Unit) {
  val colors =
    listOf(
      DefaultAnnotationRed,
      Color(0xFFF59E42),
      Color(0xFFF4D44D),
      Color(0xFF45C58A),
      Color(0xFF4CC9E8),
      Color(0xFF5E8CFF),
      Color(0xFFA99AF8),
      Color.White,
    )
  Surface(
    shape = RoundedCornerShape(22.dp),
    color = EditorChromeColor,
    modifier = Modifier.testTag("attachment_editor_color_palette"),
  ) {
    Row(Modifier.padding(8.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
      colors.forEachIndexed { index, color ->
        Box(
          modifier =
            Modifier.size(38.dp)
              .clip(CircleShape)
              .clickable { onSelect(color) }
              .semantics { contentDescription = "标注颜色 ${index + 1}" }
              .testTag("attachment_editor_color_$index"),
          contentAlignment = Alignment.Center,
        ) {
          Box(
            Modifier.size(if (selected == color) 29.dp else 25.dp)
              .clip(CircleShape)
              .background(if (selected == color) EditorSelectedIcon else Color.Transparent)
              .padding(if (selected == color) 3.dp else 0.dp)
              .clip(CircleShape)
              .background(color)
          )
        }
      }
    }
  }
}

private fun DrawScope.drawEditorImage(
  image: ImageBitmap,
  bitmap: Bitmap,
  bounds: Rect,
  viewport: Rect,
) {
  val sourceLeft = floor(viewport.left * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
  val sourceTop = floor(viewport.top * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
  val sourceRight = ceil(viewport.right * bitmap.width).toInt().coerceIn(sourceLeft + 1, bitmap.width)
  val sourceBottom = ceil(viewport.bottom * bitmap.height).toInt().coerceIn(sourceTop + 1, bitmap.height)
  drawImage(
    image = image,
    srcOffset = IntOffset(sourceLeft, sourceTop),
    srcSize = IntSize(sourceRight - sourceLeft, sourceBottom - sourceTop),
    dstOffset = IntOffset(bounds.left.toInt(), bounds.top.toInt()),
    dstSize = IntSize(bounds.width.toInt(), bounds.height.toInt()),
    filterQuality = FilterQuality.High,
  )
}

private fun DrawScope.drawCropOverlay(crop: Rect, imageBounds: Rect) {
  val mask = Color.Black.copy(alpha = .58f)
  drawRect(mask, Offset(imageBounds.left, imageBounds.top), androidx.compose.ui.geometry.Size(imageBounds.width, crop.top - imageBounds.top))
  drawRect(mask, Offset(imageBounds.left, crop.bottom), androidx.compose.ui.geometry.Size(imageBounds.width, imageBounds.bottom - crop.bottom))
  drawRect(mask, Offset(imageBounds.left, crop.top), androidx.compose.ui.geometry.Size(crop.left - imageBounds.left, crop.height))
  drawRect(mask, Offset(crop.right, crop.top), androidx.compose.ui.geometry.Size(imageBounds.right - crop.right, crop.height))
  drawRect(Color.White.copy(alpha = .9f), topLeft = crop.topLeft, size = crop.size, style = Stroke(1.2.dp.toPx()))
  repeat(2) { index ->
    val fraction = (index + 1) / 3f
    drawLine(Color.White.copy(alpha = .48f), Offset(crop.left + crop.width * fraction, crop.top), Offset(crop.left + crop.width * fraction, crop.bottom), 1.dp.toPx())
    drawLine(Color.White.copy(alpha = .48f), Offset(crop.left, crop.top + crop.height * fraction), Offset(crop.right, crop.top + crop.height * fraction), 1.dp.toPx())
  }
  val length = min(crop.width, crop.height) * .075f
  val width = 4.dp.toPx()
  fun corner(point: Offset, xDirection: Float, yDirection: Float) {
    drawLine(Color.White, point, point + Offset(length * xDirection, 0f), width, StrokeCap.Square)
    drawLine(Color.White, point, point + Offset(0f, length * yDirection), width, StrokeCap.Square)
  }
  corner(crop.topLeft, 1f, 1f)
  corner(crop.topRight, -1f, 1f)
  corner(crop.bottomLeft, 1f, -1f)
  corner(crop.bottomRight, -1f, -1f)
  val sideLength = length * .7f
  drawLine(Color.White, Offset(crop.center.x - sideLength / 2f, crop.top), Offset(crop.center.x + sideLength / 2f, crop.top), width)
  drawLine(Color.White, Offset(crop.center.x - sideLength / 2f, crop.bottom), Offset(crop.center.x + sideLength / 2f, crop.bottom), width)
  drawLine(Color.White, Offset(crop.left, crop.center.y - sideLength / 2f), Offset(crop.left, crop.center.y + sideLength / 2f), width)
  drawLine(Color.White, Offset(crop.right, crop.center.y - sideLength / 2f), Offset(crop.right, crop.center.y + sideLength / 2f), width)
}

private fun DrawScope.drawAnnotation(
  annotation: AttachmentAnnotation,
  bounds: Rect,
  viewport: Rect,
) {
  val start = annotation.start.toScreen(bounds, viewport)
  val end = annotation.end.toScreen(bounds, viewport)
  val stroke = 4.dp.toPx()
  when (annotation.tool) {
    AttachmentEditorTool.ARROW -> drawArrow(annotation.color, start, end, stroke)
    AttachmentEditorTool.RECTANGLE -> {
      val rect = rectFromPoints(start, end)
      drawRect(annotation.color, rect.topLeft, rect.size, style = Stroke(stroke))
    }
    AttachmentEditorTool.CIRCLE -> {
      val rect = rectFromPoints(start, end)
      drawOval(annotation.color, rect.topLeft, rect.size, style = Stroke(stroke))
    }
    AttachmentEditorTool.CROP -> Unit
  }
}

private fun DrawScope.drawArrow(color: Color, start: Offset, end: Offset, stroke: Float) {
  drawLine(color, start, end, stroke, StrokeCap.Round)
  val angle = atan2(end.y - start.y, end.x - start.x)
  val head = 18.dp.toPx()
  val spread = Math.toRadians(28.0).toFloat()
  drawLine(
    color,
    end,
    Offset(end.x - cos(angle - spread) * head, end.y - sin(angle - spread) * head),
    stroke,
    StrokeCap.Round,
  )
  drawLine(
    color,
    end,
    Offset(end.x - cos(angle + spread) * head, end.y - sin(angle + spread) * head),
    stroke,
    StrokeCap.Round,
  )
}

private fun DrawScope.drawAnnotationHandles(
  annotation: AttachmentAnnotation,
  bounds: Rect,
  viewport: Rect,
) {
  val start = annotation.start.toScreen(bounds, viewport)
  val end = annotation.end.toScreen(bounds, viewport)
  listOf(start, end).forEach { point ->
    drawCircle(Color.Black.copy(alpha = .7f), radius = 8.dp.toPx(), center = point)
    drawCircle(annotation.color, radius = 6.dp.toPx(), center = point)
    drawCircle(Color.White, radius = 6.dp.toPx(), center = point, style = Stroke(1.5.dp.toPx()))
  }
}

private fun fittedImageRect(size: IntSize, imageWidth: Int, imageHeight: Int): Rect {
  if (size.width <= 0 || size.height <= 0 || imageWidth <= 0 || imageHeight <= 0) return Rect.Zero
  val scale = min(size.width.toFloat() / imageWidth, size.height.toFloat() / imageHeight)
  val width = imageWidth * scale
  val height = imageHeight * scale
  val left = (size.width - width) / 2f
  val top = (size.height - height) / 2f
  return Rect(left, top, left + width, top + height)
}

private fun fittedImageRect(size: IntSize, bitmap: Bitmap, viewport: Rect): Rect =
  fittedImageRect(
    size = size,
    imageWidth = (bitmap.width * viewport.width).toInt().coerceAtLeast(1),
    imageHeight = (bitmap.height * viewport.height).toInt().coerceAtLeast(1),
  )

internal fun editorViewport(tool: AttachmentEditorTool?, crop: Rect): Rect =
  if (tool == AttachmentEditorTool.CROP) FullImageViewport else crop

private fun androidx.compose.ui.geometry.Size.toIntSize() =
  IntSize(width.toInt().coerceAtLeast(1), height.toInt().coerceAtLeast(1))

private fun Rect.toScreen(bounds: Rect): Rect =
  Rect(
    left = bounds.left + left * bounds.width,
    top = bounds.top + top * bounds.height,
    right = bounds.left + right * bounds.width,
    bottom = bounds.top + bottom * bounds.height,
  )

private fun Offset.toScreen(bounds: Rect): Offset =
  Offset(bounds.left + x * bounds.width, bounds.top + y * bounds.height)

private fun Offset.toScreen(bounds: Rect, viewport: Rect): Offset =
  Offset(
    bounds.left + (x - viewport.left) / viewport.width * bounds.width,
    bounds.top + (y - viewport.top) / viewport.height * bounds.height,
  )

private fun Offset.toNormalized(bounds: Rect, viewport: Rect): Offset =
  Offset(
      viewport.left + (x - bounds.left) / bounds.width * viewport.width,
      viewport.top + (y - bounds.top) / bounds.height * viewport.height,
    )
    .clampTo(viewport)

private fun Offset.clampNormalized() = Offset(x.coerceIn(0f, 1f), y.coerceIn(0f, 1f))

private fun Offset.clampTo(bounds: Rect) =
  Offset(x.coerceIn(bounds.left, bounds.right), y.coerceIn(bounds.top, bounds.bottom))

private fun rectFromPoints(first: Offset, second: Offset): Rect =
  Rect(
    min(first.x, second.x),
    min(first.y, second.y),
    max(first.x, second.x),
    max(first.y, second.y),
  )

private fun cropHandleAt(point: Offset, crop: Rect, tolerance: Float): CropHandle {
  fun near(target: Offset) = (point - target).getDistance() <= tolerance
  return when {
    near(crop.topLeft) -> CropHandle.TOP_LEFT
    near(crop.topRight) -> CropHandle.TOP_RIGHT
    near(crop.bottomLeft) -> CropHandle.BOTTOM_LEFT
    near(crop.bottomRight) -> CropHandle.BOTTOM_RIGHT
    kotlin.math.abs(point.x - crop.left) <= tolerance && point.y in crop.top..crop.bottom -> CropHandle.LEFT
    kotlin.math.abs(point.x - crop.right) <= tolerance && point.y in crop.top..crop.bottom -> CropHandle.RIGHT
    kotlin.math.abs(point.y - crop.top) <= tolerance && point.x in crop.left..crop.right -> CropHandle.TOP
    kotlin.math.abs(point.y - crop.bottom) <= tolerance && point.x in crop.left..crop.right -> CropHandle.BOTTOM
    crop.contains(point) -> CropHandle.MOVE
    else -> CropHandle.NONE
  }
}

internal fun resizeNormalizedCrop(
  crop: Rect,
  handle: CropHandle,
  dx: Float,
  dy: Float,
): Rect {
  val minimum = .12f
  var left = crop.left
  var top = crop.top
  var right = crop.right
  var bottom = crop.bottom
  when (handle) {
    CropHandle.LEFT, CropHandle.TOP_LEFT, CropHandle.BOTTOM_LEFT ->
      left = (left + dx).coerceIn(0f, right - minimum)
    CropHandle.RIGHT, CropHandle.TOP_RIGHT, CropHandle.BOTTOM_RIGHT ->
      right = (right + dx).coerceIn(left + minimum, 1f)
    else -> Unit
  }
  when (handle) {
    CropHandle.TOP, CropHandle.TOP_LEFT, CropHandle.TOP_RIGHT ->
      top = (top + dy).coerceIn(0f, bottom - minimum)
    CropHandle.BOTTOM, CropHandle.BOTTOM_LEFT, CropHandle.BOTTOM_RIGHT ->
      bottom = (bottom + dy).coerceIn(top + minimum, 1f)
    else -> Unit
  }
  if (handle == CropHandle.MOVE) {
    val translatedLeft = (left + dx).coerceIn(0f, 1f - crop.width)
    val translatedTop = (top + dy).coerceIn(0f, 1f - crop.height)
    right = translatedLeft + crop.width
    bottom = translatedTop + crop.height
    left = translatedLeft
    top = translatedTop
  }
  return Rect(left, top, right, bottom)
}

private fun annotationHandleAt(
  point: Offset,
  annotation: AttachmentAnnotation,
  bounds: Rect,
  viewport: Rect,
  tolerance: Float,
): AnnotationHandle? =
  when {
    (point - annotation.start.toScreen(bounds, viewport)).getDistance() <= tolerance ->
      AnnotationHandle.START
    (point - annotation.end.toScreen(bounds, viewport)).getDistance() <= tolerance ->
      AnnotationHandle.END
    else -> null
  }

private fun AttachmentAnnotation.screenLength(bounds: Rect, viewport: Rect): Float {
  val start = start.toScreen(bounds, viewport)
  val end = end.toScreen(bounds, viewport)
  return hypot(end.x - start.x, end.y - start.y)
}

internal fun renderEditedBitmap(source: Bitmap, result: AttachmentEditResult): Bitmap {
  val crop = result.crop
  val sourceRect =
    AndroidRect(
      floor(crop.left * source.width).toInt().coerceIn(0, source.width - 1),
      floor(crop.top * source.height).toInt().coerceIn(0, source.height - 1),
      ceil(crop.right * source.width).toInt().coerceIn(1, source.width),
      ceil(crop.bottom * source.height).toInt().coerceIn(1, source.height),
    )
  val rawWidth = sourceRect.width().coerceAtLeast(1)
  val rawHeight = sourceRect.height().coerceAtLeast(1)
  val outputScale = min(1f, EDITOR_MAX_BITMAP_EDGE.toFloat() / max(rawWidth, rawHeight))
  val outputWidth = (rawWidth * outputScale).toInt().coerceAtLeast(1)
  val outputHeight = (rawHeight * outputScale).toInt().coerceAtLeast(1)
  val output = Bitmap.createBitmap(outputWidth, outputHeight, Bitmap.Config.ARGB_8888)
  val canvas = AndroidCanvas(output)
  canvas.drawColor(android.graphics.Color.BLACK)
  val bitmapPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG or AndroidPaint.FILTER_BITMAP_FLAG)
  canvas.drawBitmap(
    source,
    sourceRect,
    AndroidRectF(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat()),
    bitmapPaint,
  )
  canvas.save()
  canvas.clipRect(0f, 0f, outputWidth.toFloat(), outputHeight.toFloat())
  val strokeWidth = max(4f, min(outputWidth, outputHeight) * .006f)
  result.annotations.forEach { annotation ->
    val paint =
      AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
        color = annotation.color.toArgb()
        style = AndroidPaint.Style.STROKE
        strokeCap = AndroidPaint.Cap.ROUND
        strokeJoin = AndroidPaint.Join.ROUND
        this.strokeWidth = strokeWidth
      }
    fun map(point: Offset): android.graphics.PointF =
      android.graphics.PointF(
        (point.x * source.width - sourceRect.left) * outputScale,
        (point.y * source.height - sourceRect.top) * outputScale,
      )
    val start = map(annotation.start)
    val end = map(annotation.end)
    when (annotation.tool) {
      AttachmentEditorTool.ARROW -> {
        canvas.drawLine(start.x, start.y, end.x, end.y, paint)
        val angle = atan2(end.y - start.y, end.x - start.x)
        val spread = Math.toRadians(28.0).toFloat()
        val head = strokeWidth * 4.5f
        val path = AndroidPath().apply {
          moveTo(end.x, end.y)
          lineTo(end.x - cos(angle - spread) * head, end.y - sin(angle - spread) * head)
          moveTo(end.x, end.y)
          lineTo(end.x - cos(angle + spread) * head, end.y - sin(angle + spread) * head)
        }
        canvas.drawPath(path, paint)
      }
      AttachmentEditorTool.RECTANGLE, AttachmentEditorTool.CIRCLE -> {
        val rect =
          AndroidRectF(
            min(start.x, end.x),
            min(start.y, end.y),
            max(start.x, end.x),
            max(start.y, end.y),
          )
        if (annotation.tool == AttachmentEditorTool.RECTANGLE) canvas.drawRect(rect, paint)
        else canvas.drawOval(rect, paint)
      }
      AttachmentEditorTool.CROP -> Unit
    }
  }
  canvas.restore()
  return output
}
