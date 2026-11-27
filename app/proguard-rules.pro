# Правила R8 для Раундтрека (R8 включён в build.gradle).
#
# Приложение — обёртка WebView: страница вызывает Java через window.Android.
# R8 не видит эти вызовы и без правил удалил или переименовал бы методы моста —
# кнопки на странице молча перестали бы работать. Поэтому:

# 1. Все классы приложения сохраняются как есть (их немного, а риск сломать мост,
#    сервисы раундов и отсчёта или будильник — большой).
-keep class com.yuriilosiev.stopwatch.** { *; }

# 2. На всякий случай — любые методы с @JavascriptInterface в любом классе.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
-keepattributes *Annotation*

# 3. Без переименования: стек-трейсы сбоев в Play Console остаются читаемыми,
#    и не нужно загружать файл деобфускации.
-dontobfuscate
