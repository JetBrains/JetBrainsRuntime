# jetbrains/runtime:jbr14env
FROM centos:7

RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool gcc gcc-c++ libstdc++-devel alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel libXrandr-devel libXi-devel
# Install Java 11
RUN wget https://download.java.net/java/GA/jdk13.0.1/cec27d702aa74d5a8630c65ae61e4305/9/GPL/openjdk-13.0.1_linux-x64_bin.tar.gz \
-O - | tar xz -C /
ENV JAVA_HOME /jbrsdk
ENV PATH $JAVA_HOME/bin:$PATH
