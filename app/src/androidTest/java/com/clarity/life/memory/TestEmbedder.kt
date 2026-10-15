package com.clarity.life.memory

import androidx.test.platform.app.InstrumentationRegistry

/**
 * One meaning model for every emulator test, as the app has one (a Hilt singleton). Each Embedder holds its own
 * ONNX Runtime session; with gte-base-en-v1.5 (Max plan Phase 4, 147 MB) a new one per test filled the
 * emulator's memory and the test process was killed.
 */
object TestEmbedder {
    val shared: Embedder by lazy { Embedder(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext) }
}
