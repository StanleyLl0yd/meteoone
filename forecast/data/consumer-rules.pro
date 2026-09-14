# JNI symbol lookup and FindClass/constructor lookup require these exact JVM names.
-keep class com.sl.meteoone.forecast.data.grib.EcCodesNativeBridge { *; }
-keep class com.sl.meteoone.forecast.data.grib.NativeGribMessage {
    <init>(long[], double[], double[]);
}
