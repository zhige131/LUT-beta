# Apache FtpServer uses reflection for its message resource bundles.
-keep class org.apache.ftpserver.** { *; }
-keep class org.apache.mina.** { *; }
-dontwarn org.slf4j.**
-dontwarn org.springframework.**
-dontwarn javax.management.**
-dontwarn java.beans.**
