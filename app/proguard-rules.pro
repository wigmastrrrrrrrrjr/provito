# Keep the JS bridge classes and their @JavascriptInterface methods -
# they are only ever attached to Provito's own chrome pages.
-keepclassmembers class com.provito.browser.MainActivity$SettingsBridge { public *; }
-keepclassmembers class com.provito.browser.MainActivity$TabsBridge { public *; }
-keep class com.provito.browser.MainActivity$SettingsBridge
-keep class com.provito.browser.MainActivity$TabsBridge
