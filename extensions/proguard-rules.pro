-dontobfuscate
-dontoptimize
-keepattributes *
# From AGP 9.2 the wildcard above no longer matches the RuntimeInvisible attributes, so it would
# quietly stop keeping them once the Morphe settings plugin brings that AGP in. Naming them
# alongside it changes nothing today and keeps them when the wildcard stops covering them.
#
# Measured on 2026-09-07: with and without these three lines the R8 output is byte for byte the
# same, so nothing changes until the AGP that needs them arrives. Replacing the wildcard outright
# was tried and put back, because nothing here can run the resulting DEX to show what a named
# list would have dropped.
-keepattributes RuntimeInvisibleAnnotations,RuntimeInvisibleParameterAnnotations
-keepattributes RuntimeInvisibleTypeAnnotations
# Extension classes are called from injected target bytecode and by reflection.
# Keep the extension namespace intact while allowing unrelated Morphe classes to shrink.
-keep class app.morphe.extension.** {
  *;
}
# Proguard can strip away kotlin intrinsics methods that are used by extension Kotlin code. Unclear why.
-keep class kotlin.jvm.internal.Intrinsics {
    public static *;
}
-dontwarn javax.lang.model.element.Modifier
