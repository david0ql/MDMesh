# Keep kotlinx.serialization generated serializers for protocol classes.
-keepclassmembers class com.dallycontrol.proto.** {
    *** Companion;
}
-keepclasseswithmembers class com.dallycontrol.proto.** {
    kotlinx.serialization.KSerializer serializer(...);
}
