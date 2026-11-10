# UniFFI bindings call into Rust through JNA, which reflects over these.
-keep class com.sun.jna.** { *; }
-keep class * implements com.sun.jna.** { *; }
-keep class uniffi.calzone_ffi.** { *; }
-dontwarn java.awt.**
