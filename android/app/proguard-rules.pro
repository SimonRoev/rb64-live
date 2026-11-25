# RB64 Live - R8-Regeln
# Eigener Code bleibt vollstaendig und unverschleiert (klein, und so sicher vor Reflexion/Serialisierung durch Namen,
# z. B. Enum.valueOf aus gespeicherten Einstellungen). Verkleinert werden die Bibliotheken.
-keep class de.rb64.live.** { *; }
-keepattributes SourceFile,LineNumberTable,*Annotation*,Signature,InnerClasses,EnclosingMethod
# lesbare Absturzberichte
-renamesourcefileattribute SourceFile

# WorkManager laedt seine Room-Datenbank (WorkDatabase_Impl) ueber den Klassennamen
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.** { *; }
# Firebase findet seine Komponenten ueber Klassennamen aus dem Manifest
-keep class * implements com.google.firebase.components.ComponentRegistrar { <init>(); }
