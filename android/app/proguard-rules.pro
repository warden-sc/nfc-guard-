# TensorFlow Lite: JNI가 리플렉션으로 접근하는 클래스 유지
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# 크래시 스택트레이스에서 줄 번호 확인 (Play Console에 매핑 파일 자동 업로드됨)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
