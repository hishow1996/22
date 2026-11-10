# 批注与会话数据通过 org.json 按字段名读写，保留数据类与常量
-keep class com.moye.pdfink.Mark { *; }
-keep class com.moye.pdfink.BrushPreset { *; }
-keep class com.moye.pdfink.DocSession { *; }
-keep class com.moye.pdfink.InkHistory$Change { *; }
-keep class com.moye.pdfink.**$Companion { *; }
-keepclassmembers class com.moye.pdfink.** {
    public static *** Companion;
}
-dontwarn org.json.**
