# Keep the launcher activity and its inner classes
-keep class com.x10launcher.LauncherActivity { *; }
-keep class com.x10launcher.LauncherActivity$* { *; }

# Keep standard Android entry points
-keep public class * extends android.app.Activity
-keep public class * extends android.view.View

# Don't warn about missing classes we don't use
-dontwarn android.support.**
