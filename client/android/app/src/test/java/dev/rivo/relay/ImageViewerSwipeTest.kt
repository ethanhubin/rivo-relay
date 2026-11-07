package dev.rivo.relay.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageViewerSwipeTest {
  @Test
  fun `shorter twelve percent swipe changes page`() {
    assertEquals(1, imagePageSwipeDelta(-120f, 0f, 1_000f, 700f, 0, 2))
    assertEquals(-1, imagePageSwipeDelta(120f, 0f, 1_000f, 700f, 1, 2))
  }

  @Test
  fun `quick short fling changes page`() {
    assertEquals(1, imagePageSwipeDelta(-40f, -701f, 1_000f, 700f, 0, 2))
    assertEquals(-1, imagePageSwipeDelta(40f, 701f, 1_000f, 700f, 1, 2))
  }

  @Test
  fun `release velocity wins when the user reverses direction`() {
    assertEquals(-1, imagePageSwipeDelta(-140f, 701f, 1_000f, 700f, 1, 2))
  }

  @Test
  fun `small slow drag settles on current page`() {
    assertEquals(0, imagePageSwipeDelta(-40f, -300f, 1_000f, 700f, 0, 2))
  }

  @Test
  fun `swipe does not cross gallery boundaries`() {
    assertEquals(0, imagePageSwipeDelta(200f, 900f, 1_000f, 700f, 0, 2))
    assertEquals(0, imagePageSwipeDelta(-200f, -900f, 1_000f, 700f, 2, 2))
  }
}
