-keepattributes Signature,*Annotation*,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations
-keep class kotlin.Metadata { *; }
-keep class com.anthropic.** { *; }
-keep class com.fasterxml.jackson.** { *; }
-keepclassmembers class * {
    @com.fasterxml.jackson.annotation.* <fields>;
    @com.fasterxml.jackson.annotation.* <methods>;
    @com.fasterxml.jackson.annotation.* <init>(...);
}
-dontwarn com.fasterxml.jackson.**
-dontwarn java.beans.**
-dontwarn org.w3c.dom.bootstrap.**
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn java.lang.reflect.AnnotatedParameterizedType
-dontwarn java.lang.reflect.AnnotatedType
-dontwarn com.google.auto.value.AutoValue
