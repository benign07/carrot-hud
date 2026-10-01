package com.carrot.hud

/** Rotating bounded attempts so one damaged record cannot starve later records. */
class ArchiveBatchCursor {
    enum class Visit { CACHED, ATTEMPTED, STOP }
    private var next = 0
    fun run(total: Int, limit: Int = 6, cancelled: () -> Boolean = { false }, visit: (Int) -> Visit) {
        require(total >= 0 && limit > 0)
        if (total == 0) { next = 0; return }
        val start = next % total
        var attempts = 0
        for (offset in 0 until total) {
            if (cancelled()) return
            val index = (start + offset) % total
            // Advance before invoking I/O, also on exceptions caught by the caller.
            next = (index + 1) % total
            when (visit(index)) {
                Visit.CACHED -> Unit
                Visit.STOP -> return
                Visit.ATTEMPTED -> if (++attempts >= limit) return
            }
        }
    }
}
