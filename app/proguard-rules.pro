# gomobile exposes these Java stubs to JNI. Keep their names and native methods.
-keep class core.** { *; }

# Keep source/line information so release crash reports can be retraced.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
