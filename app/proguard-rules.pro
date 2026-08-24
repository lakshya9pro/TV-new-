# Keep data model classes (parsed via reflection-free manual JSON parsing, but keep names for debugging)
-keep class com.batz.tvlauncher.model.** { *; }

# Glide
-keep public class * implements com.bumptech.glide.module.GlideModule
-keep class * extends com.bumptech.glide.module.AppGlideModule {
 <init>(...);
}
-keep public enum com.bumptech.glide.load.ImageHeaderParser$ImageType {
  **[] $VALUES;
  public *;
}
