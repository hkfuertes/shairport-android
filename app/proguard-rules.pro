# Shairport's native code calls Engine's methods by name: keep the app as written.
-keep class com.hkfuertes.shairport.** { *; }
# Java-WebSocket logs through SLF4J, which has no provider here (a no-op).
-dontwarn org.slf4j.**
