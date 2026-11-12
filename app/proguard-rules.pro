# Keep data model field names stable for Gson reflection if minification is enabled later.
-keep class com.example.morningalarm.data.Alarm { *; }
-keep class com.example.morningalarm.data.AlarmJsonCodec$AlarmDto { *; }
-keep class com.example.morningalarm.hermes.HermesProtocol$Response { *; }
-keep class com.example.morningalarm.hermes.HermesProtocol$CreateRequest { *; }
