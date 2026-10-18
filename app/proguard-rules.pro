-keep class androidx.compose.** { *; }
-dontwarn org.jetbrains.annotations.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Room
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# Our model classes are used reflectively by Room only for column mapping
-keepclassmembers class com.oceanreader.data.db.** { *; }