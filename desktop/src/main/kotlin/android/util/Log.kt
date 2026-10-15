package android.util

/** Desktop stand-in for Android's Log: lines go to stderr (seen in a terminal or the journal). */
object Log {
    @Volatile var verbose = System.getenv("WORLDTV_DEBUG") != null

    private fun line(level: Char, tag: String, msg: String, t: Throwable?): Int {
        if (level == 'D' && !verbose) return 0
        System.err.println("$level/$tag: $msg")
        t?.printStackTrace()
        return 0
    }

    @JvmStatic fun d(tag: String, msg: String) = line('D', tag, msg, null)
    @JvmStatic fun d(tag: String, msg: String, t: Throwable?) = line('D', tag, msg, t)
    @JvmStatic fun i(tag: String, msg: String) = line('I', tag, msg, null)
    @JvmStatic fun i(tag: String, msg: String, t: Throwable?) = line('I', tag, msg, t)
    @JvmStatic fun w(tag: String, msg: String) = line('W', tag, msg, null)
    @JvmStatic fun w(tag: String, msg: String, t: Throwable?) = line('W', tag, msg, t)
    @JvmStatic fun w(tag: String, t: Throwable?) = line('W', tag, "", t)
    @JvmStatic fun e(tag: String, msg: String) = line('E', tag, msg, null)
    @JvmStatic fun e(tag: String, msg: String, t: Throwable?) = line('E', tag, msg, t)
}
