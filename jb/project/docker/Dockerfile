# jetbrains/runtime:jbr11env
FROM centos:7

RUN yum -y install zip bzip2 unzip tar wget make autoconf automake libtool gcc gcc-c++ libstdc++-devel alsa-devel cups-devel xorg-x11-devel libjpeg62-devel giflib-devel freetype-devel file which libXtst-devel libXt-devel libXrender-devel alsa-lib-devel fontconfig-devel libXrandr-devel libXi-devel
# Install Java 11
RUN wget https://bintray.com/jetbrains/intellij-jbr/download_file?file_path=jbrsdk-11_0_3-linux-x64-b360.2.tar.gz \
-O - | tar xz -C /
ENV JAVA_HOME /jbrsdk
ENV PATH $JAVA_HOME/bin:$PATH
