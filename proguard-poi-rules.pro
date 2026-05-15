# ProGuard rules for Apache POI library

# 忽略缺失的Java AWT和Swing类
-dontwarn java.awt.**
-dontwarn javax.swing.**
-dontwarn java.beans.**

# 忽略缺失的XML相关类
-dontwarn javax.xml.stream.**
-dontwarn org.xml.sax.**
-dontwarn org.w3c.dom.**

# 忽略缺失的Saxon类
-dontwarn net.sf.saxon.**

# 忽略缺失的日志类
-dontwarn org.apache.logging.log4j.**
-dontwarn org.slf4j.**

# 忽略缺失的OSGi类
-dontwarn org.osgi.**

# 忽略缺失的Maven相关类
-dontwarn org.apache.maven.**
-dontwarn org.apache.tools.ant.**

# 忽略缺失的JavaParser相关类
-dontwarn com.github.javaparser.**

# 忽略缺失的Sun XML resolver类
-dontwarn com.sun.org.apache.xml.internal.resolver.**

# 保留POI库的核心类
-keep class org.apache.poi.** { *; }
-keep class org.apache.xmlbeans.** { *; }
-keep class com.graphbuilder.** { *; }

# 保留POI库的反射相关类
-keep class org.apache.poi.ss.usermodel.** { *; }
-keep class org.apache.poi.xssf.usermodel.** { *; }
-keep class org.apache.poi.hssf.usermodel.** { *; }

# 保留XMLBeans相关类
-keep class org.apache.xmlbeans.impl.** { *; }
-keep class schemaorg_apache_xmlbeans.** { *; }

# 保留GraphBuilder相关类
-keep class com.graphbuilder.curve.** { *; }
-keep class com.graphbuilder.math.** { *; }

# 忽略无效的方法调用
-dontwarn org.apache.poi.ss.formula.functions.**
-dontwarn org.apache.poi.ss.formula.**
-dontwarn org.apache.xmlbeans.impl.tool.**
-dontwarn org.apache.xmlbeans.impl.config.**

# 忽略序列化相关警告
-dontwarn java.io.ObjectInputStream
-dontwarn java.io.ObjectOutputStream
-dontwarn java.io.Serializable

# 忽略其他可能的警告
-dontwarn com.graphbuilder.**
-dontwarn org.apache.xmlbeans.**