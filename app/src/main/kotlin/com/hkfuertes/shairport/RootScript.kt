package com.hkfuertes.shairport

internal const val ROOT_READY = "@root ready"
internal const val NQPTP_MARKER = "@nqptp"
internal const val WIFI_MARKER = "@wifi"

/** Root protects Wi-Fi for either AirPlay mode; only AirPlay 2 also starts NQPTP. */
internal fun rootScript(binary: String, directory: String, airplay2: Boolean): String {
    fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    val shm = quote(directory)
    return """
        trap '' PIPE
        exec 4<&0
        # Android's mksh closes private FDs on exec: use stdin for the lifetime lock.
        exec 0>$shm/root.lock
        flock -x 0 || exit 1
        nqptp=
        wifi_mode=
        cleanup() {
            if [ -n "${'$'}wifi_mode" ]; then
                cmd wifi "force-${'$'}wifi_mode-mode" disabled >/dev/null 2>&1
            fi
            if [ -n "${'$'}nqptp" ]; then
                kill ${'$'}nqptp 2>/dev/null
                sleep 1
                kill -9 ${'$'}nqptp 2>/dev/null
            fi
            echo "$NQPTP_MARKER down"
        }
        trap cleanup EXIT
        trap 'exit' HUP INT TERM
        if cmd wifi force-low-latency-mode enabled >/dev/null 2>&1; then
            wifi_mode=low-latency
        elif cmd wifi force-hi-perf-mode enabled >/dev/null 2>&1; then
            wifi_mode=hi-perf
        fi
        if [ -n "${'$'}wifi_mode" ]; then echo "$WIFI_MARKER up"; else echo "$WIFI_MARKER down"; fi
        ${if (airplay2) """
        old=${'$'}(pidof libnqptp.so)
        if [ -n "${'$'}old" ]; then kill ${'$'}old; sleep 0.5; kill -9 ${'$'}old; fi 2>/dev/null
        export NQPTP_SHM_DIRECTORY=$shm
        rm -f $shm/nqptp
        ${quote(binary)} 0</dev/null 4<&- &
        nqptp=${'$'}!
        i=0
        while [ ! -s $shm/nqptp ] && [ ${'$'}i -lt 50 ]; do sleep 0.1; i=${'$'}((i + 1)); done
        if [ -s $shm/nqptp ]; then echo "$NQPTP_MARKER up"; else echo "$NQPTP_MARKER down"; fi
        """ else ""}
        echo "$ROOT_READY"
        read -r _ <&4 || true
    """.trimIndent()
}
