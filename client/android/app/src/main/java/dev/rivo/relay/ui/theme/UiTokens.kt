package dev.rivo.relay.ui.theme

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/** Layout tokens shared by Rivo's Compose screens. */
object RivoSpacing {
  val Xs = 4.dp
  val Sm = 8.dp
  val Md = 12.dp
  val Lg = 16.dp
  val Xl = 20.dp
  val Xxl = 24.dp
  val Section = 32.dp
  val Page = 20.dp
  val PanelContentHorizontal = 18.dp
  val KeyboardGap = 4.dp
}

object RivoSize {
  val MinTouchTarget = 44.dp
  val ControlHeight = 48.dp
  val ActionIcon = 23.dp
  val SmallActionIcon = 18.dp
  val NavigationHeight = 72.dp
  val AgentIcon = 52.dp
}

/** Component radii based on the Todo screen without changing Material defaults used by chat. */
object RivoRadius {
  val ExtraSmall = 8.dp
  val Control = 12.dp
  val Card = 18.dp
  val Panel = 22.dp
  val Dialog = 24.dp
}

/** Shared button insets derived from the compact controls on the Todo screen. */
object RivoButtonPadding {
  val Compact = PaddingValues(horizontal = RivoSpacing.Md, vertical = 0.dp)
  val Segmented = PaddingValues(horizontal = RivoSpacing.Xs, vertical = 0.dp)
  val Icon = PaddingValues(0.dp)
}
