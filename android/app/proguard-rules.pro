# HostAgent loads the capture pipeline by name:
#   Class.forName("com.rustdesk.java.host.HostFrameSource")
# so R8 must keep the class and its methods even though nothing references them statically.
# Without this rule, enabling minify makes the class vanish and the viewer silently gets no video.
-keep class com.rustdesk.java.host.HostFrameSource { *; }
