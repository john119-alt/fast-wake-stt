package com.fastwake.app

object NativeEngine {
    init { System.loadLibrary("fastwake") }

    external fun create(model: String, vad: String, threads: Int, phrase: String): Long
    /** Returns "wake<TAB>decode_ms<TAB>transcript". */
    external fun feed(handle: Long, pcm: ShortArray, n: Int): String
    external fun reset(handle: Long)
    external fun free(handle: Long)
}
