# Retrofit methods are invoked from generated proxies.
-keepattributes Signature, InnerClasses, EnclosingMethod
-keepclasseswithmembers,allowshrinking,allowobfuscation,includedescriptorclasses class * {
    @retrofit2.http.* <methods>;
}

# Room entities are generated and validated at compile time.
-keep class * extends androidx.room.RoomDatabase { *; }
