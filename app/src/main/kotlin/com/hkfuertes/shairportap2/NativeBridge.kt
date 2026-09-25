package com.hkfuertes.shairportap2

object NativeBridge {
    private val loadError: String? = try {
        System.loadLibrary("shairport_ap2")
        null
    } catch (error: UnsatisfiedLinkError) {
        error.message ?: "Could not load the JNI library"
    }

    fun start(configPath: String): String? = loadError ?: nativeStart(configPath)

    fun stop() {
        if (loadError == null) nativeStop()
    }

    // ponytail: lifecycle smoke bridge only; replace with Shairport/NQPTP start after its Android build works.
    private external fun nativeStart(configPath: String): String?
    private external fun nativeStop()
}
