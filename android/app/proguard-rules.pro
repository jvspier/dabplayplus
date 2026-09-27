# DABplay+ R8 rules. Nothing here uses reflection; the manifest keeps the Activity/Service/Application.
# Keep line numbers so crash stack traces from the head unit stay readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# dnsjava (RadioDNS lookups): compile-time-only references, never used at runtime
-dontwarn lombok.Generated
-dontwarn org.slf4j.impl.StaticLoggerBinder
