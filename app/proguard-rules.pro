# WebView 가 자바스크립트에서 부르는 다리. 이름이 바뀌면 웹에서 못 찾는다.
-keepclassmembers class com.henny.checklist.MainActivity$Bridge {
    public *;
}
-keepattributes JavascriptInterface

# 매니페스트에서만 참조되는 리시버
-keep class com.henny.checklist.notify.** { *; }
